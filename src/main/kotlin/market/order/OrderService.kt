package market.order

import market.Actor
import market.Fault
import market.model.OrderCreate
import market.model.OrderItem
import market.model.OrderLine
import market.model.OrderResponse
import market.model.OrderState
import market.model.OrderStatus
import market.model.OrderUpdate
import market.model.PromoKind
import market.model.PromoResponse
import market.promo.PromoRepo
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class OrderService(
    private val repo: OrderRepo,
    private val promos: PromoRepo,
    private val clock: Clock,
    @Value("\${order.interval-minutes:1}") private val minutes: Long
) {
    private val zero = BigDecimal("0.00")
    private val max = BigDecimal("9999999999.99")

    init {
        require(minutes >= 0)
    }

    @Transactional
    fun create(actor: Actor, body: OrderCreate): OrderResponse {
        allow(actor)
        OrderInput.check(body.items, body.promoCode)
        repo.lockUsers(listOf(actor.id))
        limit(actor.id, "CREATE_ORDER")
        if (repo.active(actor.id)) throw Fault("ORDER_HAS_ACTIVE", 409, "У пользователя уже есть активный заказ")
        val stocks = repo.stocks(body.items.map { it.productId })
        catalog(body.items, stocks)
        reserve(body.items, stocks)
        val items = snapshot(body.items, stocks)
        val sum = sum(items)
        val promo = body.promoCode?.let {
            promos.byCode(it) ?: throw Fault("PROMO_CODE_INVALID", 422, "Промокод не найден")
        }
        var cut = zero
        if (promo != null) {
            valid(promo, true)
            if (sum < promo.minOrderAmount) throw Fault("PROMO_CODE_MIN_AMOUNT", 422, "Сумма ниже минимума промокода")
            cut = discount(sum, promo)
            promos.use(promo.id, 1)
        }
        val id = repo.create(actor.id, money(sum - cut), cut, promo?.id)
        repo.items(id, items)
        record(actor.id, "CREATE_ORDER")
        return repo.find(id)!!
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(actor: Actor, id: UUID): OrderResponse {
        allow(actor)
        val order = repo.find(id) ?: missing()
        owner(actor, order.userId)
        return order
    }

    @Transactional
    fun update(actor: Actor, id: UUID, body: OrderUpdate): OrderResponse {
        allow(actor)
        OrderInput.check(body.items)
        val order = locked(actor, id)
        if (order.status != OrderStatus.CREATED) state()
        limit(actor.id, "UPDATE_ORDER")
        val ids = order.items.map { it.productId } + body.items.map { it.productId }
        val stocks = restore(order.items, repo.stocks(ids))
        catalog(body.items, stocks)
        reserve(body.items, stocks)
        val items = snapshot(body.items, stocks, order.items)
        val sum = sum(items)
        var promo = order.promoCodeId?.let { requireNotNull(promos.find(it)) }
        var cut = zero
        if (promo != null) {
            valid(promo, false)
            if (sum < promo.minOrderAmount) {
                promos.use(promo.id, -1)
                promo = null
            } else {
                cut = discount(sum, promo)
            }
        }
        repo.update(id, money(sum - cut), cut, promo?.id)
        repo.items(id, items)
        record(actor.id, "UPDATE_ORDER")
        return repo.find(id)!!
    }

    @Transactional
    fun cancel(actor: Actor, id: UUID): OrderResponse {
        allow(actor)
        val order = locked(actor, id)
        if (order.status !in listOf(OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING)) state()
        restore(order.items, repo.stocks(order.items.map { it.productId }))
        order.promoCodeId?.let {
            val promo = requireNotNull(promos.find(it))
            promos.use(promo.id, -1)
        }
        repo.status(id, OrderStatus.CANCELED)
        return repo.find(id)!!
    }

    @Transactional
    fun status(actor: Actor, id: UUID, body: OrderState): OrderResponse {
        if (actor.role != "ADMIN") denied()
        val order = locked(actor, id)
        val next = when (order.status) {
            OrderStatus.CREATED -> OrderStatus.PAYMENT_PENDING
            OrderStatus.PAYMENT_PENDING -> OrderStatus.PAID
            OrderStatus.PAID -> OrderStatus.SHIPPED
            OrderStatus.SHIPPED -> OrderStatus.COMPLETED
            else -> state()
        }
        if (body.status != next) state()
        repo.status(id, next)
        return repo.find(id)!!
    }

    private fun locked(actor: Actor, id: UUID): OrderResponse {
        val user = repo.owner(id) ?: missing()
        owner(actor, user)
        repo.lockUsers(listOf(actor.id, user))
        val order = repo.find(id, true) ?: missing()
        owner(actor, order.userId)
        return order
    }

    private fun allow(actor: Actor) {
        if (actor.role !in listOf("USER", "ADMIN")) denied()
    }

    private fun owner(actor: Actor, id: UUID) {
        if (actor.role != "ADMIN" && actor.id != id) {
            throw Fault("ORDER_OWNERSHIP_VIOLATION", 403, "Заказ принадлежит другому пользователю")
        }
    }

    private fun limit(id: UUID, type: String) {
        if (minutes == 0L) return
        val last = repo.last(id, type) ?: return
        if (last.plusMinutes(minutes).toInstant().isAfter(clock.instant())) {
            throw Fault("ORDER_LIMIT_EXCEEDED", 429, "Слишком частые операции с заказами")
        }
    }

    private fun record(id: UUID, type: String) {
        repo.record(id, type, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
    }

    private fun catalog(items: List<OrderLine>, stocks: Map<UUID, Stock>) {
        items.forEach {
            val stock = stocks[it.productId] ?: throw Fault("PRODUCT_NOT_FOUND", 404, "Товар не найден")
            if (stock.status != "ACTIVE") throw Fault("PRODUCT_INACTIVE", 409, "Товар неактивен")
        }
    }

    private fun reserve(items: List<OrderLine>, stocks: Map<UUID, Stock>) {
        val missing = items.filter { stocks.getValue(it.productId).stock < it.quantity }.map {
            mapOf("product_id" to it.productId, "requested" to it.quantity, "available" to stocks.getValue(it.productId).stock)
        }
        if (missing.isNotEmpty()) throw Fault("INSUFFICIENT_STOCK", 409, "Недостаточно товара", mapOf("items" to missing))
        items.forEach { repo.stock(it.productId, stocks.getValue(it.productId).stock - it.quantity) }
    }

    private fun restore(items: List<OrderItem>, stocks: Map<UUID, Stock>): Map<UUID, Stock> {
        val restored = stocks.toMutableMap()
        items.forEach {
            val stock = stocks.getValue(it.productId)
            val qty = stock.stock.toLong() + it.quantity
            if (qty > Int.MAX_VALUE) bad("items", "Возврат товара превышает допустимый остаток")
            restored[it.productId] = stock.copy(stock = qty.toInt())
        }
        items.forEach { repo.stock(it.productId, restored.getValue(it.productId).stock) }
        return restored
    }

    private fun snapshot(
        items: List<OrderLine>,
        stocks: Map<UUID, Stock>,
        old: List<OrderItem> = emptyList()
    ): List<OrderItem> {
        val prices = old.associateBy { it.productId }
        return items.map {
            val item = prices[it.productId]
            OrderItem(
                id = item?.id ?: UUID.randomUUID(),
                productId = it.productId,
                quantity = it.quantity,
                priceAtOrder = item?.priceAtOrder ?: stocks.getValue(it.productId).price
            )
        }
    }

    private fun sum(items: List<OrderItem>): BigDecimal =
        money(items.fold(zero) { total, item -> total + item.priceAtOrder * item.quantity.toBigDecimal() })

    private fun valid(promo: PromoResponse, fresh: Boolean) {
        val now = clock.instant()
        if (!promo.active || now.isBefore(promo.validFrom.toInstant()) || now.isAfter(promo.validUntil.toInstant()) ||
            (fresh && promo.currentUses >= promo.maxUses)
        ) {
            throw Fault("PROMO_CODE_INVALID", 422, "Промокод недоступен")
        }
    }

    private fun discount(sum: BigDecimal, promo: PromoResponse): BigDecimal = money(
        when (promo.discountType) {
            PromoKind.PERCENTAGE -> if (promo.discountValue > BigDecimal("70")) zero
            else (sum * promo.discountValue).divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)
            PromoKind.FIXED_AMOUNT -> promo.discountValue.min(sum)
        }
    )

    private fun money(value: BigDecimal): BigDecimal {
        val amount = value.setScale(2, RoundingMode.HALF_UP)
        if (amount < zero || amount > max) bad("total_amount", "Сумма заказа превышает допустимый размер")
        return amount
    }

    private fun bad(field: String, message: String): Nothing = throw Fault(
        "VALIDATION_ERROR", 400, "Некорректные данные",
        mapOf("fields" to listOf(mapOf("field" to field, "message" to message)))
    )

    private fun missing(): Nothing = throw Fault("ORDER_NOT_FOUND", 404, "Заказ не найден")

    private fun state(): Nothing = throw Fault("INVALID_STATE_TRANSITION", 409, "Недопустимый переход состояния заказа")

    private fun denied(): Nothing = throw Fault("ACCESS_DENIED", 403, "Недостаточно прав")
}
