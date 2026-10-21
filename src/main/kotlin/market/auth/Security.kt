package market.auth

import market.Fault
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class Security {
    @Bean
    fun chain(http: HttpSecurity, jwt: Jwt, errors: AuthErrors): SecurityFilterChain {
        http.csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.requestMatchers("/auth/**").permitAll().anyRequest().authenticated() }
            .exceptionHandling {
                it.authenticationEntryPoint { _, res, _ -> errors.write(res, Fault("TOKEN_INVALID", 401, "Требуется Bearer токен")) }
                it.accessDeniedHandler { _, res, _ -> errors.write(res, Fault("ACCESS_DENIED", 403, "Недостаточно прав")) }
            }
            .addFilterBefore(JwtFilter(jwt, errors), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
