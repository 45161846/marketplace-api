package market

import com.fasterxml.jackson.databind.JsonNode
import market.auth.Jwt
import market.model.Login
import market.model.Refresh
import market.model.Register
import market.model.Tokens
import market.model.UserRole
import market.model.UserView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthTest @Autowired constructor(private val http: TestRestTemplate, private val db: JdbcTemplate, private val jwt: Jwt) {
    private val email = "user@example.com"
    private val password = "test-pass-123"

    @AfterEach
    fun clear() {
        db.execute("TRUNCATE users CASCADE")
    }

    @Test
    fun `registration stores a hash and login returns both tokens`() {
        val user = register("USER@example.com")
        assertThat(user.email).isEqualTo(email)
        assertThat(user.role).isEqualTo(UserRole.USER)
        val hash = db.queryForObject("SELECT password_hash FROM users WHERE id = ?", String::class.java, user.id)!!
        assertThat(hash).isNotEqualTo(password)
        assertThat(BCryptPasswordEncoder().matches(password, hash)).isTrue()
        val tokens = login()
        assertThat(tokens.tokenType).isEqualTo("Bearer")
        assertThat(tokens.expiresIn).isEqualTo(900)
        assertThat(jwt.read(tokens.accessToken, "access").actor).isEqualTo(Actor(user.id, "USER"))
        assertThat(jwt.read(tokens.refreshToken, "refresh").actor.id).isEqualTo(user.id)
        val stored = db.queryForObject("SELECT hash FROM refresh_tokens WHERE user_id = ?", String::class.java, user.id)
        assertThat(stored).hasSize(64).isNotEqualTo(tokens.refreshToken)
        assertThat(get(tokens.accessToken).statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `registration rejects duplicate email and role injection`() {
        register()
        val duplicate = http.postForEntity("/auth/register", Register("USER@example.com", password), JsonNode::class.java)
        assertThat(duplicate.statusCode.value()).isEqualTo(400)
        assertThat(duplicate.body!!.get("error_code").asText()).isEqualTo("VALIDATION_ERROR")
        val body = mapOf("email" to "admin@example.com", "password" to password, "role" to "ADMIN")
        val elevated = http.postForEntity("/auth/register", body, JsonNode::class.java)
        assertThat(elevated.statusCode.value()).isEqualTo(400)
        assertThat(db.queryForObject("SELECT count(*) FROM users", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `wrong password and missing user return auth failure`() {
        register()
        for (body in listOf(Login(email, "wrong-pass"), Login("missing@example.com", password))) {
            val result = http.postForEntity("/auth/login", body, JsonNode::class.java)
            assertThat(result.statusCode.value()).isEqualTo(401)
            assertThat(result.body!!.get("error_code").asText()).isEqualTo("AUTH_FAILED")
        }
    }

    @Test
    fun `password limit counts bytes`() {
        val valid = http.postForEntity("/auth/register", Register(email, "я".repeat(36)), UserView::class.java)
        assertThat(valid.statusCode.value()).isEqualTo(201)
        val result = http.postForEntity("/auth/register", Register("other@example.com", "я".repeat(37)), JsonNode::class.java)
        assertThat(result.statusCode.value()).isEqualTo(400)
        assertThat(result.body!!.get("details").get("fields").first().get("field").asText()).isEqualTo("password")
    }

    @Test
    fun `access requires a valid token of the right type`() {
        val user = register()
        val tokens = login()
        for (raw in listOf(null, "broken", tokens.refreshToken)) {
            val result = get(raw)
            assertThat(result.statusCode.value()).isEqualTo(401)
            assertThat(result.body!!.get("error_code").asText()).isEqualTo("TOKEN_INVALID")
        }
        val expired = jwt.issue(Actor(user.id, "USER"), "access", Duration.ofSeconds(-1))
        val result = get(expired)
        assertThat(result.statusCode.value()).isEqualTo(401)
        assertThat(result.body!!.get("error_code").asText()).isEqualTo("TOKEN_EXPIRED")
    }

    @Test
    fun `refresh rotates tokens and rejects reuse`() {
        val user = register()
        val tokens = login()
        val result = http.postForEntity("/auth/refresh", Refresh(tokens.refreshToken), Tokens::class.java)
        assertThat(result.statusCode.value()).isEqualTo(200)
        assertThat(result.body!!.refreshToken).isNotEqualTo(tokens.refreshToken)
        assertThat(get(result.body!!.accessToken).statusCode.value()).isEqualTo(200)
        assertThat(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Int::class.java)).isEqualTo(1)
        val expired = jwt.issue(Actor(user.id, "USER"), "refresh", Duration.ofSeconds(-1))
        for (raw in listOf(tokens.refreshToken, tokens.accessToken, expired, "broken")) {
            val denied = http.postForEntity("/auth/refresh", Refresh(raw), JsonNode::class.java)
            assertThat(denied.statusCode.value()).isEqualTo(401)
            assertThat(denied.body!!.get("error_code").asText()).isEqualTo("REFRESH_TOKEN_INVALID")
        }
    }

    @Test
    fun `parallel refresh accepts the old token once`() {
        register()
        val token = login().refreshToken
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map {
                pool.submit(Callable {
                    gate.await(10, TimeUnit.SECONDS)
                    http.postForEntity("/auth/refresh", Refresh(token), JsonNode::class.java).statusCode.value()
                })
            }
            gate.countDown()
            assertThat(jobs.map { it.get(15, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(200, 401)
            assertThat(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Int::class.java)).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun register(mail: String = email): UserView {
        val result = http.postForEntity("/auth/register", Register(mail, password), UserView::class.java)
        assertThat(result.statusCode.value()).isEqualTo(201)
        return result.body!!
    }

    private fun login(): Tokens {
        val result = http.postForEntity("/auth/login", Login(email, password), Tokens::class.java)
        assertThat(result.statusCode.value()).isEqualTo(200)
        return result.body!!
    }

    private fun get(raw: String?) = http.exchange(
        "/products", HttpMethod.GET,
        HttpEntity<Unit>(HttpHeaders().apply { if (raw != null) setBearerAuth(raw) }), JsonNode::class.java
    )
}
