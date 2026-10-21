package market.order

import market.Fault
import market.model.OrderItem
import market.model.OrderResponse
import market.model.OrderStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class Stock(val id: UUID, val price: BigDecimal, val stock: Int, val status: String)

@Repository
class OrderRepo(private val db: JdbcTemplate) {
    private val row = RowMapper { rs, _ ->
        OrderResponse(
            id = rs.getObject("id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            status = OrderStatus.valueOf(rs.getString("status")),
            totalAmount = rs.getBigDecimal("total_amount"),
            discountAmount = rs.getBigDecimal("discount_amount"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
            items = emptyList(),
            promoCodeId = rs.getObject("promo_code_id", UUID::class.java)
        )
    }

    fun find(id: UUID, lock: Boolean = false): OrderResponse? {
        val sql = "SELECT * FROM orders WHERE id = ?" + if (lock) " FOR UPDATE" else ""
        val order = db.query(sql, row, id).singleOrNull() ?: return null
        val items = db.query("SELECT * FROM order_items WHERE order_id = ? ORDER BY product_id", { rs, _ ->
            OrderItem(
                id = rs.getObject("id", UUID::class.java),
                productId = rs.getObject("product_id", UUID::class.java),
                quantity = rs.getInt("quantity"),
                priceAtOrder = rs.getBigDecimal("price_at_order")
            )
        }, id)
        return order.copy(items = items)
    }

    fun owner(id: UUID): UUID? = db.query(
        "SELECT user_id FROM orders WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, id
    ).singleOrNull()

    fun lockUsers(ids: List<UUID>) {
        ids.distinct().sortedBy { it.toString() }.forEach { id ->
            val found = db.query("SELECT id FROM users WHERE id = ? FOR UPDATE", { rs, _ ->
                rs.getObject(1, UUID::class.java)
            }, id)
            if (found.isEmpty()) throw Fault("TOKEN_INVALID", 401, "Пользователь не найден")
        }
    }

    fun active(id: UUID): Boolean = db.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM orders WHERE user_id = ? AND status IN ('CREATED', 'PAYMENT_PENDING'))",
        Boolean::class.java, id
    )!!

    fun last(id: UUID, type: String): OffsetDateTime? = db.query(
        "SELECT created_at FROM user_operations WHERE user_id = ? AND operation_type = ? ORDER BY created_at DESC LIMIT 1",
        { rs, _ -> rs.getObject(1, OffsetDateTime::class.java) }, id, type
    ).firstOrNull()

    fun record(id: UUID, type: String, time: OffsetDateTime) {
        db.update("INSERT INTO user_operations (user_id, operation_type, created_at) VALUES (?, ?, ?)", id, type, time)
    }

    fun stocks(ids: List<UUID>): Map<UUID, Stock> {
        val stocks = linkedMapOf<UUID, Stock>()
        ids.distinct().sortedBy { it.toString() }.forEach { id ->
            val stock = db.query("SELECT id, price, stock, status FROM products WHERE id = ? FOR UPDATE", { rs, _ ->
                Stock(rs.getObject("id", UUID::class.java), rs.getBigDecimal("price"), rs.getInt("stock"), rs.getString("status"))
            }, id).singleOrNull()
            if (stock != null) stocks[id] = stock
        }
        return stocks
    }

    fun stock(id: UUID, qty: Int) {
        db.update("UPDATE products SET stock = ? WHERE id = ?", qty, id)
    }

    fun create(user: UUID, total: BigDecimal, discount: BigDecimal, promo: UUID?): UUID {
        val id = UUID.randomUUID()
        db.update(
            "INSERT INTO orders (id, user_id, status, total_amount, discount_amount, promo_code_id) VALUES (?, ?, 'CREATED', ?, ?, ?)",
            id, user, total, discount, promo
        )
        return id
    }

    fun update(id: UUID, total: BigDecimal, discount: BigDecimal, promo: UUID?) {
        db.update(
            "UPDATE orders SET total_amount = ?, discount_amount = ?, promo_code_id = ? WHERE id = ?",
            total, discount, promo, id
        )
    }

    fun status(id: UUID, status: OrderStatus) {
        db.update("UPDATE orders SET status = ? WHERE id = ?", status.name, id)
    }

    fun items(id: UUID, items: List<OrderItem>) {
        db.update("DELETE FROM order_items WHERE order_id = ?", id)
        items.forEach {
            db.update(
                "INSERT INTO order_items (id, order_id, product_id, quantity, price_at_order) VALUES (?, ?, ?, ?, ?)",
                it.id, id, it.productId, it.quantity, it.priceAtOrder
            )
        }
    }
}
