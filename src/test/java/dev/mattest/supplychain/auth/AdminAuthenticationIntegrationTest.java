package dev.mattest.supplychain.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Sql("/admin-test-seed.sql")
@Import(AdminAuthenticationIntegrationTest.TimeConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminAuthenticationIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("supply_chain")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("app.security.allowed-origins", () -> "http://localhost:5173");
        registry.add("app.security.login-rate-limit.max-attempts", () -> "100");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MutableTestClock clock;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void servesSwaggerUiAndDocumentsAvailableApiEndpointsWithoutAuthentication() {
        ResponseEntity<String> swaggerUi = rest.getForEntity(
                url("/swagger-ui/index.html"), String.class);
        assertThat(swaggerUi.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> swaggerConfig = rest.getForEntity(
                url("/api/v1/openapi/swagger-config"), String.class);
        assertThat(swaggerConfig.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(swaggerConfig.getBody()).contains("\"url\":\"/api/v1/openapi\"");

        ResponseEntity<String> openApi = rest.getForEntity(
                url("/api/v1/openapi"), String.class);
        assertThat(openApi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openApi.getBody()).contains(
                "/api/v1/health",
                "/api/v1/admin/auth/login",
                "/api/v1/admin/auth/me",
                "/api/v1/admin/accounts",
                "/api/v1/admin/categories",
                "/api/v1/admin/products",
                "adminSession",
                "X-XSRF-TOKEN");
    }

    @Test
    void editorCanManageCatalogButCannotManageAccountsAndLogoutRevokesSession() {
        CsrfCookie csrf = csrfCookie();

        ResponseEntity<Map<String, Object>> health = rest.exchange(
                url("/api/v1/health"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {
                });
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).containsEntry("success", true);
        assertThat(((Map<?, ?>) health.getBody().get("data")).get("status")).isEqualTo("UP");

        ResponseEntity<Map<String, Object>> adminLogin = login(
                "root-admin", "A-strong-test-password-123!", csrf);
        assertThat(adminLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminLogin.getBody()).containsEntry("success", true);
        String adminCookie = cookieValue(adminLogin.getHeaders(), "ADMIN_SESSION");

        HttpHeaders adminHeaders = authenticatedHeaders(adminCookie, csrf);
        ResponseEntity<String> adminAccounts = rest.exchange(
                url("/api/v1/admin/accounts"),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);
        assertThat(adminAccounts.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> createEditor = rest.exchange(
                url("/api/v1/admin/accounts"),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "username", "catalog-editor",
                                "temporaryPassword", "Editor-password-123!",
                                "role", "EDITOR"),
                        adminHeaders),
                new ParameterizedTypeReference<>() {
                });
        assertThat(createEditor.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createEditor.getBody()).containsEntry("success", true);

        ResponseEntity<Map<String, Object>> editorLogin = login(
                "catalog-editor", "Editor-password-123!", csrf);
        assertThat(editorLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        String editorCookie = cookieValue(editorLogin.getHeaders(), "ADMIN_SESSION");

        ResponseEntity<String> changedPassword = rest.exchange(
                url("/api/v1/admin/auth/change-password"),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("newPassword", "Editor-password-456!"),
                        authenticatedHeaders(editorCookie, csrf)),
                String.class);
        assertThat(changedPassword.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> changedEditorLogin = login(
                "catalog-editor", "Editor-password-456!", csrf);
        assertThat(changedEditorLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        editorCookie = cookieValue(changedEditorLogin.getHeaders(), "ADMIN_SESSION");

        ResponseEntity<String> editorAccounts = rest.exchange(
                url("/api/v1/admin/accounts"),
                HttpMethod.GET,
                new HttpEntity<>(authenticatedHeaders(editorCookie, csrf)),
                String.class);
        assertThat(editorAccounts.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> logout = rest.exchange(
                url("/api/v1/admin/auth/logout"),
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders),
                String.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> revokedSession = rest.exchange(
                url("/api/v1/admin/accounts"),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                String.class);
        assertThat(revokedSession.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void currentUserReturnsTheAuthenticatedSessionIdentityIncludingPasswordChangeRequirement() {
        ResponseEntity<String> anonymous = rest.getForEntity(
                url("/api/v1/admin/auth/me"), String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        CsrfCookie csrf = csrfCookie();
        ResponseEntity<Map<String, Object>> adminLogin = login(
                "root-admin", "A-strong-test-password-123!", csrf);
        String adminCookie = cookieValue(adminLogin.getHeaders(), "ADMIN_SESSION");

        ResponseEntity<Map<String, Object>> adminMe = rest.exchange(
                url("/api/v1/admin/auth/me"),
                HttpMethod.GET,
                new HttpEntity<>(authenticatedHeaders(adminCookie, csrf)),
                new ParameterizedTypeReference<>() {
                });
        assertThat(adminMe.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminMe.getBody()).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> adminData = (Map<String, Object>) adminMe.getBody().get("data");
        assertThat(adminData)
                .containsEntry("username", "root-admin")
                .containsEntry("role", "ADMIN")
                .containsEntry("mustChangePassword", false);

        HttpHeaders adminHeaders = authenticatedHeaders(adminCookie, csrf);
        ResponseEntity<Map<String, Object>> createdEditor = rest.exchange(
                url("/api/v1/admin/accounts"),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "username", "first-login-editor",
                                "temporaryPassword", "Editor-password-123!",
                                "role", "EDITOR"),
                        adminHeaders),
                new ParameterizedTypeReference<>() {
                });
        assertThat(createdEditor.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> editorLogin = login(
                "first-login-editor", "Editor-password-123!", csrf);
        String editorCookie = cookieValue(editorLogin.getHeaders(), "ADMIN_SESSION");
        ResponseEntity<Map<String, Object>> editorMe = rest.exchange(
                url("/api/v1/admin/auth/me"),
                HttpMethod.GET,
                new HttpEntity<>(authenticatedHeaders(editorCookie, csrf)),
                new ParameterizedTypeReference<>() {
                });
        assertThat(editorMe.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> editorData = (Map<String, Object>) editorMe.getBody().get("data");
        assertThat(editorData)
                .containsEntry("username", "first-login-editor")
                .containsEntry("role", "EDITOR")
                .containsEntry("mustChangePassword", true);

        ResponseEntity<String> forbiddenCatalog = rest.exchange(
                url("/api/v1/admin/products"),
                HttpMethod.GET,
                new HttpEntity<>(authenticatedHeaders(editorCookie, csrf)),
                String.class);
        assertThat(forbiddenCatalog.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void locksAnAccountAfterFiveConsecutiveInvalidPasswords() {
        CsrfCookie csrf = csrfCookie();
        ResponseEntity<Map<String, Object>> adminLogin = login(
                "root-admin", "A-strong-test-password-123!", csrf);
        HttpHeaders adminHeaders = authenticatedHeaders(
                cookieValue(adminLogin.getHeaders(), "ADMIN_SESSION"), csrf);
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/v1/admin/accounts"),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "username", "lock-test",
                                "temporaryPassword", "Lock-test-password-123!",
                                "role", "EDITOR"),
                        adminHeaders),
                new ParameterizedTypeReference<>() {
                });
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        for (int attempt = 0; attempt < 5; attempt++) {
            ResponseEntity<Map<String, Object>> invalidLogin = login(
                    "lock-test", "Incorrect-password-123!", csrf);
            assertThat(invalidLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<Map<String, Object>> lockedLogin = login(
                "lock-test", "Lock-test-password-123!", csrf);
        assertThat(lockedLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiresAnIdleSessionAfterTwelveHours() {
        CsrfCookie csrf = csrfCookie();
        ResponseEntity<Map<String, Object>> adminLogin = login(
                "root-admin", "A-strong-test-password-123!", csrf);
        String adminCookie = cookieValue(adminLogin.getHeaders(), "ADMIN_SESSION");
        clock.advance(Duration.ofHours(13));

        ResponseEntity<String> expiredSession = rest.exchange(
                url("/api/v1/admin/accounts"),
                HttpMethod.GET,
                new HttpEntity<>(authenticatedHeaders(adminCookie, csrf)),
                String.class);
        assertThat(expiredSession.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void writeRequestsRequireCsrfToken() {
        CsrfCookie csrf = csrfCookie();
        ResponseEntity<Map<String, Object>> login = login(
                "root-admin", "A-strong-test-password-123!", csrf);
        String adminCookie = cookieValue(login.getHeaders(), "ADMIN_SESSION");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, adminCookie);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/admin/accounts"),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "username", "blocked-editor",
                                "temporaryPassword", "Editor-password-123!",
                                "role", "EDITOR"),
                        headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private CsrfCookie csrfCookie() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/admin/auth/csrf"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", true);
        String cookie = cookieValue(response.getHeaders(), "XSRF-TOKEN");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return new CsrfCookie(cookie, (String) data.get("token"));
    }

    private ResponseEntity<Map<String, Object>> login(
            String username, String password, CsrfCookie csrf) {
        HttpHeaders headers = csrfHeaders(csrf);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(
                url("/api/v1/admin/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", password), headers),
                new ParameterizedTypeReference<>() {
                });
    }

    private HttpHeaders authenticatedHeaders(String adminCookie, CsrfCookie csrf) {
        HttpHeaders headers = csrfHeaders(csrf);
        headers.set(HttpHeaders.COOKIE, csrf.cookie() + "; " + adminCookie);
        return headers;
    }

    private HttpHeaders csrfHeaders(CsrfCookie csrf) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, csrf.cookie());
        headers.add("X-XSRF-TOKEN", csrf.token());
        return headers;
    }

    private String cookieValue(HttpHeaders headers, String cookieName) {
        return headers.get(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(cookieName + "="))
                .map(value -> value.split(";", 2)[0])
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing cookie " + cookieName));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private record CsrfCookie(String cookie, String token) {
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableTestClock mutableTestClock() {
            return new MutableTestClock();
        }
    }

    static class MutableTestClock extends Clock {
        private final AtomicReference<Instant> current =
                new AtomicReference<>(Instant.now());

        void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
