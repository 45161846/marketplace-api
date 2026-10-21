package market.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import market.Fault
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class JwtFilter(private val jwt: Jwt, private val errors: AuthErrors) : OncePerRequestFilter() {
    override fun shouldNotFilter(req: HttpServletRequest) = req.servletPath.startsWith("/auth/")

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val actor = try {
            val headers = req.getHeaders("Authorization").toList()
            if (headers.size != 1 || !headers[0].startsWith("Bearer ", true)) {
                throw Fault("TOKEN_INVALID", 401, "Требуется Bearer токен")
            }
            jwt.read(headers[0].substring(7), "access").actor
        } catch (ex: Fault) {
            errors.write(res, ex)
            return
        }
        req.setAttribute("actor", actor)
        val auth = UsernamePasswordAuthenticationToken(actor, null, listOf(SimpleGrantedAuthority("ROLE_${actor.role}")))
        val ctx = SecurityContextHolder.createEmptyContext()
        ctx.authentication = auth
        SecurityContextHolder.setContext(ctx)
        chain.doFilter(req, res)
    }
}
