package market.product

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
class Products(private val repo: ProductRepo) : ProductsApi {
    @Transactional
    override fun createProduct(body: ProductCreate): ResponseEntity<ProductResponse> =
        ResponseEntity.status(201).body(repo.create(body))

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
        find(id, true)
        return ResponseEntity.ok(repo.update(id, body))
    }

    @Transactional
    override fun deleteProduct(id: UUID): ResponseEntity<Unit> {
        find(id, true)
        repo.archive(id)
        return ResponseEntity.noContent().build()
    }

    private fun find(id: UUID, lock: Boolean = false): ProductResponse =
        repo.find(id, lock) ?: throw Fault("PRODUCT_NOT_FOUND", 404, "Товар не найден")
}
