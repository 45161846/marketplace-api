package market.order

import market.Caller
import market.api.OrdersApi
import market.model.OrderCreate
import market.model.OrderResponse
import market.model.OrderState
import market.model.OrderUpdate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class Orders(private val service: OrderService, private val caller: Caller) : OrdersApi {
    override fun createOrder(body: OrderCreate): ResponseEntity<OrderResponse> {
        OrderInput.check(body.items, body.promoCode)
        return ResponseEntity.status(201).body(service.create(caller.get(), body))
    }

    override fun getOrder(id: UUID): ResponseEntity<OrderResponse> = ResponseEntity.ok(service.get(caller.get(), id))

    override fun updateOrder(id: UUID, body: OrderUpdate): ResponseEntity<OrderResponse> {
        OrderInput.check(body.items)
        return ResponseEntity.ok(service.update(caller.get(), id, body))
    }

    override fun cancelOrder(id: UUID): ResponseEntity<OrderResponse> = ResponseEntity.ok(service.cancel(caller.get(), id))

    override fun changeStatus(id: UUID, body: OrderState): ResponseEntity<OrderResponse> =
        ResponseEntity.ok(service.status(caller.get(), id, body))
}
