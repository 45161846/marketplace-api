package market.auth

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import market.Actor
import market.Fault
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

@Component
class Jwt(@Value("\${jwt.secret}") secret: String) {
    private val key = secret.toByteArray(Charsets.UTF_8)
    private val roles = setOf("USER", "SELLER", "ADMIN")

    init {
        require(key.size >= 32) { "JWT_SECRET должен содержать не менее 32 байт" }
    }

    fun issue(actor: Actor, type: String, ttl: Duration): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer("marketplace")
            .subject(actor.id.toString())
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ttl)))
            .claim("type", type)
            .claim("role", actor.role)
            .build()
        val token = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims)
        token.sign(MACSigner(key))
        return token.serialize()
    }

    fun read(raw: String, type: String): Claim {
        val claims = try {
            val token = SignedJWT.parse(raw)
            require(token.header.algorithm == JWSAlgorithm.HS256)
            require(token.header.type == JOSEObjectType.JWT)
            require(token.verify(MACVerifier(key)))
            token.jwtClaimsSet
        } catch (_: Exception) {
            throw invalid(type)
        }
        val claim = try {
            require(claims.issuer == "marketplace")
            require(claims.getStringClaim("type") == type)
            val role = claims.getStringClaim("role")
            require(role in roles)
            val id = UUID.fromString(claims.subject)
            val jti = UUID.fromString(claims.jwtid)
            val exp = claims.expirationTime.toInstant()
            val issued = claims.issueTime.toInstant()
            val now = Instant.now()
            require(!issued.isAfter(now))
            require(claims.notBeforeTime?.toInstant()?.isAfter(now) != true)
            Claim(Actor(id, role), jti, exp)
        } catch (_: Exception) {
            throw invalid(type)
        }
        if (!claim.exp.isAfter(Instant.now())) {
            if (type == "access") throw Fault("TOKEN_EXPIRED", 401, "Токен истёк")
            throw invalid(type)
        }
        return claim
    }

    private fun invalid(type: String) = if (type == "refresh") {
        Fault("REFRESH_TOKEN_INVALID", 401, "Недействительный токен обновления")
    } else {
        Fault("TOKEN_INVALID", 401, "Недействительный токен")
    }
}

data class Claim(val actor: Actor, val jti: UUID, val exp: Instant)
