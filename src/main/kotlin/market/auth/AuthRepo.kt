package market.auth

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class AuthRepo(private val db: JdbcTemplate) {
    fun create(email: String, hash: String, role: String = "USER"): User {
        val id = UUID.randomUUID()
        db.update("INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, ?)", id, email, hash, role)
        return User(id, email, hash, role)
    }

    fun find(email: String): User? = db.query(
        "SELECT id, email, password_hash, role FROM users WHERE email = ?",
        { row, _ -> User(row.getObject("id", UUID::class.java), row.getString("email"), row.getString("password_hash"), row.getString("role")) },
        email
    ).firstOrNull()

    fun find(id: UUID): User? = db.query(
        "SELECT id, email, password_hash, role FROM users WHERE id = ?",
        { row, _ -> User(row.getObject("id", UUID::class.java), row.getString("email"), row.getString("password_hash"), row.getString("role")) },
        id
    ).firstOrNull()

    fun save(raw: String, id: UUID, exp: Instant) {
        db.update(
            "INSERT INTO refresh_tokens (hash, user_id, expires_at) VALUES (?, ?, ?)",
            hash(raw), id, OffsetDateTime.ofInstant(exp, java.time.ZoneOffset.UTC)
        )
    }

    fun lock(raw: String): Session? = db.query(
        "SELECT user_id, expires_at, revoked_at FROM refresh_tokens WHERE hash = ? FOR UPDATE",
        { row, _ ->
            Session(
                row.getObject("user_id", UUID::class.java),
                row.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                row.getObject("revoked_at", OffsetDateTime::class.java) != null
            )
        },
        hash(raw)
    ).firstOrNull()

    fun revoke(raw: String) {
        db.update("UPDATE refresh_tokens SET revoked_at = now() WHERE hash = ?", hash(raw))
    }

    private fun hash(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

data class User(val id: UUID, val email: String, val hash: String, val role: String)
data class Session(val id: UUID, val exp: Instant, val revoked: Boolean)
