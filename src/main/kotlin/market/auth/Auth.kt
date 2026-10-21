package market.auth

import market.Actor
import market.Fault
import market.api.AuthApi
import market.model.Login
import market.model.Refresh
import market.model.Register
import market.model.Tokens
import market.model.UserRole
import market.model.UserView
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.Locale

@RestController
class Auth(private val repo: AuthRepo, private val passwords: Passwords, private val jwt: Jwt) : AuthApi {
    @Transactional
    override fun register(body: Register): ResponseEntity<UserView> {
        val user = try {
            repo.create(body.email.lowercase(Locale.ROOT), passwords.hash(body.password))
        } catch (_: DuplicateKeyException) {
            throw Fault(
                "VALIDATION_ERROR", 400, "Email уже зарегистрирован",
                mapOf("fields" to listOf(mapOf("field" to "email", "message" to "Email уже зарегистрирован")))
            )
        }
        return ResponseEntity.status(201).body(UserView(user.id, user.email, UserRole.valueOf(user.role)))
    }

    @Transactional
    override fun login(body: Login): ResponseEntity<Tokens> {
        val user = repo.find(body.email.lowercase(Locale.ROOT))
        if (!passwords.matches(body.password, user?.hash)) throw Fault("AUTH_FAILED", 401, "Неверный email или пароль")
        return ResponseEntity.ok(tokens(user!!))
    }

    @Transactional
    override fun refresh(body: Refresh): ResponseEntity<Tokens> {
        val claim = jwt.read(body.refreshToken, "refresh")
        val session = repo.lock(body.refreshToken) ?: throw invalid()
        if (session.revoked || session.id != claim.actor.id || !session.exp.isAfter(Instant.now())) throw invalid()
        val user = repo.find(session.id) ?: throw invalid()
        repo.revoke(body.refreshToken)
        return ResponseEntity.ok(tokens(user))
    }

    private fun tokens(user: User): Tokens {
        val actor = Actor(user.id, user.role)
        val access = jwt.issue(actor, "access", Duration.ofMinutes(15))
        val refresh = jwt.issue(actor, "refresh", Duration.ofDays(7))
        repo.save(refresh, user.id, jwt.read(refresh, "refresh").exp)
        return Tokens(access, refresh, "Bearer", 900)
    }

    private fun invalid() = Fault("REFRESH_TOKEN_INVALID", 401, "Недействительный токен обновления")
}
