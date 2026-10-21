package market.promo

import market.Fault
import market.model.PromoCreate
import market.model.PromoResponse
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PromoService(private val repo: PromoRepo) {
    @Transactional
    fun create(body: PromoCreate): PromoResponse {
        PromoInput.check(body)
        return try {
            repo.create(body)
        } catch (ex: DuplicateKeyException) {
            throw Fault("VALIDATION_ERROR", 400, "Промокод уже существует", mapOf(
                "fields" to listOf(mapOf("field" to "code", "message" to "Код уже занят"))
            ))
        }
    }
}
