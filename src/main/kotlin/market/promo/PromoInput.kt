package market.promo

import market.Fault
import market.model.PromoCreate
import market.model.PromoKind
import java.math.BigDecimal

object PromoInput {
    private val max = BigDecimal("9999999999.99")

    fun check(body: PromoCreate) {
        if (!body.code.matches(Regex("^[A-Z0-9_]{4,20}$"))) bad("code", "Неверный формат промокода")
        money(body.discountValue, "discount_value", BigDecimal("0.01"))
        money(body.minOrderAmount, "min_order_amount", BigDecimal.ZERO)
        if (body.discountType == PromoKind.PERCENTAGE && body.discountValue > BigDecimal("100")) {
            bad("discount_value", "Процент должен быть не больше 100")
        }
        if (body.maxUses < 1) bad("max_uses", "Нужно хотя бы одно использование")
        if (body.validUntil.isBefore(body.validFrom)) bad("valid_until", "Дата раньше начала действия")
        if (body.active == null) bad("active", "Поле не может быть null")
    }

    private fun money(value: BigDecimal, field: String, min: BigDecimal) {
        if (value < min || value > max || value.scale() > 2) bad(field, "Недопустимая сумма")
    }

    private fun bad(field: String, message: String): Nothing = throw Fault(
        "VALIDATION_ERROR", 400, "Некорректные данные",
        mapOf("fields" to listOf(mapOf("field" to field, "message" to message)))
    )
}
