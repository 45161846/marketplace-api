package market.auth

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletResponse
import market.Fault
import market.model.ErrorResponse
import org.springframework.stereotype.Component

@Component
class AuthErrors(private val json: ObjectMapper) {
    fun write(res: HttpServletResponse, fault: Fault) {
        res.status = fault.status
        res.contentType = "application/json"
        res.characterEncoding = "UTF-8"
        json.writeValue(res.outputStream, ErrorResponse(fault.code, fault.message.orEmpty(), fault.details))
    }
}
