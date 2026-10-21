package market

import com.fasterxml.jackson.databind.JsonNode
import market.auth.Jwt
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RoleTest @Autowired constructor(private val http: TestRestTemplate, private val db: JdbcTemplate, private val jwt: Jwt) {
    @AfterEach
    fun clear() {
        db.execute("TRUNCATE users, products, promo_codes CASCADE")
    }

    @ParameterizedTest
    @ValueSource(strings = ["USER", "SELLER", "ADMIN"])
    fun `all roles can read catalog`(role: String) {
        val owner = user("SELLER")
        val id = product(owner)
        val actor = user(role)
        val item = request(actor, HttpMethod.GET, "/products/$id")
        assertThat(item.statusCode.value()).isEqualTo(200)
        assertThat(item.body!!["seller_id"].asText()).isEqualTo(owner.id.toString())
        val page = request(actor, HttpMethod.GET, "/products")
        assertThat(page.statusCode.value()).isEqualTo(200)
        assertThat(page.body!!["items"].size()).isEqualTo(1)
    }

    @Test
    fun `user cannot write catalog even when product is missing`() {
        val actor = user()
        error(request(actor, HttpMethod.POST, "/products", data()), 403, "ACCESS_DENIED")
        val id = UUID.randomUUID()
        error(request(actor, HttpMethod.PUT, "/products/$id", data()), 403, "ACCESS_DENIED")
        error(request(actor, HttpMethod.DELETE, "/products/$id"), 403, "ACCESS_DENIED")
        assertThat(count()).isZero()
    }

    @Test
    fun `seller creates own products and cannot impersonate owner`() {
        val actor = user("SELLER")
        val other = user("SELLER")
        val own = request(actor, HttpMethod.POST, "/products", data())
        assertThat(own.statusCode.value()).isEqualTo(201)
        assertThat(own.body!!["seller_id"].asText()).isEqualTo(actor.id.toString())
        val explicit = request(actor, HttpMethod.POST, "/products", data() + ("seller_id" to actor.id))
        assertThat(explicit.statusCode.value()).isEqualTo(201)
        error(request(actor, HttpMethod.POST, "/products", data() + ("seller_id" to other.id)), 403, "ACCESS_DENIED")
        assertThat(count()).isEqualTo(2)
    }

    @Test
    fun `seller changes own products and cannot change another seller products`() {
        val actor = user("SELLER")
        val own = product(actor)
        val other = product(user("SELLER"))
        val body = data() + ("name" to "Pen")
        val edit = request(actor, HttpMethod.PUT, "/products/$own", body)
        assertThat(edit.statusCode.value()).isEqualTo(200)
        assertThat(edit.body!!["seller_id"].asText()).isEqualTo(actor.id.toString())
        assertThat(request(actor, HttpMethod.DELETE, "/products/$own").statusCode.value()).isEqualTo(204)
        error(request(actor, HttpMethod.PUT, "/products/$other", body), 403, "ACCESS_DENIED")
        error(request(actor, HttpMethod.DELETE, "/products/$other"), 403, "ACCESS_DENIED")
        assertThat(db.queryForObject("SELECT status FROM products WHERE id = ?", String::class.java, own)).isEqualTo("ARCHIVED")
        assertThat(db.queryForObject("SELECT name FROM products WHERE id = ?", String::class.java, other)).isEqualTo("Book")
        assertThat(db.queryForObject("SELECT status FROM products WHERE id = ?", String::class.java, other)).isEqualTo("ACTIVE")
    }

    @Test
    fun `admin selects owners and manages another seller products`() {
        val actor = user("ADMIN")
        val owner = user("SELLER")
        val own = request(actor, HttpMethod.POST, "/products", data() + ("seller_id" to null))
        assertThat(own.statusCode.value()).isEqualTo(201)
        assertThat(own.body!!["seller_id"].asText()).isEqualTo(actor.id.toString())
        val other = request(actor, HttpMethod.POST, "/products", data() + ("seller_id" to owner.id))
        assertThat(other.statusCode.value()).isEqualTo(201)
        val id = other.body!!["id"].asText()
        val edit = request(actor, HttpMethod.PUT, "/products/$id", data() + ("name" to "Pen"))
        assertThat(edit.statusCode.value()).isEqualTo(200)
        assertThat(edit.body!!["seller_id"].asText()).isEqualTo(owner.id.toString())
        assertThat(request(actor, HttpMethod.DELETE, "/products/$id").statusCode.value()).isEqualTo(204)
    }

    @Test
    fun `admin cannot assign missing user or buyer as owner`() {
        val actor = user("ADMIN")
        for (owner in listOf(user().id, UUID.randomUUID())) {
            val result = request(actor, HttpMethod.POST, "/products", data() + ("seller_id" to owner))
            error(result, 400, "VALIDATION_ERROR")
            assertThat(result.body!!["details"]["fields"].map { it["field"].asText() }).contains("seller_id")
        }
        assertThat(count()).isZero()
    }

    @Test
    fun `update cannot transfer ownership`() {
        val actor = user("ADMIN")
        val owner = user("SELLER")
        val id = product(owner)
        error(request(actor, HttpMethod.PUT, "/products/$id", data() + ("seller_id" to actor.id)), 400, "VALIDATION_ERROR")
        assertThat(db.queryForObject("SELECT seller_id FROM products WHERE id = ?", UUID::class.java, id)).isEqualTo(owner.id)
    }

    @ParameterizedTest
    @ValueSource(strings = ["USER", "ADMIN"])
    fun `buyer and admin can create read update and cancel orders`(role: String) {
        val actor = user(role)
        val id = product(user("SELLER"))
        val create = request(actor, HttpMethod.POST, "/orders", cart(id))
        assertThat(create.statusCode.value()).isEqualTo(201)
        val path = "/orders/${create.body!!["id"].asText()}"
        assertThat(request(actor, HttpMethod.GET, path).statusCode.value()).isEqualTo(200)
        assertThat(request(actor, HttpMethod.PUT, path, cart(id, 2)).statusCode.value()).isEqualTo(200)
        assertThat(request(actor, HttpMethod.POST, "$path/cancel").statusCode.value()).isEqualTo(200)
        assertThat(db.queryForObject("SELECT stock FROM products WHERE id = ?", Int::class.java, id)).isEqualTo(10)
    }

    @Test
    fun `seller cannot use order endpoints`() {
        val actor = user("SELLER")
        val buyer = user()
        val id = product(actor)
        val create = request(buyer, HttpMethod.POST, "/orders", cart(id))
        val path = "/orders/${create.body!!["id"].asText()}"
        error(request(actor, HttpMethod.POST, "/orders", cart(id)), 403, "ACCESS_DENIED")
        error(request(actor, HttpMethod.GET, path), 403, "ACCESS_DENIED")
        error(request(actor, HttpMethod.PUT, path, cart(id)), 403, "ACCESS_DENIED")
        error(request(actor, HttpMethod.POST, "$path/cancel"), 403, "ACCESS_DENIED")
    }

    @Test
    fun `foreign order is forbidden for user and accessible to admin`() {
        val buyer = user()
        val other = user()
        val admin = user("ADMIN")
        val id = product(user("SELLER"))
        val create = request(buyer, HttpMethod.POST, "/orders", cart(id))
        val path = "/orders/${create.body!!["id"].asText()}"
        error(request(other, HttpMethod.GET, path), 403, "ORDER_OWNERSHIP_VIOLATION")
        error(request(other, HttpMethod.PUT, path, cart(id)), 403, "ORDER_OWNERSHIP_VIOLATION")
        error(request(other, HttpMethod.POST, "$path/cancel"), 403, "ORDER_OWNERSHIP_VIOLATION")
        assertThat(request(admin, HttpMethod.GET, path).statusCode.value()).isEqualTo(200)
        assertThat(request(admin, HttpMethod.PUT, path, cart(id, 2)).statusCode.value()).isEqualTo(200)
        assertThat(request(admin, HttpMethod.POST, "$path/cancel").statusCode.value()).isEqualTo(200)
    }

    @ParameterizedTest
    @ValueSource(strings = ["USER", "SELLER", "ADMIN"])
    fun `only sellers and admins create promo codes`(role: String) {
        val body = mapOf(
            "code" to "SAVE_10", "discount_type" to "PERCENTAGE", "discount_value" to 10,
            "min_order_amount" to 0, "max_uses" to 10,
            "valid_from" to OffsetDateTime.now().minusDays(1).toString(),
            "valid_until" to OffsetDateTime.now().plusDays(1).toString()
        )
        val result = request(user(role), HttpMethod.POST, "/promo-codes", body)
        if (role == "USER") error(result, 403, "ACCESS_DENIED")
        else assertThat(result.statusCode.value()).isEqualTo(201)
    }

    @Test
    fun `only admin advances order status`() {
        val buyer = user()
        val seller = user("SELLER")
        val id = product(seller)
        val create = request(buyer, HttpMethod.POST, "/orders", cart(id))
        val path = "/orders/${create.body!!["id"].asText()}/status"
        val body = mapOf("status" to "PAYMENT_PENDING")
        error(request(buyer, HttpMethod.POST, path, body), 403, "ACCESS_DENIED")
        error(request(seller, HttpMethod.POST, path, body), 403, "ACCESS_DENIED")
        assertThat(request(user("ADMIN"), HttpMethod.POST, path, body).statusCode.value()).isEqualTo(200)
    }

    private fun request(actor: Actor, method: HttpMethod, path: String, body: Any? = null): ResponseEntity<JsonNode> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.setBearerAuth(jwt.issue(actor, "access", Duration.ofMinutes(15)))
        return http.exchange(path, method, HttpEntity(body, headers), JsonNode::class.java)
    }

    private fun error(result: ResponseEntity<JsonNode>, status: Int, code: String) {
        assertThat(result.statusCode.value()).isEqualTo(status)
        assertThat(result.body!!["error_code"].asText()).isEqualTo(code)
    }

    private fun user(role: String = "USER"): Actor {
        val id = UUID.randomUUID()
        db.update("INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, 'hash', ?)", id, "$id@test.local", role)
        return Actor(id, role)
    }

    private fun product(owner: Actor): UUID {
        val id = UUID.randomUUID()
        db.update("INSERT INTO products (id, name, price, stock, category, status, seller_id) VALUES (?, 'Book', 10, 10, 'Books', 'ACTIVE', ?)", id, owner.id)
        return id
    }

    private fun data() = mapOf("name" to "Book", "price" to BigDecimal("10.00"), "stock" to 10, "category" to "Books", "status" to "ACTIVE")

    private fun cart(id: UUID, qty: Int = 1) = mapOf("items" to listOf(mapOf("product_id" to id, "quantity" to qty)))

    private fun count(): Long = db.queryForObject("SELECT count(*) FROM products", Long::class.java)!!
}
