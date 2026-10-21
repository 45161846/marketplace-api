package market

import market.model.OrderLine
import market.model.OrderStatus
import market.model.OrderUpdate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AmountTest : OrderData() {
    @Test
    fun `create allows large subtotal when total and discount fit`() {
        val actor = user()
        val id = product("5000000000.00")
        val promo = promo(value = "50.00")
        val order = orders.create(actor, cart(id, 2, promo.code))
        assertThat(order.totalAmount).isEqualByComparingTo("5000000000.00")
        assertThat(order.discountAmount).isEqualByComparingTo("5000000000.00")
        assertThat(stock(id)).isEqualTo(8)
        assertThat(uses(promo.id)).isEqualTo(1)
    }

    @Test
    fun `update allows large subtotal when total and discount fit`() {
        val actor = user()
        val id = product("5000000000.00")
        val promo = promo(value = "50.00")
        val order = orders.create(actor, cart(id, code = promo.code))
        val saved = orders.update(actor, order.id, OrderUpdate(listOf(OrderLine(id, 2))))
        assertThat(saved.totalAmount).isEqualByComparingTo("5000000000.00")
        assertThat(saved.discountAmount).isEqualByComparingTo("5000000000.00")
        assertThat(stock(id)).isEqualTo(8)
        assertThat(uses(promo.id)).isEqualTo(1)
    }

    @Test
    fun `total overflow rolls back reservation`() {
        val actor = user()
        val id = product("9999999999.99")
        fault("VALIDATION_ERROR", 400) { orders.create(actor, cart(id, 2)) }
        assertThat(stock(id)).isEqualTo(10)
        assertThat(count("orders")).isZero()
        assertThat(count("order_items")).isZero()
        assertThat(count("user_operations")).isZero()
    }

    @Test
    fun `stock overflow rolls back cancellation and retains promo`() {
        val actor = user()
        val id = product()
        val promo = promo()
        val order = orders.create(actor, cart(id, code = promo.code))
        db.update("UPDATE products SET stock = ? WHERE id = ?", Int.MAX_VALUE, id)
        fault("VALIDATION_ERROR", 400) { orders.cancel(actor, order.id) }
        val saved = orders.get(actor, order.id)
        assertThat(saved).isEqualTo(order)
        assertThat(saved.status).isEqualTo(OrderStatus.CREATED)
        assertThat(stock(id)).isEqualTo(Int.MAX_VALUE)
        assertThat(uses(promo.id)).isEqualTo(1)
    }

    @ParameterizedTest
    @CsvSource("50.00,0.02,0.01", "16.50,0.00,0.03")
    fun `percentage rounds once with half up`(percent: String, discount: String, total: String) {
        val promo = promo(value = percent)
        val order = orders.create(user(), cart(product("0.03"), code = promo.code))
        assertThat(order.discountAmount).isEqualByComparingTo(discount)
        assertThat(order.totalAmount).isEqualByComparingTo(total)
        assertThat(uses(promo.id)).isEqualTo(1)
    }
}
