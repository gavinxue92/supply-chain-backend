package dev.mattest.supplychain.media;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Sql("/admin-test-seed.sql")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaAdministrationIntegrationTest {
    private static final String MINIO_ACCESS_KEY = "test-minio-user";
    private static final String MINIO_SECRET_KEY = "test-minio-password-123";

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("supply_chain")
                    .withUsername("test")
                    .withPassword("test")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("scripts/schema.sql"),
                            "/docker-entrypoint-initdb.d/01-schema.sql");

    @Container
    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
                    .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("app.security.allowed-origins", () -> "http://localhost:5173");
        registry.add("app.security.login-rate-limit.max-attempts", () -> "100");
        registry.add("app.media.storage.endpoint", MediaAdministrationIntegrationTest::minioEndpoint);
        registry.add("app.media.storage.public-endpoint", MediaAdministrationIntegrationTest::minioEndpoint);
        registry.add("app.media.storage.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("app.media.storage.secret-key", () -> MINIO_SECRET_KEY);
        registry.add("app.media.storage.bucket", () -> "product-media-test");
        registry.add("app.media.storage.region", () -> "us-east-1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void editorCanUploadConfirmAndReadAnImageWithoutAuthentication() throws Exception {
        byte[] imageBytes = jpegContent();
        CsrfCookie csrf = csrfCookie();
        ResponseEntity<String> anonymousUpload = rest.exchange(
                url("/api/v1/admin/media/uploads"),
                HttpMethod.POST,
                jsonEntity(
                        Map.of("originalFilename", "cover.jpg", "contentType", "image/jpeg"),
                        csrfHeaders(csrf)),
                String.class);
        assertThat(anonymousUpload.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String adminCookie = login("root-admin", "A-strong-test-password-123!", csrf);
        HttpHeaders headers = authenticatedHeaders(adminCookie, csrf);

        ResponseEntity<String> unsupportedType = rest.exchange(
                url("/api/v1/admin/media/uploads"),
                HttpMethod.POST,
                jsonEntity(Map.of("originalFilename", "script.svg", "contentType", "image/svg+xml"), headers),
                String.class);
        assertThat(unsupportedType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> mismatchedUpload = authorizeUpload("image/png", "photo.png", headers);
        upload((String) mismatchedUpload.get("uploadUrl"), "image/png", imageBytes);
        ResponseEntity<String> mismatchedConfirmation = confirmUpload(
                ((Number) mismatchedUpload.get("mediaId")).longValue(), headers);
        assertThat(mismatchedConfirmation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> invalidImageUpload = authorizeUpload("image/jpeg", "invalid.jpg", headers);
        upload((String) invalidImageUpload.get("uploadUrl"), "image/jpeg",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
        ResponseEntity<String> invalidImageConfirmation = confirmUpload(
                ((Number) invalidImageUpload.get("mediaId")).longValue(), headers);
        assertThat(invalidImageConfirmation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> oversizedUpload = authorizeUpload("image/jpeg", "large.jpg", headers);
        byte[] oversizedBytes = new byte[10 * 1024 * 1024 + 1];
        oversizedBytes[0] = (byte) 0xff;
        oversizedBytes[1] = (byte) 0xd8;
        oversizedBytes[2] = (byte) 0xff;
        upload((String) oversizedUpload.get("uploadUrl"), "image/jpeg", oversizedBytes);
        ResponseEntity<String> oversizedConfirmation = confirmUpload(
                ((Number) oversizedUpload.get("mediaId")).longValue(), headers);
        assertThat(oversizedConfirmation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map<String, Object>> authorization = rest.exchange(
                url("/api/v1/admin/media/uploads"),
                HttpMethod.POST,
                jsonEntity(Map.of("originalFilename", "cover.jpg", "contentType", "image/jpeg"), headers),
                new ParameterizedTypeReference<>() {});
        assertThat(authorization.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> upload = data(authorization);
        long mediaId = ((Number) upload.get("mediaId")).longValue();
        String uploadUrl = (String) upload.get("uploadUrl");

        ResponseEntity<String> unconfirmedReference = rest.exchange(
                url("/api/v1/admin/products"),
                HttpMethod.POST,
                jsonEntity(Map.of("mainImageMediaId", mediaId), headers),
                String.class);
        assertThat(unconfirmedReference.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        HttpRequest put = HttpRequest.newBuilder(URI.create(uploadUrl))
                .header("Content-Type", "image/jpeg")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                .build();
        HttpResponse<Void> uploaded = HttpClient.newHttpClient()
                .send(put, HttpResponse.BodyHandlers.discarding());
        assertThat(uploaded.statusCode()).isBetween(200, 299);

        ResponseEntity<Map<String, Object>> confirmation = rest.exchange(
                url("/api/v1/admin/media/" + mediaId + "/confirm"),
                HttpMethod.POST,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
        assertThat(confirmation.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> media = data(confirmation);
        assertThat(media).containsEntry("id", mediaId)
                .containsEntry("mediaType", "image/jpeg")
                .containsEntry("byteSize", imageBytes.length)
                .containsEntry("status", "READY");

        ResponseEntity<byte[]> publicImage = rest.getForEntity(
                url("/api/v1/media/" + mediaId), byte[].class);
        assertThat(publicImage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicImage.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(publicImage.getBody()).containsExactly(imageBytes);

        ResponseEntity<Map<String, Object>> product = rest.exchange(
                url("/api/v1/admin/products"),
                HttpMethod.POST,
                jsonEntity(Map.of(
                        "name", "牛腩",
                        "mainImageMediaId", mediaId,
                        "galleryMediaIds", new long[] {mediaId},
                        "detailText", "<h2 onclick='run()'>说明</h2><script>alert(1)</script>"
                                + "<img src='/api/v1/media/" + mediaId
                                + "' style='position:fixed' onerror='run()'>"),
                        headers),
                new ParameterizedTypeReference<>() {});
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> productData = data(product);
        assertThat(productData).containsEntry("mainImageMediaId", mediaId)
                .containsEntry("detailText",
                        "<h2>说明</h2><img src=\"/api/v1/media/" + mediaId + "\">");
        assertThat((java.util.List<?>) productData.get("galleryMediaIds")).hasSize(1);
        assertThat(((Number) ((java.util.List<?>) productData.get("galleryMediaIds")).getFirst())
                .longValue()).isEqualTo(mediaId);

        ResponseEntity<String> tooManyGalleryImages = rest.exchange(
                url("/api/v1/admin/products"),
                HttpMethod.POST,
                jsonEntity(Map.of(
                        "galleryMediaIds",
                        java.util.List.of(mediaId, mediaId, mediaId, mediaId, mediaId,
                                mediaId, mediaId, mediaId, mediaId)),
                        headers),
                String.class);
        assertThat(tooManyGalleryImages.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> externalImage = rest.exchange(
                url("/api/v1/admin/products"),
                HttpMethod.POST,
                jsonEntity(Map.of("detailText", "<p>内容</p><img src='https://example.com/image.jpg'>"),
                        headers),
                String.class);
        assertThat(externalImage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private Map<String, Object> authorizeUpload(
            String contentType, String filename, HttpHeaders headers) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/admin/media/uploads"),
                HttpMethod.POST,
                jsonEntity(Map.of("originalFilename", filename, "contentType", contentType), headers),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return data(response);
    }

    private void upload(String uploadUrl, String contentType, byte[] bytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uploadUrl))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isBetween(200, 299);
    }

    private ResponseEntity<String> confirmUpload(long mediaId, HttpHeaders headers) {
        return rest.exchange(
                url("/api/v1/admin/media/" + mediaId + "/confirm"),
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class);
    }

    private static byte[] jpegContent() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, "jpeg", output)).isTrue();
            return output.toByteArray();
        }
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

    private Map<String, Object> data(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return data;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private record CsrfCookie(String cookie, String token) {
    }
}
