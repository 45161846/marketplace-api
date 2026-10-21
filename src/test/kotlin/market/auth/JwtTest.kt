package market.auth

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import market.Actor
import market.Fault
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

class JwtTest {
    private val secret = "x".repeat(64)
    private val jwt = Jwt(secret)
    private val actor = Actor(UUID.randomUUID(), "USER")

    @Test
    fun `token carries actor and expiry`() {
        val raw = jwt.issue(actor, "access", Duration.ofMinutes(15))
        val claim = jwt.read(raw, "access")
        assertThat(claim.actor).isEqualTo(actor)
        assertThat(claim.exp).isBetween(Instant.now().plusSeconds(895), Instant.now().plusSeconds(901))
        val claims = SignedJWT.parse(raw).jwtClaimsSet
        assertThat(claims.issuer).isEqualTo("marketplace")
        assertThat(claims.getStringClaim("type")).isEqualTo("access")
        assertThat(claims.getStringClaim("role")).isEqualTo("USER")
    }

    @Test
    fun `expired tokens use the right error`() {
        fault(jwt.issue(actor, "access", Duration.ofSeconds(-1)), "access", "TOKEN_EXPIRED")
        fault(jwt.issue(actor, "refresh", Duration.ofSeconds(-1)), "refresh", "REFRESH_TOKEN_INVALID")
    }

    @Test
    fun `token types are not interchangeable`() {
        fault(jwt.issue(actor, "refresh", Duration.ofDays(7)), "access", "TOKEN_INVALID")
        fault(jwt.issue(actor, "access", Duration.ofMinutes(15)), "refresh", "REFRESH_TOKEN_INVALID")
    }

    @Test
    fun `invalid signatures and algorithms fail`() {
        fault(signed(key = "z".repeat(64)), "access", "TOKEN_INVALID")
        fault(signed(alg = JWSAlgorithm.HS384), "access", "TOKEN_INVALID")
        fault("not-a-token", "access", "TOKEN_INVALID")
    }

    @Test
    fun `issuer role and time are verified`() {
        fault(signed(issuer = "other"), "access", "TOKEN_INVALID")
        fault(signed(role = "ROOT"), "access", "TOKEN_INVALID")
        fault(signed(issued = Instant.now().plusSeconds(60)), "access", "TOKEN_INVALID")
    }

    @Test
    fun `secret must have 32 bytes`() {
        assertThatThrownBy { Jwt("short") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun fault(raw: String, type: String, code: String) {
        assertThatThrownBy { jwt.read(raw, type) }.isInstanceOfSatisfying(Fault::class.java) {
            assertThat(it.code).isEqualTo(code)
            assertThat(it.status).isEqualTo(401)
        }
    }

    private fun signed(
        key: String = secret,
        alg: JWSAlgorithm = JWSAlgorithm.HS256,
        issuer: String = "marketplace",
        role: String = "USER",
        issued: Instant = Instant.now()
    ): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer).subject(actor.id.toString()).jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(issued)).expirationTime(Date.from(Instant.now().plusSeconds(900)))
            .claim("type", "access").claim("role", role).build()
        val token = SignedJWT(JWSHeader.Builder(alg).type(JOSEObjectType.JWT).build(), claims)
        token.sign(MACSigner(key))
        return token.serialize()
    }
}
