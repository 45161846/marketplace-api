package market

class Fault(
    val code: String,
    val status: Int,
    message: String,
    val details: Map<String, Any>? = null
) : RuntimeException(message)
