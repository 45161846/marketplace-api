package market

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

@Component
class Caller(private val req: ObjectProvider<HttpServletRequest>) {
    fun get(): Actor = req.getObject().getAttribute("actor") as? Actor
        ?: throw Fault("TOKEN_INVALID", 401, "Требуется авторизация")
}
