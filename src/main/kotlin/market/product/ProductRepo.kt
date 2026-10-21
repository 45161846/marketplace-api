package market.product

import market.model.ProductCreate
import market.model.ProductResponse
import market.model.ProductStatus
import market.model.ProductUpdate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class ProductRepo(private val db: JdbcTemplate) {
    private val row = RowMapper { rs, _ ->
        ProductResponse(
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            price = rs.getBigDecimal("price"),
            stock = rs.getInt("stock"),
            category = rs.getString("category"),
            status = ProductStatus.valueOf(rs.getString("status")),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
            description = rs.getString("description")
        )
    }

    fun create(body: ProductCreate): ProductResponse = db.queryForObject(
        """
        INSERT INTO products (name, description, price, stock, category, status)
        VALUES (?, ?, ?, ?, ?, ?)
        RETURNING *
        """.trimIndent(),
        row, body.name, body.description, body.price, body.stock, body.category, body.status.name
    )!!

    fun find(id: UUID, lock: Boolean = false): ProductResponse? {
        val sql = "SELECT * FROM products WHERE id = ?" + if (lock) " FOR UPDATE" else ""
        return db.query(sql, row, id).singleOrNull()
    }

    fun update(id: UUID, body: ProductUpdate): ProductResponse = db.queryForObject(
        """
        UPDATE products SET name = ?, description = ?, price = ?, stock = ?, category = ?, status = ?
        WHERE id = ?
        RETURNING *
        """.trimIndent(),
        row, body.name, body.description, body.price, body.stock, body.category, body.status.name, id
    )!!

    fun archive(id: UUID) {
        db.update("UPDATE products SET status = 'ARCHIVED' WHERE id = ?", id)
    }

    fun list(page: Int, size: Int, status: ProductStatus?, category: String?): List<ProductResponse> {
        val (where, args) = filter(status, category)
        val sql = "SELECT * FROM products $where ORDER BY created_at, id LIMIT ? OFFSET ?"
        return db.query(sql, row, *(args + size + page.toLong() * size).toTypedArray())
    }

    fun count(status: ProductStatus?, category: String?): Long {
        val (where, args) = filter(status, category)
        return db.queryForObject("SELECT count(*) FROM products $where", Long::class.java, *args.toTypedArray())!!
    }

    private fun filter(status: ProductStatus?, category: String?): Pair<String, List<Any>> {
        val terms = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (status != null) {
            terms += "status = ?"
            args += status.name
        }
        if (category != null) {
            terms += "category = ?"
            args += category
        }
        val where = if (terms.isEmpty()) "" else "WHERE " + terms.joinToString(" AND ")
        return where to args
    }
}
