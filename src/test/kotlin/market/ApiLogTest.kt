package market

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.util.UUID

class ApiLogTest {
    @Test
    fun `logs rejected body without secrets`() {
        val json = jacksonObjectMapper()
        val log = LoggerFactory.getLogger("api") as Logger
        val sink = ListAppender<ILoggingEvent>()
        sink.start()
        log.addAppender(sink)
        try {
            val req = MockHttpServletRequest("POST", "/auth/login")
            req.setContent("""{"email":"a@b.dev","password":"hidden-pass","nested":[{"refresh_token":"hidden-token"}]}""".toByteArray())
            val res = MockHttpServletResponse()
            ApiLog(json, Clock.systemUTC()).doFilter(req, res) { _, response ->
                (response as MockHttpServletResponse).status = 401
            }
            val fields = sink.list.single().keyValuePairs.associate { it.key to it.value }
            assertThat(UUID.fromString(res.getHeader("X-Request-Id"))).isNotNull()
            assertThat(fields["request_id"]).isEqualTo(res.getHeader("X-Request-Id"))
            assertThat(fields["status_code"]).isEqualTo(401)
            assertThat(fields["user_id"]).isNull()
            assertThat(fields.keys).contains("method", "endpoint", "duration_ms", "timestamp", "body")
            val body = json.writeValueAsString(fields["body"])
            assertThat(body).contains("a@b.dev", "***").doesNotContain("hidden-pass", "hidden-token")
        } finally {
            log.detachAppender(sink)
            sink.stop()
        }
    }

    @Test
    fun `logs actor after request authentication`() {
        val log = LoggerFactory.getLogger("api") as Logger
        val sink = ListAppender<ILoggingEvent>()
        sink.start()
        log.addAppender(sink)
        try {
            val id = UUID.randomUUID()
            val req = MockHttpServletRequest("GET", "/products")
            ApiLog(jacksonObjectMapper(), Clock.systemUTC()).doFilter(req, MockHttpServletResponse()) { request, _ ->
                request.setAttribute("actor", Actor(id, "USER"))
            }
            val fields = sink.list.single().keyValuePairs.associate { it.key to it.value }
            assertThat(fields["user_id"]).isEqualTo(id.toString())
            assertThat(fields).doesNotContainKey("body")
        } finally {
            log.detachAppender(sink)
            sink.stop()
        }
    }
}
