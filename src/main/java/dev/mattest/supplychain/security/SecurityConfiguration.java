package dev.mattest.supplychain.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mattest.supplychain.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AdminSessionFilter adminSessionFilter,
            CorsConfigurationSource corsConfigurationSource,
            ObjectMapper objectMapper) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/api/v1");
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/health", "/api/v1/admin/auth/csrf",
                                "/api/v1/admin/auth/login", "/api/v1/openapi/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/admin/auth/change-password")
                        .access((authentication, context) -> new AuthorizationDecision(
                                isChangingPassword(authentication.get())))
                        .requestMatchers("/api/v1/admin/auth/me")
                        .access((authentication, context) -> new AuthorizationDecision(
                                isAdminUser(authentication.get())))
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/categories", "/api/v1/categories/**", "/api/v1/products/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/media/**").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/accounts/me/nickname")
                        .access((authentication, context) -> new AuthorizationDecision(
                                isAdminUser(authentication.get()) && !mustChangePassword(authentication.get())))
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/customer-services")
                        .access((authentication, context) -> new AuthorizationDecision(
                                isAdminUser(authentication.get()) && !mustChangePassword(authentication.get())))
                        .requestMatchers("/api/v1/admin/customer-services/**")
                        .access((authentication, context) -> new AuthorizationDecision(
                                isAdmin(authentication.get()) && !mustChangePassword(authentication.get())))
                        .requestMatchers("/api/v1/admin/accounts/**")
                        .access((authentication, context) -> new AuthorizationDecision(
                                isAdmin(authentication.get()) && !mustChangePassword(authentication.get())))
                        .requestMatchers("/api/v1/admin/**")
                        .access((authentication, context) -> new AuthorizationDecision(
                                isAdminUser(authentication.get()) && !mustChangePassword(authentication.get())))
                        .anyRequest().denyAll())
                .addFilterBefore(adminSessionFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, objectMapper, 401, "Authentication required"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, objectMapper, 403, "Access denied")));

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    java.time.Clock applicationClock() {
        return java.time.Clock.systemUTC();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.security.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Accept"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }

    private boolean isChangingPassword(Authentication authentication) {
        return isAdminUser(authentication)
                && ((AdminPrincipal) authentication.getPrincipal()).mustChangePassword();
    }

    private boolean isAdmin(Authentication authentication) {
        return isAdminUser(authentication)
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    private boolean isAdminUser(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AdminPrincipal;
    }

    private boolean mustChangePassword(Authentication authentication) {
        return isAdminUser(authentication)
                && ((AdminPrincipal) authentication.getPrincipal()).mustChangePassword();
    }

    private void writeError(
            jakarta.servlet.http.HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Result.failure(message));
    }
}
