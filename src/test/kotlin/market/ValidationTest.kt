package market

import market.auth.Jwt

import com.fasterxml.jackson.databind.ObjectMapper
import market.model.ErrorResponse
import market.model.ProductResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ValidationTest @Autowired constructor(private val http: TestRestTemplate, private val db: JdbcTemplate, private val jwt: Jwt) {
    private lateinit var auth: ClientHttpRequestInterceptor

    @BeforeEach
    fun login() {
        val id = UUID.randomUUID()
        db.update("INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, 'hash', 'ADMIN')", id, "$id@test.local")
        val token = jwt.issue(Actor(id, "ADMIN"), "access", Duration.ofMinutes(5))
        auth = ClientHttpRequestInterceptor { req, body, chain ->
            req.headers.setBearerAuth(token)
            chain.execute(req, body)
        }
        http.restTemplate.interceptors.add(auth)
    }

    @AfterEach
    fun clear() {
        http.restTemplate.interceptors.remove(auth)
        db.execute("TRUNCATE users, products CASCADE")
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("bodies")
    fun `invalid product body`(case: String, body: String, field: String) {
        val result = http.postForEntity("/products", json(body), ErrorResponse::class.java)
        invalid(result, field)
    }

    @ParameterizedTest
    @ValueSource(strings = ["{", "{\"name\":", "{\"name\":\"Book\",}"])
    fun `malformed json returns validation error`(body: String) {
        val result = http.postForEntity("/products", json(body), ErrorResponse::class.java)
        invalid(result, "body")
    }

    @ParameterizedTest
    @CsvSource(
        "/products/not-a-uuid, id",
        "/products?page=-1, page",
        "/products?size=0, size",
        "/products?size=101, size"
    )
    fun `invalid path or query`(path: String, field: String) {
        invalid(http.getForEntity(path, ErrorResponse::class.java), field)
    }

    @Test
    fun `boundary values are accepted`() {
        val body = base().apply {
            this["name"] = "N".repeat(255)
            this["description"] = "D".repeat(4000)
            this["price"] = BigDecimal("0.01")
            this["stock"] = 0
            this["category"] = "C".repeat(100)
        }
        val result = http.postForEntity("/products", json(ObjectMapper().writeValueAsString(body)), ProductResponse::class.java)
        assertThat(result.statusCode.value()).isEqualTo(201)
        val item = result.body!!
        assertThat(item.name).hasSize(255)
        assertThat(item.description).hasSize(4000)
        assertThat(item.price).isEqualByComparingTo("0.01")
        assertThat(item.stock).isZero()
        assertThat(item.category).hasSize(100)
        assertThat(db.queryForObject("SELECT count(*) FROM products", Long::class.java)).isEqualTo(1L)
    }

    private fun invalid(result: ResponseEntity<ErrorResponse>, field: String) {
        assertThat(result.statusCode.value()).isEqualTo(400)
        val error = result.body!!
        assertThat(error.errorCode).isEqualTo("VALIDATION_ERROR")
        assertThat(error.message).isNotBlank()
        val fields = error.details?.get("fields") as? List<*>
        assertThat(fields).isNotNull().isNotEmpty()
        assertThat(fields!!.map { (it as Map<*, *>)["field"] }).contains(field)
        assertThat(fields.map { (it as Map<*, *>)["message"] }).allSatisfy {
            assertThat(it as String).isNotBlank()
        }
        assertThat(db.queryForObject("SELECT count(*) FROM products", Long::class.java)).isEqualTo(0L)
    }

    private fun json(body: String): HttpEntity<String> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        return HttpEntity(body, headers)
    }

    companion object {
        private fun base(): MutableMap<String, Any?> = linkedMapOf(
            "name" to "Book",
            "description" to "Paperback",
            "price" to BigDecimal("24.50"),
            "stock" to 10,
            "category" to "Books",
            "status" to "ACTIVE"
        )

        @JvmStatic
        fun bodies(): List<Arguments> {
            val mapper = ObjectMapper()
            val cases = mutableListOf<Arguments>()
            fun add(case: String, field: String, value: Any?) {
                val body = base().apply { this[field] = value }
                cases += Arguments.of(case, mapper.writeValueAsString(body), field)
            }
            add("empty name", "name", "")
            add("long name", "name", "N".repeat(256))
            add("long description", "description", "D".repeat(4001))
            add("zero price", "price", BigDecimal.ZERO)
            add("negative price", "price", BigDecimal("-1.00"))
            add("fractional cent", "price", BigDecimal("0.001"))
            add("fractional cent above minimum", "price", BigDecimal("1.001"))
            add("price as string", "price", "24.50")
            add("negative stock", "stock", -1)
            add("fractional stock", "stock", BigDecimal("1.5"))
            add("empty category", "category", "")
            add("long category", "category", "C".repeat(101))
            add("unknown status", "status", "DELETED")
            add("unknown field", "extra", "value")
            for (field in listOf("name", "price", "stock", "category", "status")) {
                add("null $field", field, null)
                val body = base().apply { remove(field) }
                cases += Arguments.of("missing $field", mapper.writeValueAsString(body), field)
            }
            return cases
        }
    }
}
