package dev.mattest.supplychain.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Sql("/admin-test-seed.sql")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchEnhancementIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("supply_chain")
                    .withUsername("test")
                    .withPassword("test")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("scripts/schema.sql"),
                            "/docker-entrypoint-initdb.d/01-schema.sql");

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
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSource;

    @Test
    void managesHotKeywordsAndPublishesThemOverHttp() throws SQLException {
        CsrfCookie csrf = csrfCookie();

        ResponseEntity<Map<String, Object>> initiallyEmpty = rest.exchange(
                url("/api/v1/search/hot-keywords"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(initiallyEmpty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) initiallyEmpty.getBody().get("data")).isEmpty();

        ResponseEntity<String> anonymousSave = rest.exchange(
                url("/api/v1/admin/search/hot-keywords"),
                HttpMethod.PUT,
                jsonEntity(Map.of("keywords", List.of("雪花牛肉")), csrfHeaders(csrf)),
                String.class);
        assertThat(anonymousSave.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String adminCookie = login("root-admin", "A-strong-test-password-123!", csrf);
        HttpHeaders adminHeaders = authenticatedHeaders(adminCookie, csrf);

        ResponseEntity<Map<String, Object>> saved = rest.exchange(
                url("/api/v1/admin/search/hot-keywords"),
                HttpMethod.PUT,
                jsonEntity(Map.of("keywords", List.of("雪花牛肉", "牛毛肚")), adminHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> savedRows = (List<Map<String, Object>>) saved.getBody().get("data");
        assertThat(savedRows).extracting(row -> row.get("keyword"))
                .containsExactly("雪花牛肉", "牛毛肚");
        assertThat(savedRows).extracting(row -> row.get("sortOrder")).containsExactly(0, 1);
        assertHotKeywordAudit();

        ResponseEntity<Map<String, Object>> adminList = rest.exchange(
                url("/api/v1/admin/search/hot-keywords"),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(adminList.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> publicList = rest.exchange(
                url("/api/v1/search/hot-keywords"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(publicList.getBody().get("data")).isEqualTo(List.of("雪花牛肉", "牛毛肚"));

        ResponseEntity<String> duplicate = rest.exchange(
                url("/api/v1/admin/search/hot-keywords"),
                HttpMethod.PUT,
                jsonEntity(Map.of("keywords", List.of("相同", "相同")), adminHeaders),
                String.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> tooMany = rest.exchange(
                url("/api/v1/admin/search/hot-keywords"),
                HttpMethod.PUT,
                jsonEntity(Map.of("keywords", List.of("1", "2", "3", "4", "5", "6", "7", "8",
                        "9", "10", "11", "12", "13")), adminHeaders),
                String.class);
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map<String, Object>> replaced = rest.exchange(
                url("/api/v1/admin/search/hot-keywords"),
                HttpMethod.PUT,
                jsonEntity(Map.of("keywords", List.of("鸡爪")), adminHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> publicAfterReplace = rest.exchange(
                url("/api/v1/search/hot-keywords"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(publicAfterReplace.getBody().get("data")).isEqualTo(List.of("鸡爪"));

        String editorCookie = createEditorAndLogin("hotkeyword-editor", csrf, adminHeaders);
        HttpHeaders editorHeaders = authenticatedHeaders(editorCookie, csrf);
        ResponseEntity<String> editorSave = rest.exchange(
                url("/api/v1/admin/search/hot-keywords"),
                HttpMethod.PUT,
                jsonEntity(Map.of("keywords", List.of("无权")), editorHeaders),
                String.class);
        assertThat(editorSave.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void recommendsPublishedVisibleProductsBySortOrder() throws SQLException {
        long mediaId = insertReadyMedia();
        long rootId = insertCategory(null, "推荐根", 0);
        long leafId = insertCategory(rootId, "推荐叶", 0);
        long lowSortId = insertPublishedProduct("排序靠前", leafId, 0, mediaId);
        long highSortId = insertPublishedProduct("排序靠后", leafId, 9, mediaId);
        insertPublishedProduct("未上架", leafId, -1, mediaId, "UNPUBLISHED");

        ResponseEntity<Map<String, Object>> limited = rest.exchange(
                url("/api/v1/products/recommended?limit=1"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> one = (List<Map<String, Object>>) limited.getBody().get("data");
        assertThat(one).hasSize(1);
        assertThat(number(one.get(0).get("id"))).isEqualTo(lowSortId);

        ResponseEntity<Map<String, Object>> defaulted = rest.exchange(
                url("/api/v1/products/recommended"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> two = (List<Map<String, Object>>) defaulted.getBody().get("data");
        assertThat(two).extracting(row -> number(row.get("id")))
                .containsExactly(lowSortId, highSortId);

        ResponseEntity<Map<String, Object>> clamped = rest.exchange(
                url("/api/v1/products/recommended?limit=1000"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clampedRows = (List<Map<String, Object>>) clamped.getBody().get("data");
        assertThat(clampedRows).hasSize(2);
    }

    @Test
    void paginatesProductsWithSorting() throws SQLException {
        long mediaId = insertReadyMedia();
        long rootId = insertCategory(null, "分页根", 0);
        long leafId = insertCategory(rootId, "分页叶", 0);
        insertPublishedProduct("Z末位", leafId, 9, mediaId);
        insertPublishedProduct("A首位", leafId, 0, mediaId);

        ResponseEntity<Map<String, Object>> first = rest.exchange(
                url("/api/v1/products?page=1&pageSize=1&sort=default"),
                HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> page = (Map<String, Object>) first.getBody().get("data");
        assertThat(page).containsEntry("total", 2).containsEntry("page", 1).containsEntry("pageSize", 1);
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("name", "A首位");

        ResponseEntity<Map<String, Object>> latest = rest.exchange(
                url("/api/v1/categories/" + leafId + "/products?sort=latest"),
                HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});
        Map<String, Object> latestPage = (Map<String, Object>) latest.getBody().get("data");
        List<Map<String, Object>> latestItems = (List<Map<String, Object>>) latestPage.get("items");
        assertThat(latestItems).extracting(row -> row.get("name")).containsExactly("Z末位", "A首位");

        ResponseEntity<Map<String, Object>> clamped = rest.exchange(
                url("/api/v1/products?page=0&pageSize=1000"),
                HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});
        Map<String, Object> clampedPage = (Map<String, Object>) clamped.getBody().get("data");
        assertThat(clampedPage).containsEntry("page", 1).containsEntry("pageSize", 50);
    }

    @Test
    void managesShopInfoAndPublishesIt() {
        CsrfCookie csrf = csrfCookie();
        ResponseEntity<String> anonymous = rest.exchange(
                url("/api/v1/shop"), HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String adminCookie = login("root-admin", "A-strong-test-password-123!", csrf);
        HttpHeaders headers = authenticatedHeaders(adminCookie, csrf);
        ResponseEntity<Map<String, Object>> saved = rest.exchange(
                url("/api/v1/admin/shop"),
                HttpMethod.PUT,
                jsonEntity(Map.of(
                        "name", "小陈食品", "slogan", "火锅食材供应", "address", "成都",
                        "hours", "06:00-20:00", "phone", "13800006688", "wechat", "xiaochen"),
                        headers),
                new ParameterizedTypeReference<>() {});
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> published = rest.exchange(
                url("/api/v1/shop"), HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody().get("data")).isEqualTo(Map.of(
                "name", "小陈食品", "slogan", "火锅食材供应", "address", "成都",
                "hours", "06:00-20:00", "phone", "13800006688", "wechat", "xiaochen"));
    }

    @Test
    void bootstrapsCategoriesWithProducts() throws SQLException {
        long mediaId = insertReadyMedia();
        long rootId = insertCategory(null, "聚合根", 0);
        long leafId = insertCategory(rootId, "聚合叶", 0);
        insertPublishedProduct("聚合产品", leafId, 0, mediaId);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/catalog/bootstrap"), HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> categories = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(categories).hasSize(1);
        List<Map<String, Object>> products = (List<Map<String, Object>>) categories.get(0).get("products");
        assertThat(products).extracting(row -> row.get("name")).containsExactly("聚合产品");
    }

    private long insertReadyMedia() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO media_resources (
                         object_key, media_type, byte_size, resource_status,
                         public_url, uploaded_by, is_deleted
                     )
                     SELECT ?, 'image/jpeg', 1, 'READY', ?, id, FALSE
                     FROM admin_users WHERE username = 'root-admin'
                     """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "test/search-rec-" + System.nanoTime() + ".jpg");
            statement.setString(2, "/api/v1/media/test");
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private long insertCategory(Long parentId, String name, int sortOrder) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO categories (parent_id, name, is_active, sort_order, is_deleted)
                     VALUES (?, ?, TRUE, ?, FALSE)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            if (parentId == null) {
                statement.setNull(1, java.sql.Types.BIGINT);
            } else {
                statement.setLong(1, parentId);
            }
            statement.setString(2, name);
            statement.setInt(3, sortOrder);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private long insertPublishedProduct(String name, long categoryId, int sortOrder, long mediaId)
            throws SQLException {
        return insertPublishedProduct(name, categoryId, sortOrder, mediaId, "PUBLISHED");
    }

    private long insertPublishedProduct(
            String name, long categoryId, int sortOrder, long mediaId, String status)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO products (
                         name, status, category_id, main_image_media_id, sort_order, is_deleted
                     )
                     VALUES (?, ?, ?, ?, ?, FALSE)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, status);
            statement.setLong(3, categoryId);
            statement.setLong(4, mediaId);
            statement.setInt(5, sortOrder);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                long productId = keys.getLong(1);
                try (PreparedStatement spec = connection.prepareStatement("""
                        INSERT INTO product_specs (product_id, name, sort_order, is_deleted)
                        VALUES (?, '500g', 0, FALSE)
                        """)) {
                    spec.setLong(1, productId);
                    assertThat(spec.executeUpdate()).isEqualTo(1);
                }
                return productId;
            }
        }
    }

    private void assertHotKeywordAudit() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM audit_logs
                     WHERE action = 'SEARCH_HOT_KEYWORDS_UPDATED'
                       AND target_type = 'search_hot_keywords'
                       AND actor_admin_user_id IS NOT NULL
                     """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isGreaterThan(0);
            }
        }
    }

    private String createEditorAndLogin(
            String username, CsrfCookie csrf, HttpHeaders adminHeaders) {
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/v1/admin/accounts"),
                HttpMethod.POST,
                jsonEntity(new LinkedHashMap<>(Map.of(
                        "username", username,
                        "temporaryPassword", "Editor-password-123!",
                        "role", "EDITOR")), adminHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String temporaryCookie = login(username, "Editor-password-123!", csrf);
        ResponseEntity<String> changed = rest.exchange(
                url("/api/v1/admin/auth/change-password"),
                HttpMethod.POST,
                jsonEntity(Map.of("newPassword", "Editor-password-456!"),
                        authenticatedHeaders(temporaryCookie, csrf)),
                String.class);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return login(username, "Editor-password-456!", csrf);
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }

    private CsrfCookie csrfCookie() {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/admin/auth/csrf"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return new CsrfCookie(
                cookieValue(response.getHeaders(), "XSRF-TOKEN"),
                (String) data.get("token"));
    }

    private String login(String username, String password, CsrfCookie csrf) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/admin/auth/login"),
                HttpMethod.POST,
                jsonEntity(Map.of("username", username, "password", password), csrfHeaders(csrf)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return cookieValue(response.getHeaders(), "ADMIN_SESSION");
    }

    private HttpEntity<?> jsonEntity(Object body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private HttpHeaders authenticatedHeaders(String adminCookie, CsrfCookie csrf) {
        HttpHeaders headers = csrfHeaders(csrf);
        headers.set(HttpHeaders.COOKIE, csrf.cookie() + "; " + adminCookie);
        return headers;
    }

    private HttpHeaders csrfHeaders(CsrfCookie csrf) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, csrf.cookie());
        headers.set("X-XSRF-TOKEN", csrf.token());
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
}
