package market.promo

import market.api.PromosApi
import market.model.PromoCreate
import market.model.PromoResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class Promos(private val service: PromoService) : PromosApi {
    override fun createPromo(body: PromoCreate): ResponseEntity<PromoResponse> {
        PromoInput.check(body)
        return ResponseEntity.status(201).body(service.create(body))
    }
}
