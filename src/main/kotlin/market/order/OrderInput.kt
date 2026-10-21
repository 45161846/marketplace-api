package market.order

import market.Fault
import market.model.OrderLine

object OrderInput {
    fun check(items: List<OrderLine>, code: String? = null) {
        if (items.size !in 1..50) bad("items", "Нужно от 1 до 50 позиций")
        items.forEachIndexed { i, item ->
            if (item.quantity !in 1..999) bad("items[$i].quantity", "Количество должно быть от 1 до 999")
        }
        if (items.map { it.productId }.toSet().size != items.size) bad("items", "Товары не должны повторяться")
        if (code != null && !code.matches(Regex("^[A-Z0-9_]{4,20}$"))) bad("promo_code", "Неверный формат промокода")
    }

    private fun bad(field: String, message: String): Nothing = throw Fault(
        "VALIDATION_ERROR", 400, "Некорректные данные",
        mapOf("fields" to listOf(mapOf("field" to field, "message" to message)))
    )
}
