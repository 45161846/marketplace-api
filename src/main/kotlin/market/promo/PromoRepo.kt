package market.promo

import market.model.PromoCreate
import market.model.PromoKind
import market.model.PromoResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class PromoRepo(private val db: JdbcTemplate) {
    private val row = RowMapper { rs, _ ->
        PromoResponse(
            id = rs.getObject("id", UUID::class.java),
            code = rs.getString("code"),
            discountType = PromoKind.valueOf(rs.getString("discount_type")),
            discountValue = rs.getBigDecimal("discount_value"),
            minOrderAmount = rs.getBigDecimal("min_order_amount"),
            maxUses = rs.getInt("max_uses"),
            currentUses = rs.getInt("current_uses"),
            validFrom = rs.getObject("valid_from", OffsetDateTime::class.java),
            validUntil = rs.getObject("valid_until", OffsetDateTime::class.java),
            active = rs.getBoolean("active")
        )
    }

    fun create(body: PromoCreate): PromoResponse = db.queryForObject(
        """
        INSERT INTO promo_codes
            (code, discount_type, discount_value, min_order_amount, max_uses, valid_from, valid_until, active)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING *
        """.trimIndent(),
        row, body.code, body.discountType.name, body.discountValue, body.minOrderAmount,
        body.maxUses, body.validFrom, body.validUntil, body.active
    )!!

    fun byCode(code: String): PromoResponse? = db.query(
        "SELECT * FROM promo_codes WHERE code = ? FOR UPDATE", row, code
    ).singleOrNull()

    fun find(id: UUID): PromoResponse? = db.query(
        "SELECT * FROM promo_codes WHERE id = ? FOR UPDATE", row, id
    ).singleOrNull()

    fun use(id: UUID, delta: Int) {
        db.update("UPDATE promo_codes SET current_uses = current_uses + ? WHERE id = ?", delta, id)
    }
}
