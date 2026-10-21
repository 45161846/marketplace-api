package market

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
class Caller(private val req: HttpServletRequest) {
    fun get(): Actor = req.getAttribute("actor") as? Actor
        ?: throw Fault("TOKEN_INVALID", 401, "Требуется авторизация")
}
