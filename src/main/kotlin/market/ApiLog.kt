package market

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import java.time.Clock
import java.util.UUID

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiLog(private val json: ObjectMapper, private val clock: Clock) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger("api")
    private val changes = setOf("POST", "PUT", "DELETE")

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val id = UUID.randomUUID().toString()
        val time = clock.instant()
        val start = System.nanoTime()
        val cached = ContentCachingRequestWrapper(req, 1_048_576)
        res.setHeader("X-Request-Id", id)
        try {
            chain.doFilter(cached, res)
        } finally {
            val entry = log.atInfo()
                .addKeyValue("request_id", id)
                .addKeyValue("method", req.method)
                .addKeyValue("endpoint", req.requestURI)
                .addKeyValue("status_code", res.status)
                .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000.0)
                .addKeyValue("user_id", (req.getAttribute("actor") as? Actor)?.id?.toString())
                .addKeyValue("timestamp", time.toString())
            if (req.method in changes) entry.addKeyValue("body", body(cached))
            entry.log("http")
        }
    }

    private fun body(req: ContentCachingRequestWrapper): Any? = try {
        req.inputStream.readNBytes(1_048_576)
        val bytes = req.contentAsByteArray
        if (bytes.isEmpty()) null else {
            val tree = json.readTree(bytes)
            hide(tree)
            json.convertValue(tree, Any::class.java)
        }
    } catch (_: Exception) {
        "<unreadable body>"
    }

    private fun hide(node: JsonNode) {
        if (node is ObjectNode) {
            node.fieldNames().asSequence().toList().forEach { key ->
                val name = key.lowercase()
                if (listOf("password", "token", "secret", "authorization").any { it in name }) {
                    node.put(key, "***")
                } else {
                    hide(node[key])
                }
            }
        } else if (node.isArray) node.forEach(::hide)
    }
}
