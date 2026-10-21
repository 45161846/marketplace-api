package market

import com.fasterxml.jackson.databind.JsonMappingException
import jakarta.validation.ConstraintViolationException
import market.model.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class Errors {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(Fault::class)
    fun fault(ex: Fault): ResponseEntity<ErrorResponse> = ResponseEntity.status(ex.status)
        .body(ErrorResponse(ex.code, ex.message.orEmpty(), ex.details))

    @ExceptionHandler(BindException::class)
    fun binding(ex: BindException) = invalid(ex.fieldErrors.map {
        field(it.field, it.defaultMessage ?: "Некорректное значение")
    })

    @ExceptionHandler(ConstraintViolationException::class)
    fun constraint(ex: ConstraintViolationException) = invalid(ex.constraintViolations.map {
        field(it.propertyPath.toString().substringAfter('.'), it.message)
    })

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun method(ex: HandlerMethodValidationException) = invalid(ex.allValidationResults.flatMap { result ->
        result.resolvableErrors.map { field(result.methodParameter.parameterName ?: "request", it.defaultMessage.orEmpty()) }
    })

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun argument(ex: MethodArgumentTypeMismatchException) = invalid(listOf(field(ex.name, "Неверный тип или формат")))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun json(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        val cause = generateSequence(ex.cause) { it.cause }.filterIsInstance<JsonMappingException>().firstOrNull()
        val path = cause?.path?.joinToString(".") { it.fieldName ?: "[${it.index}]" }.orEmpty()
        return invalid(listOf(field(path.ifEmpty { "body" }, "Неверный тип, формат или отсутствует обязательное поле")))
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun missing() = fault(Fault("NOT_FOUND", 404, "Адрес не найден"))

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Request failed", ex)
        return fault(Fault("INTERNAL_ERROR", 500, "Внутренняя ошибка"))
    }

    private fun invalid(fields: List<Map<String, String>>) =
        fault(Fault("VALIDATION_ERROR", 400, "Некорректные данные", mapOf("fields" to fields)))

    private fun field(name: String, message: String) = mapOf(
        "field" to name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(),
        "message" to message
    )
}
