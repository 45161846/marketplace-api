package market.promo

import market.Caller
import market.Fault
import market.api.PromosApi
import market.model.PromoCreate
import market.model.PromoResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class Promos(private val service: PromoService, private val caller: Caller) : PromosApi {
    override fun createPromo(body: PromoCreate): ResponseEntity<PromoResponse> {
        if (caller.get().role !in setOf("SELLER", "ADMIN")) throw Fault("ACCESS_DENIED", 403, "Недостаточно прав")
        PromoInput.check(body)
        return ResponseEntity.status(201).body(service.create(body))
    }
}
