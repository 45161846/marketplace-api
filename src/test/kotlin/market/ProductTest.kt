package market

import com.fasterxml.jackson.databind.JsonNode
import market.model.ProductCreate
import market.model.ProductPage
import market.model.ProductResponse
import market.model.ProductStatus
import market.model.ProductUpdate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProductTest @Autowired constructor(private val http: TestRestTemplate, private val db: JdbcTemplate) {
    @AfterEach
    fun clear() {
        db.execute("TRUNCATE products CASCADE")
    }

    @Test
    fun `create and read product`() {
        val body = data().copy(description = "Paperback")
        val result = http.postForEntity("/products", body, ProductResponse::class.java)
        assertThat(result.statusCode.value()).isEqualTo(201)
        val item = result.body!!
        assertThat(item.name).isEqualTo(body.name)
        assertThat(item.description).isEqualTo(body.description)
        assertThat(item.price).isEqualByComparingTo(body.price)
        assertThat(item.stock).isEqualTo(body.stock)
        assertThat(item.category).isEqualTo(body.category)
        assertThat(item.status).isEqualTo(ProductStatus.ACTIVE)
        assertThat(item.createdAt).isEqualTo(item.updatedAt)
        val read = http.getForEntity("/products/${item.id}", ProductResponse::class.java)
        assertThat(read.statusCode.value()).isEqualTo(200)
        assertThat(read.body).isEqualTo(item)
    }

    @Test
    fun `update replaces fields and clears description`() {
        val item = create(data().copy(description = "Old description"))
        val body = ProductUpdate("Pen", BigDecimal("12.34"), 5, "Office", ProductStatus.INACTIVE)
        val result = http.exchange("/products/${item.id}", HttpMethod.PUT, HttpEntity(body), ProductResponse::class.java)
        assertThat(result.statusCode.value()).isEqualTo(200)
        val saved = result.body!!
        assertThat(saved.id).isEqualTo(item.id)
        assertThat(saved.name).isEqualTo(body.name)
        assertThat(saved.description).isNull()
        assertThat(saved.price).isEqualByComparingTo(body.price)
        assertThat(saved.stock).isEqualTo(body.stock)
        assertThat(saved.category).isEqualTo(body.category)
        assertThat(saved.status).isEqualTo(body.status)
        assertThat(saved.createdAt).isEqualTo(item.createdAt)
        assertThat(saved.updatedAt).isAfter(item.updatedAt)
    }

    @Test
    fun `list filters and counts before pagination`() {
        val first = create(data().copy(name = "First"))
        val second = create(data().copy(name = "Second"))
        create(data().copy(status = ProductStatus.INACTIVE))
        create(data().copy(category = "books"))
        val result = http.getForEntity("/products?status=ACTIVE&category=Books&page=1&size=1", ProductPage::class.java)
        assertThat(result.statusCode.value()).isEqualTo(200)
        val page = result.body!!
        assertThat(page.totalElements).isEqualTo(2)
        assertThat(page.page).isEqualTo(1)
        assertThat(page.propertySize).isEqualTo(1)
        assertThat(page.items.map { it.id }).containsExactly(second.id)
        val all = http.getForObject("/products", ProductPage::class.java)!!
        assertThat(all.totalElements).isEqualTo(4)
        assertThat(all.page).isZero()
        assertThat(all.propertySize).isEqualTo(20)
        assertThat(all.items.first().id).isEqualTo(first.id)
        val empty = http.getForObject("/products?page=10&size=1", ProductPage::class.java)!!
        assertThat(empty.items).isEmpty()
        assertThat(empty.totalElements).isEqualTo(4)
    }

    @Test
    fun `delete archives row`() {
        val item = create()
        val result = http.exchange("/products/${item.id}", HttpMethod.DELETE, HttpEntity.EMPTY, String::class.java)
        assertThat(result.statusCode.value()).isEqualTo(204)
        assertThat(result.body).isNull()
        assertThat(db.queryForObject("SELECT count(*) FROM products WHERE id = ?", Long::class.java, item.id)).isEqualTo(1L)
        assertThat(db.queryForObject("SELECT status FROM products WHERE id = ?", String::class.java, item.id)).isEqualTo("ARCHIVED")
        val read = http.getForObject("/products/${item.id}", ProductResponse::class.java)!!
        assertThat(read.status).isEqualTo(ProductStatus.ARCHIVED)
    }

    @Test
    fun `missing product returns not found`() {
        val id = UUID.randomUUID()
        val read = http.getForEntity("/products/$id", JsonNode::class.java)
        assertThat(read.statusCode.value()).isEqualTo(404)
        assertThat(read.body!!.get("error_code").asText()).isEqualTo("PRODUCT_NOT_FOUND")
        val body = ProductUpdate("Pen", BigDecimal("1.00"), 1, "Office", ProductStatus.ACTIVE)
        val edit = http.exchange("/products/$id", HttpMethod.PUT, HttpEntity(body), JsonNode::class.java)
        assertThat(edit.statusCode.value()).isEqualTo(404)
        assertThat(edit.body!!.get("error_code").asText()).isEqualTo("PRODUCT_NOT_FOUND")
        val remove = http.exchange("/products/$id", HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode::class.java)
        assertThat(remove.statusCode.value()).isEqualTo(404)
        assertThat(remove.body!!.get("error_code").asText()).isEqualTo("PRODUCT_NOT_FOUND")
    }

    private fun create(body: ProductCreate = data()): ProductResponse =
        http.postForEntity("/products", body, ProductResponse::class.java).body!!

    private fun data() = ProductCreate("Book", BigDecimal("24.50"), 10, "Books", ProductStatus.ACTIVE)
}
