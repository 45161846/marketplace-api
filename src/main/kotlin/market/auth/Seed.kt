package market.auth

import jakarta.validation.Validator
import market.model.Register
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

@Component
@Profile("seed")
class Seed(
    private val repo: AuthRepo,
    private val passwords: Passwords,
    private val env: Environment,
    private val validator: Validator
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        for (role in listOf("SELLER", "ADMIN")) {
            val email = env.getRequiredProperty("${role}_EMAIL").lowercase(Locale.ROOT)
            val password = env.getRequiredProperty("${role}_PASSWORD")
            require(validator.validate(Register(email, password)).isEmpty()) { "Некорректные данные $role" }
            val user = repo.find(email)
            if (user == null) {
                repo.create(email, passwords.hash(password), role)
            } else {
                require(user.role == role) { "Email $email занят другой ролью" }
            }
        }
    }
}
