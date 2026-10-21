package market

import market.model.OrderCreate
import market.model.OrderLine
import market.model.OrderState
import market.model.OrderStatus
import market.model.OrderUpdate
import market.model.PromoCreate
import market.model.PromoKind
import market.model.PromoResponse
import market.order.OrderService
import market.promo.PromoService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class OrderTest : OrderData() {
    @Test
    fun `rate precedes active check and operation types have separate limits`() {
        val user = user()
        val id = product()
        val order = orders.create(user, cart(id))
        fault("ORDER_LIMIT_EXCEEDED", 429) { orders.create(user, cart(id)) }
        age()
        fault("ORDER_HAS_ACTIVE", 409) { orders.create(user, cart(id)) }
        orders.update(user, order.id, OrderUpdate(listOf(OrderLine(id, 2))))
        fault("ORDER_LIMIT_EXCEEDED", 429) { orders.update(user, order.id, OrderUpdate(listOf(OrderLine(id, 1)))) }
        assertThat(count("user_operations")).isEqualTo(2)
    }

    @Test
    fun `catalog checks precede stock and failures do not use the rate limit`() {
        val user = user()
        val empty = product(stock = 0)
        val off = product(status = "INACTIVE")
        fault("PRODUCT_NOT_FOUND", 404) {
            orders.create(user, OrderCreate(listOf(OrderLine(empty, 1), OrderLine(UUID.randomUUID(), 1))))
        }
        fault("PRODUCT_INACTIVE", 409) { orders.create(user, cart(off)) }
        val error = fault("INSUFFICIENT_STOCK", 409) { orders.create(user, cart(empty)) }
        val fields = error.details!!["items"] as List<*>
        assertThat(fields).hasSize(1)
        val item = fields.single() as Map<*, *>
        assertThat(item["product_id"].toString()).isEqualTo(empty.toString())
        assertThat(item["requested"]).isEqualTo(1)
        assertThat(item["available"]).isEqualTo(0)
        assertThat(count("user_operations")).isZero()
        orders.create(user, cart(product()))
        assertThat(count("user_operations")).isEqualTo(1)
    }

    @Test
    fun `invalid promo rolls back every reserved item`() {
        val user = user()
        val first = product()
        val second = product()
        val body = OrderCreate(listOf(OrderLine(first, 3), OrderLine(second, 4)), "NONE")
        fault("PROMO_CODE_INVALID", 422) { orders.create(user, body) }
        assertThat(stock(first)).isEqualTo(10)
        assertThat(stock(second)).isEqualTo(10)
        assertThat(count("orders")).isZero()
        assertThat(count("order_items")).isZero()
        assertThat(count("user_operations")).isZero()
        orders.create(user, body.copy(promoCode = null))
    }

    @Test
    fun `update retains snapshots and uses current prices for new items`() {
        val user = user()
        val first = product()
        val second = product("30.00")
        val order = orders.create(user, cart(first, 2))
        db.update("UPDATE products SET price = 20 WHERE id = ?", first)
        assertThat(orders.get(user, order.id).totalAmount).isEqualByComparingTo("20.00")
        val saved = orders.update(user, order.id, OrderUpdate(listOf(OrderLine(first, 3), OrderLine(second, 1))))
        assertThat(saved.totalAmount).isEqualByComparingTo("60.00")
        assertThat(saved.items.first { it.productId == first }.priceAtOrder).isEqualByComparingTo("10.00")
        assertThat(saved.items.first { it.productId == second }.priceAtOrder).isEqualByComparingTo("30.00")
        assertThat(stock(first)).isEqualTo(7)
        assertThat(stock(second)).isEqualTo(9)
    }

    @Test
    fun `failed update restores order and stock without recording an operation`() {
        val user = user()
        val first = product()
        val empty = product(stock = 0)
        val order = orders.create(user, cart(first, 2))
        fault("INSUFFICIENT_STOCK", 409) {
            orders.update(user, order.id, OrderUpdate(listOf(OrderLine(first, 3), OrderLine(empty, 1))))
        }
        assertThat(orders.get(user, order.id)).isEqualTo(order)
        assertThat(stock(first)).isEqualTo(8)
        assertThat(stock(empty)).isZero()
        assertThat(count("user_operations")).isEqualTo(1)
        orders.update(user, order.id, OrderUpdate(listOf(OrderLine(first, 1))))
        assertThat(stock(first)).isEqualTo(9)
    }

    @ParameterizedTest
    @CsvSource("PERCENTAGE,70.00,70.00,30.00", "PERCENTAGE,71.00,0.00,100.00", "FIXED_AMOUNT,150.00,100.00,0.00")
    fun `discount rules`(kind: PromoKind, value: String, discount: String, total: String) {
        val promo = promo(kind = kind, value = value)
        val order = orders.create(user(), cart(product("100.00"), code = promo.code))
        assertThat(order.discountAmount).isEqualByComparingTo(discount)
        assertThat(order.totalAmount).isEqualByComparingTo(total)
        assertThat(uses(promo.id)).isEqualTo(1)
    }

    @Test
    fun `update retains its own last promo use`() {
        val user = user()
        val id = product("100.00")
        val promo = promo(max = 1)
        val order = orders.create(user, cart(id, code = promo.code))
        val saved = orders.update(user, order.id, OrderUpdate(listOf(OrderLine(id, 2))))
        assertThat(saved.promoCodeId).isEqualTo(promo.id)
        assertThat(saved.totalAmount).isEqualByComparingTo("180.00")
        assertThat(uses(promo.id)).isEqualTo(1)
    }

    @Test
    fun `update below minimum removes promo and returns its use`() {
        val user = user()
        val id = product("60.00")
        val promo = promo(min = "100.00")
        fault("PROMO_CODE_MIN_AMOUNT", 422) { orders.create(user, cart(id, code = promo.code)) }
        val order = orders.create(user, cart(id, 2, promo.code))
        val saved = orders.update(user, order.id, OrderUpdate(listOf(OrderLine(id, 1))))
        assertThat(saved.promoCodeId).isNull()
        assertThat(saved.discountAmount).isEqualByComparingTo("0.00")
        assertThat(saved.totalAmount).isEqualByComparingTo("60.00")
        assertThat(uses(promo.id)).isZero()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `expired or disabled promo rolls back update`(expired: Boolean) {
        val user = user()
        val id = product("100.00")
        val promo = promo()
        val order = orders.create(user, cart(id, code = promo.code))
        if (expired) db.update("UPDATE promo_codes SET valid_until = now() - interval '1 hour' WHERE id = ?", promo.id)
        else db.update("UPDATE promo_codes SET active = false WHERE id = ?", promo.id)
        fault("PROMO_CODE_INVALID", 422) { orders.update(user, order.id, OrderUpdate(listOf(OrderLine(id, 2)))) }
        assertThat(orders.get(user, order.id)).isEqualTo(order)
        assertThat(stock(id)).isEqualTo(9)
        assertThat(uses(promo.id)).isEqualTo(1)
        assertThat(count("user_operations")).isEqualTo(1)
    }

    @Test
    fun `cancel pending order releases stock and promo once`() {
        val user = user()
        val id = product()
        val promo = promo()
        val order = orders.create(user, cart(id, 3, promo.code))
        orders.status(user("ADMIN"), order.id, OrderState(OrderStatus.PAYMENT_PENDING))
        assertThat(orders.cancel(user, order.id).status).isEqualTo(OrderStatus.CANCELED)
        assertThat(stock(id)).isEqualTo(10)
        assertThat(uses(promo.id)).isZero()
        fault("INVALID_STATE_TRANSITION", 409) { orders.cancel(user, order.id) }
        assertThat(stock(id)).isEqualTo(10)
        assertThat(uses(promo.id)).isZero()
    }

    @Test
    fun `states advance in sequence and completed order cannot change`() {
        val user = user()
        val admin = user("ADMIN")
        val id = product()
        val order = orders.create(user, cart(id))
        fault("INVALID_STATE_TRANSITION", 409) { orders.status(admin, order.id, OrderState(OrderStatus.PAID)) }
        fault("ACCESS_DENIED", 403) { orders.status(user, order.id, OrderState(OrderStatus.PAYMENT_PENDING)) }
        for (state in listOf(OrderStatus.PAYMENT_PENDING, OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED)) {
            assertThat(orders.status(admin, order.id, OrderState(state)).status).isEqualTo(state)
        }
        fault("INVALID_STATE_TRANSITION", 409) { orders.cancel(user, order.id) }
        fault("INVALID_STATE_TRANSITION", 409) { orders.update(user, order.id, OrderUpdate(listOf(OrderLine(id, 1)))) }
        assertThat(stock(id)).isEqualTo(9)
    }

    @Test
    fun `roles ownership and admin access`() {
        val user = user()
        val other = user()
        val seller = user("SELLER")
        val admin = user("ADMIN")
        val id = product()
        val order = orders.create(user, cart(id))
        fault("ACCESS_DENIED", 403) { orders.create(seller, cart(id)) }
        fault("ACCESS_DENIED", 403) { orders.get(seller, order.id) }
        fault("ACCESS_DENIED", 403) { orders.update(seller, order.id, OrderUpdate(listOf(OrderLine(id, 1)))) }
        fault("ACCESS_DENIED", 403) { orders.cancel(seller, order.id) }
        fault("ORDER_OWNERSHIP_VIOLATION", 403) { orders.get(other, order.id) }
        fault("ORDER_OWNERSHIP_VIOLATION", 403) { orders.update(other, order.id, OrderUpdate(listOf(OrderLine(id, 1)))) }
        fault("ORDER_OWNERSHIP_VIOLATION", 403) { orders.cancel(other, order.id) }
        assertThat(orders.get(admin, order.id)).isEqualTo(order)
        assertThat(orders.update(admin, order.id, OrderUpdate(listOf(OrderLine(id, 2)))).userId).isEqualTo(user.id)
        assertThat(orders.cancel(admin, order.id).status).isEqualTo(OrderStatus.CANCELED)
        fault("ORDER_NOT_FOUND", 404) { orders.get(user, UUID.randomUUID()) }
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class OrderData {
    @Autowired
    protected lateinit var orders: OrderService
    @Autowired
    protected lateinit var promos: PromoService
    @Autowired
    protected lateinit var db: JdbcTemplate

    @AfterEach
    fun clear() {
        db.execute("TRUNCATE users, products, promo_codes CASCADE")
    }

    protected fun user(role: String = "USER"): Actor {
        val id = UUID.randomUUID()
        db.update("INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, 'hash', ?)", id, "$id@test.local", role)
        return Actor(id, role)
    }

    protected fun product(price: String = "10.00", stock: Int = 10, status: String = "ACTIVE"): UUID {
        val id = UUID.randomUUID()
        db.update(
            "INSERT INTO products (id, name, price, stock, category, status) VALUES (?, 'Book', ?, ?, 'Books', ?)",
            id, BigDecimal(price), stock, status
        )
        return id
    }

    protected fun promo(kind: PromoKind = PromoKind.PERCENTAGE, value: String = "10.00", min: String = "0.00", max: Int = 10): PromoResponse =
        promos.create(PromoCreate("SAVE_10", kind, BigDecimal(value), BigDecimal(min), max, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1)))

    protected fun cart(id: UUID, qty: Int = 1, code: String? = null) = OrderCreate(listOf(OrderLine(id, qty)), code)

    protected fun stock(id: UUID): Int = db.queryForObject("SELECT stock FROM products WHERE id = ?", Int::class.java, id)!!

    protected fun uses(id: UUID): Int = db.queryForObject("SELECT current_uses FROM promo_codes WHERE id = ?", Int::class.java, id)!!

    protected fun count(table: String): Long = db.queryForObject("SELECT count(*) FROM $table", Long::class.java)!!

    protected fun age() {
        db.update("UPDATE user_operations SET created_at = now() - interval '2 minutes'")
    }

    protected fun fault(code: String, status: Int, block: () -> Any?): Fault {
        val error = assertThrows<Fault> { block() }
        assertThat(error.code).isEqualTo(code)
        assertThat(error.status).isEqualTo(status)
        return error
    }
}
