package market

import com.fasterxml.jackson.databind.ObjectMapper
import market.auth.Jwt
import market.model.ErrorResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import java.math.BigDecimal
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class OrderApiTest : OrderData() {
    @Autowired
    private lateinit var http: TestRestTemplate
    @Autowired
    private lateinit var jwt: Jwt

    @ParameterizedTest
    @CsvSource(
        "empty, items",
        "too_many, items",
        "zero, quantity",
        "too_large, quantity",
        "fraction, quantity",
        "null, quantity",
        "duplicate, items",
        "promo, promo_code",
        "uuid, product_id",
        "missing, items"
    )
    fun `invalid order never reserves stock`(case: String, field: String) {
        val actor = user()
        val id = product()
        val line = mutableMapOf<String, Any?>("product_id" to id, "quantity" to 1)
        val body = mutableMapOf<String, Any?>("items" to listOf(line))
        when (case) {
            "empty" -> body["items"] = emptyList<Any>()
            "too_many" -> body["items"] = List(51) { mapOf("product_id" to UUID.randomUUID(), "quantity" to 1) }
            "zero" -> line["quantity"] = 0
            "too_large" -> line["quantity"] = 1000
            "fraction" -> line["quantity"] = BigDecimal("1.5")
            "null" -> line["quantity"] = null
            "duplicate" -> body["items"] = listOf(line, line)
            "promo" -> body["promo_code"] = "sale"
            "uuid" -> line["product_id"] = "broken"
            "missing" -> body.remove("items")
        }
        invalid(post(actor, "/orders", body), field)
        assertThat(stock(id)).isEqualTo(10)
        assertThat(count("orders")).isZero()
        assertThat(count("order_items")).isZero()
        assertThat(count("user_operations")).isZero()
    }

    @ParameterizedTest
    @CsvSource(
        "null, active",
        "percentage, discount_value",
        "dates, valid_until",
        "discount, discount_value",
        "minimum, min_order_amount"
    )
    fun `invalid promo is not saved`(case: String, field: String) {
        val body = promoBody()
        when (case) {
            "null" -> body["active"] = null
            "percentage" -> body["discount_value"] = BigDecimal("100.01")
            "dates" -> body["valid_until"] = OffsetDateTime.now().minusDays(2).toString()
            "discount" -> body["discount_value"] = BigDecimal("10.001")
            "minimum" -> body["min_order_amount"] = BigDecimal("1.001")
        }
        invalid(post(user("SELLER"), "/promo-codes", body), field)
        assertThat(count("promo_codes")).isZero()
        assertThat(count("orders")).isZero()
        assertThat(count("user_operations")).isZero()
    }

    @Test
    fun `duplicate promo returns field error`() {
        val actor = user("SELLER")
        val headers = headers(actor)
        val body = ObjectMapper().writeValueAsString(promoBody())
        val first = http.postForEntity("/promo-codes", HttpEntity(body, headers), String::class.java)
        assertThat(first.statusCode.value()).isEqualTo(201)
        invalid(post(actor, "/promo-codes", promoBody()), "code")
        assertThat(count("promo_codes")).isEqualTo(1)
    }

    private fun post(actor: Actor, path: String, body: Any): ResponseEntity<ErrorResponse> =
        http.postForEntity(path, HttpEntity(ObjectMapper().writeValueAsString(body), headers(actor)), ErrorResponse::class.java)

    private fun headers(actor: Actor): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        setBearerAuth(jwt.issue(actor, "access", Duration.ofMinutes(5)))
    }

    private fun invalid(result: ResponseEntity<ErrorResponse>, field: String) {
        assertThat(result.statusCode.value()).isEqualTo(400)
        val error = result.body!!
        assertThat(error.errorCode).isEqualTo("VALIDATION_ERROR")
        val fields = error.details!!["fields"] as List<*>
        assertThat(fields).isNotEmpty()
        assertThat(fields.map { (it as Map<*, *>)["field"].toString() })
            .anyMatch { it == field || it.endsWith(".$field") }
    }

    private fun promoBody(): MutableMap<String, Any?> = linkedMapOf(
        "code" to "SAVE_10", "discount_type" to "PERCENTAGE", "discount_value" to 10,
        "min_order_amount" to 0, "max_uses" to 10, "active" to true,
        "valid_from" to OffsetDateTime.now().minusDays(1).toString(),
        "valid_until" to OffsetDateTime.now().plusDays(1).toString()
    )
}
