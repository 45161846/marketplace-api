package market

import market.model.OrderResponse
import market.model.OrderLine
import market.model.OrderStatus
import market.model.OrderUpdate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OrderRaceTest : OrderData() {
    @Test
    fun `concurrent creates leave one active order per user`() {
        val user = user()
        val id = product()
        val results = race({ orders.create(user, cart(id)) }, { orders.create(user, cart(id)) })
        assertThat(results).containsExactlyInAnyOrder("OK", "ORDER_LIMIT_EXCEEDED")
        assertThat(count("orders")).isEqualTo(1)
        assertThat(count("order_items")).isEqualTo(1)
        assertThat(count("user_operations")).isEqualTo(1)
        assertThat(stock(id)).isEqualTo(9)
    }

    @Test
    fun `last item can be reserved by only one user`() {
        val first = user()
        val second = user()
        val id = product(stock = 1)
        val results = race({ orders.create(first, cart(id)) }, { orders.create(second, cart(id)) })
        assertThat(results).containsExactlyInAnyOrder("OK", "INSUFFICIENT_STOCK")
        assertThat(stock(id)).isZero()
        assertThat(count("orders")).isEqualTo(1)
        assertThat(count("user_operations")).isEqualTo(1)
    }

    @Test
    fun `last promo use belongs to only one order`() {
        val first = user()
        val second = user()
        val one = product()
        val two = product()
        val promo = promo(max = 1)
        val results = race(
            { orders.create(first, cart(one, code = promo.code)) },
            { orders.create(second, cart(two, code = promo.code)) }
        )
        assertThat(results).containsExactlyInAnyOrder("OK", "PROMO_CODE_INVALID")
        assertThat(uses(promo.id)).isEqualTo(1)
        assertThat(stock(one) + stock(two)).isEqualTo(19)
        assertThat(count("orders")).isEqualTo(1)
        assertThat(count("user_operations")).isEqualTo(1)
    }

    @Test
    fun `update racing with cancel returns all stock exactly once`() {
        val user = user()
        val id = product()
        val promo = promo()
        val order = orders.create(user, cart(id, 2, promo.code))
        val results = race(
            { orders.update(user, order.id, OrderUpdate(listOf(OrderLine(id, 3)))) },
            { orders.cancel(user, order.id) }
        )
        assertThat(results).contains("OK").allMatch { it == "OK" || it == "INVALID_STATE_TRANSITION" }
        assertThat(orders.get(user, order.id).status).isEqualTo(OrderStatus.CANCELED)
        assertThat(stock(id)).isEqualTo(10)
        assertThat(uses(promo.id)).isZero()
    }

    private fun race(first: () -> OrderResponse, second: () -> OrderResponse): List<String> {
        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val jobs = listOf(first, second).map { action ->
                pool.submit<String> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    try {
                        action()
                        "OK"
                    } catch (error: Fault) {
                        error.code
                    }
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return jobs.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
