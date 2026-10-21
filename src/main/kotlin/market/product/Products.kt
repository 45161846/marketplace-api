package market.product

import market.Actor
import market.Caller
import market.Fault
import market.api.ProductsApi
import market.model.ProductCreate
import market.model.ProductPage
import market.model.ProductResponse
import market.model.ProductStatus
import market.model.ProductUpdate
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class Products(private val repo: ProductRepo, private val caller: Caller) : ProductsApi {
    @Transactional
    override fun createProduct(body: ProductCreate): ResponseEntity<ProductResponse> {
        val actor = writer()
        val owner = body.sellerId ?: actor.id
        if (actor.role == "SELLER" && owner != actor.id) denied()
        if (!repo.owner(owner)) {
            throw Fault("VALIDATION_ERROR", 400, "Владелец должен быть продавцом или администратором", mapOf(
                "fields" to listOf(mapOf("field" to "seller_id", "message" to "Продавец или администратор не найден"))
            ))
        }
        return ResponseEntity.status(201).body(repo.create(body, owner))
    }

    @Transactional(readOnly = true)
    override fun getProduct(id: UUID): ResponseEntity<ProductResponse> = ResponseEntity.ok(find(id))

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun listProducts(
        page: Int,
        size: Int,
        status: ProductStatus?,
        category: String?
    ): ResponseEntity<ProductPage> = ResponseEntity.ok(
        ProductPage(repo.list(page, size, status, category), repo.count(status, category), page, size)
    )

    @Transactional
    override fun updateProduct(id: UUID, body: ProductUpdate): ResponseEntity<ProductResponse> {
        own(id)
        return ResponseEntity.ok(repo.update(id, body))
    }

    @Transactional
    override fun deleteProduct(id: UUID): ResponseEntity<Unit> {
        own(id)
        repo.archive(id)
        return ResponseEntity.noContent().build()
    }

    private fun find(id: UUID, lock: Boolean = false): ProductResponse =
        repo.find(id, lock) ?: throw Fault("PRODUCT_NOT_FOUND", 404, "Товар не найден")

    private fun writer(): Actor {
        val actor = caller.get()
        if (actor.role !in setOf("SELLER", "ADMIN")) denied()
        return actor
    }

    private fun own(id: UUID) {
        val actor = writer()
        val item = find(id, true)
        if (actor.role == "SELLER" && item.sellerId != actor.id) denied()
    }

    private fun denied(): Nothing = throw Fault("ACCESS_DENIED", 403, "Недостаточно прав")
}
