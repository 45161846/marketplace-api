package market.auth

import market.Fault
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

@Component
class Passwords {
    private val encoder = BCryptPasswordEncoder()
    private val dummy = encoder.encode(java.util.UUID.randomUUID().toString())

    fun hash(password: String): String {
        check(password)
        return encoder.encode(password)
    }

    fun matches(password: String, hash: String?): Boolean {
        check(password)
        val matched = encoder.matches(password, hash ?: dummy)
        return hash != null && matched
    }

    private fun check(password: String) {
        if (password.toByteArray(Charsets.UTF_8).size > 72) {
            throw Fault(
                "VALIDATION_ERROR", 400, "Некорректный пароль",
                mapOf("fields" to listOf(mapOf("field" to "password", "message" to "Не более 72 байт UTF-8")))
            )
        }
    }
}
