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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Sql("/admin-test-seed.sql")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogAdministrationIntegrationTest {
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
    void managesTwoLevelCategoriesAndIncompleteProductDraftsOverHttp() throws SQLException {
        CsrfCookie csrf = csrfCookie();
        ResponseEntity<String> anonymous = rest.exchange(
                url("/api/v1/admin/categories"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String adminCookie = login("root-admin", "A-strong-test-password-123!", csrf);
        HttpHeaders adminHeaders = authenticatedHeaders(adminCookie, csrf);
        ResponseEntity<Map<String, Object>> createdEditor = rest.exchange(
                url("/api/v1/admin/accounts"),
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "username", "catalog-editor",
                                "temporaryPassword", "Editor-password-123!",
                                "role", "EDITOR"),
                        adminHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(createdEditor.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String editorCookie = login("catalog-editor", "Editor-password-123!", csrf);
        ResponseEntity<String> changedPassword = rest.exchange(
                url("/api/v1/admin/auth/change-password"),
                HttpMethod.POST,
                jsonEntity(Map.of("newPassword", "Editor-password-456!"),
                        authenticatedHeaders(editorCookie, csrf)),
                String.class);
        assertThat(changedPassword.getStatusCode()).isEqualTo(HttpStatus.OK);
        editorCookie = login("catalog-editor", "Editor-password-456!", csrf);
        HttpHeaders editorHeaders = authenticatedHeaders(editorCookie, csrf);

        Map<String, Object> root = createCategory("食材", null, 0, editorHeaders);
        long rootId = number(root.get("id"));
        Map<String, Object> child = createCategory("肉类", rootId, 10, editorHeaders);
        long childId = number(child.get("id"));
        ResponseEntity<Map<String, Object>> updatedChild = rest.exchange(
                url("/api/v1/admin/categories/" + childId),
                HttpMethod.PUT,
                jsonEntity(categoryRequest("肉类", rootId, 12), editorHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(updatedChild.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getData(updatedChild)).containsEntry("sortOrder", 12);
        assertAuditRecorded("CATEGORY_UPDATED", "categories", childId);
        Map<String, Object> anotherRoot = createCategory("冷藏", null, 1, editorHeaders);
        createCategory("肉类", number(anotherRoot.get("id")), 10, editorHeaders);

        ResponseEntity<String> duplicate = rest.exchange(
                url("/api/v1/admin/categories"),
                HttpMethod.POST,
                jsonEntity(categoryRequest("食材", null, 0), editorHeaders),
                String.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> thirdLevel = rest.exchange(
                url("/api/v1/admin/categories"),
                HttpMethod.POST,
                jsonEntity(categoryRequest("牛肉", childId, 0), editorHeaders),
                String.class);
        assertThat(thirdLevel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> parentDelete = rest.exchange(
                url("/api/v1/admin/categories/" + rootId),
                HttpMethod.DELETE,
                new HttpEntity<>(editorHeaders),
                String.class);
        assertThat(parentDelete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> reusable = createCategory("临时分类", null, 99, editorHeaders);
        ResponseEntity<String> deleteReusable = rest.exchange(
                url("/api/v1/admin/categories/" + number(reusable.get("id"))),
                HttpMethod.DELETE,
                new HttpEntity<>(editorHeaders),
                String.class);
        assertThat(deleteReusable.getStatusCode()).isEqualTo(HttpStatus.OK);
        createCategory("临时分类", null, 99, editorHeaders);

        ResponseEntity<String> deactivated = rest.exchange(
                url("/api/v1/admin/categories/" + rootId + "/status"),
                HttpMethod.PATCH,
                jsonEntity(Map.of("active", false), editorHeaders),
                String.class);
        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertAuditRecorded("CATEGORY_DEACTIVATED", "categories", rootId);
        Map<String, Object> unchangedChild = getData(rest.exchange(
                url("/api/v1/admin/categories/" + childId),
                HttpMethod.GET,
                new HttpEntity<>(editorHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {}));
        assertThat(unchangedChild).containsEntry("active", true);

        Map<String, Object> emptyDraft = createDraft(Map.of(), editorHeaders);
        long emptyDraftId = number(emptyDraft.get("id"));
        assertThat(emptyDraft).doesNotContainKey("name");
        assertThat((List<?>) emptyDraft.get("specifications")).isEmpty();

        ResponseEntity<String> parentAsProductCategory = rest.exchange(
                url("/api/v1/admin/products"),
                HttpMethod.POST,
                jsonEntity(productRequest("Wrong level", rootId, List.of(),
                        List.of()), editorHeaders),
                String.class);
        assertThat(parentAsProductCategory.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> draftRequest = productRequest(
                "牛腩", childId, List.of("牛腩", "牛肉"),
                List.of(Map.of("name", "500g", "referencePrice", 25.50),
                        mapWithNullablePrice("1kg", null)));
        Map<String, Object> draft = createDraft(draftRequest, editorHeaders);
        long productId = number(draft.get("id"));
        assertAuditRecorded("PRODUCT_DRAFT_CREATED", "products", productId);
        assertThat(draft).containsEntry("name", "牛腩")
                .containsEntry("categoryId", childId)
                .containsEntry("detailText", "适合炖煮");
        assertThat(draft.get("keywords")).isEqualTo(List.of("牛腩", "牛肉"));
        assertThat((List<?>) draft.get("specifications")).hasSize(2);

        ResponseEntity<String> zeroPrice = rest.exchange(
                url("/api/v1/admin/products/" + productId),
                HttpMethod.PUT,
                jsonEntity(productRequest("牛腩", childId, List.of(),
                        List.of(Map.of("name", "500g", "referencePrice", 0))),
                        editorHeaders),
                String.class);
        assertThat(zeroPrice.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> fractionalCent = rest.exchange(
                url("/api/v1/admin/products/" + productId),
                HttpMethod.PUT,
                jsonEntity(productRequest("牛腩", childId, List.of(),
                        List.of(Map.of("name", "500g", "referencePrice", 1.234))),
                        editorHeaders),
                String.class);
        assertThat(fractionalCent.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> linkedCategoryDelete = rest.exchange(
                url("/api/v1/admin/categories/" + childId),
                HttpMethod.DELETE,
                new HttpEntity<>(editorHeaders),
                String.class);
        assertThat(linkedCategoryDelete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> draftUpdate = rest.exchange(
                url("/api/v1/admin/products/" + emptyDraftId),
                HttpMethod.PUT,
                jsonEntity(productRequest(null, null, List.of(), List.of()), editorHeaders),
                String.class);
        assertThat(draftUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> enabledRoot = createCategory("鲜食", null, 2, editorHeaders);
        long enabledRootId = number(enabledRoot.get("id"));
        long enabledLeafId = number(createCategory("牛肉", enabledRootId, 0, editorHeaders).get("id"));
        long mediaId = insertReadyMedia("catalog-editor");

        Map<String, Object> incomplete = createDraft(Map.of(), editorHeaders);
        ResponseEntity<String> rejectedPublish = rest.exchange(
                url("/api/v1/admin/products/" + number(incomplete.get("id")) + "/publish"),
                HttpMethod.POST,
                new HttpEntity<>(editorHeaders),
                String.class);
        assertThat(rejectedPublish.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> noImageRequest = productRequest(
                "缺少主图", enabledLeafId, List.of(),
                List.of(mapWithNullablePrice("500g", null)));
        Map<String, Object> noImage = createDraft(noImageRequest, editorHeaders);
        assertThat(rest.exchange(
                url("/api/v1/admin/products/" + number(noImage.get("id")) + "/publish"),
                HttpMethod.POST,
                new HttpEntity<>(editorHeaders),
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> noSpecRequest = productRequest(
                "缺少规格", enabledLeafId, List.of(), List.of());
        noSpecRequest.put("mainImageMediaId", mediaId);
        Map<String, Object> noSpec = createDraft(noSpecRequest, editorHeaders);
        assertThat(rest.exchange(
                url("/api/v1/admin/products/" + number(noSpec.get("id")) + "/publish"),
                HttpMethod.POST,
                new HttpEntity<>(editorHeaders),
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> disabledLeafRequest = productRequest(
                "停用分类产品", enabledLeafId, List.of(),
                List.of(mapWithNullablePrice("500g", null)));
        disabledLeafRequest.put("mainImageMediaId", mediaId);
        Map<String, Object> disabledLeafProduct = createDraft(disabledLeafRequest, editorHeaders);
        rest.exchange(
                url("/api/v1/admin/categories/" + enabledLeafId + "/status"),
                HttpMethod.PATCH,
                jsonEntity(Map.of("active", false), editorHeaders),
                String.class);
        assertThat(rest.exchange(
                url("/api/v1/admin/products/" + number(disabledLeafProduct.get("id")) + "/publish"),
                HttpMethod.POST,
                new HttpEntity<>(editorHeaders),
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        rest.exchange(
                url("/api/v1/admin/categories/" + enabledLeafId + "/status"),
                HttpMethod.PATCH,
                jsonEntity(Map.of("active", true), editorHeaders),
                String.class);

        Map<String, Object> publishRequest = productRequest(
                "牛腩", enabledLeafId, List.of("牛腩"),
                List.of(Map.of("name", "500g", "referencePrice", 25.50),
                        mapWithNullablePrice("1kg", null)));
        publishRequest.put("mainImageMediaId", mediaId);
        publishRequest.put("galleryMediaIds", List.of(mediaId));
        Map<String, Object> readyDraft = createDraft(publishRequest, editorHeaders);
        long publishedProductId = number(readyDraft.get("id"));
        ResponseEntity<Map<String, Object>> published = rest.exchange(
                url("/api/v1/admin/products/" + publishedProductId + "/publish"),
                HttpMethod.POST,
                new HttpEntity<>(editorHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getData(published)).containsEntry("status", "PUBLISHED");
        assertAuditRecorded("PRODUCT_PUBLISHED", "products", publishedProductId);

        ResponseEntity<Map<String, Object>> publicCategories = rest.exchange(
                url("/api/v1/categories"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(publicCategories.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicCategories.getBody()).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visibleRoots =
                (List<Map<String, Object>>) publicCategories.getBody().get("data");
        Map<String, Object> visibleRootResult = visibleRoots.stream()
                .filter(categoryEntry -> number(categoryEntry.get("id")) == enabledRootId)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visibleChildren =
                (List<Map<String, Object>>) visibleRootResult.get("children");
        assertThat(visibleChildren)
                .anyMatch(childCategory -> number(childCategory.get("id")) == enabledLeafId);
        ResponseEntity<Map<String, Object>> publicProducts = rest.exchange(
                url("/api/v1/categories/" + enabledLeafId + "/products"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(publicProducts.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> rootProducts = rest.exchange(
                url("/api/v1/categories/" + enabledRootId + "/products"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(pageItems(rootProducts)).hasSize(1);
        Map<String, Object> publicProduct = pageItems(publicProducts).getFirst();
        assertThat(number(publicProduct.get("id"))).isEqualTo(publishedProductId);
        assertThat(publicProduct).containsEntry("mainImageUrl", "/api/v1/media/" + mediaId)
                .containsEntry("referencePriceNotice", "参考价仅供参考，实际价格以客服沟通为准");
        List<Map<String, Object>> publicSpecs =
                (List<Map<String, Object>>) publicProduct.get("specifications");
        assertThat(publicSpecs).extracting(spec -> spec.get("priceDisplay"))
                .containsExactly("¥25.50", "面议");

        ResponseEntity<Map<String, Object>> publicDetail = rest.exchange(
                url("/api/v1/products/" + publishedProductId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(getData(publicDetail)).containsEntry("name", "牛腩")
                .containsEntry("galleryImageUrls", List.of("/api/v1/media/" + mediaId));

        Map<String, Object> editPublished = productRequest(
                "牛腩（更新）", enabledLeafId, List.of("牛腩"),
                List.of(mapWithNullablePrice("500g", null)));
        editPublished.put("mainImageMediaId", mediaId);
        ResponseEntity<Map<String, Object>> savedPublished = rest.exchange(
                url("/api/v1/admin/products/" + publishedProductId),
                HttpMethod.PUT,
                jsonEntity(editPublished, editorHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(savedPublished.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> updatedPublicDetail = rest.exchange(
                url("/api/v1/products/" + publishedProductId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat(getData(updatedPublicDetail)).containsEntry("name", "牛腩（更新）");

        ResponseEntity<String> editorGlobalContact = rest.exchange(
                url("/api/v1/admin/contact/global"),
                HttpMethod.PUT,
                jsonEntity(Map.of("wechatId", "editor-must-not-change"), editorHeaders),
                String.class);
        assertThat(editorGlobalContact.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange(
                url("/api/v1/admin/contact/global"),
                HttpMethod.GET,
                new HttpEntity<>(editorHeaders),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders rootAdminHeaders = authenticatedHeaders(
                login("root-admin", "A-strong-test-password-123!", csrf), csrf);
        ResponseEntity<Map<String, Object>> initialGlobalContact = rest.exchange(
                url("/api/v1/admin/contact/global"),
                HttpMethod.PUT,
                jsonEntity(Map.of("wechatId", "global-one", "qrMediaId", mediaId), rootAdminHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(initialGlobalContact.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertAuditRecorded("GLOBAL_CONTACT_UPDATED", "global_contact_configs",
                number(getData(initialGlobalContact).get("id")));
        assertThat(getData(rest.exchange(
                url("/api/v1/admin/contact/global"),
                HttpMethod.GET,
                new HttpEntity<>(rootAdminHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {})))
                .containsEntry("wechatId", "global-one");
        Map<String, Object> initialPublicDetail = getData(rest.exchange(
                url("/api/v1/products/" + publishedProductId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<Map<String, Object>>() {}));
        assertThat(asMap(initialPublicDetail.get("contact")))
                .containsEntry("wechatId", "global-one")
                .containsEntry("qrImageUrl", "/api/v1/media/" + mediaId);

        long prefixLowSortId = createAndPublishProduct(
                "牛腩卷", enabledLeafId, List.of("牛腩卷别名"), 2, mediaId, editorHeaders);
        long prefixHighSortId = createAndPublishProduct(
                "牛腩片", enabledLeafId, List.of("牛腩片别名"), 9, mediaId, editorHeaders);
        long aliasId = createAndPublishProduct(
                "安格斯牛肉", enabledLeafId, List.of("牛腩"), 1, mediaId, editorHeaders);
        long exactNameId = createAndPublishProduct(
                "牛腩", enabledLeafId, List.of(), 50, mediaId, editorHeaders);
        Map<String, Object> otherRoot = createCategory("冷冻食材", null, 3, editorHeaders);
        long otherLeafId = number(createCategory(
                "牛肉", number(otherRoot.get("id")), 0, editorHeaders).get("id"));
        createAndPublishProduct(
                "进口牛肉", otherLeafId, List.of("牛腩"), 0, mediaId, editorHeaders);

        ResponseEntity<Map<String, Object>> searchResults = rest.exchange(
                url("/api/v1/products/search?q=牛腩"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        List<Map<String, Object>> matchedProducts = pageItems(searchResults);
        assertThat(number(matchedProducts.getFirst().get("id"))).isEqualTo(exactNameId);
        assertThat(matchedProducts).extracting(product -> number(product.get("id")))
                .containsSubsequence(prefixLowSortId, publishedProductId, prefixHighSortId, aliasId);

        ResponseEntity<Map<String, Object>> parentFilteredSearch = rest.exchange(
                url("/api/v1/products/search?q=牛腩&categoryId=" + enabledRootId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        List<Map<String, Object>> parentMatches = pageItems(parentFilteredSearch);
        assertThat(parentMatches).extracting(product -> number(product.get("id")))
                .doesNotContain(matchedProducts.stream()
                        .filter(product -> "进口牛肉".equals(product.get("name")))
                        .map(product -> number(product.get("id")))
                        .findFirst()
                        .orElseThrow());
        assertThat(parentMatches).extracting(product -> number(product.get("id")))
                .contains(aliasId);

        ResponseEntity<String> emptyGlobalContact = rest.exchange(
                url("/api/v1/admin/contact/global"),
                HttpMethod.PUT,
                jsonEntity(Map.of("wechatId", " "), rootAdminHeaders),
                String.class);
        assertThat(emptyGlobalContact.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> invalidQrContact = rest.exchange(
                url("/api/v1/admin/contact/global"),
                HttpMethod.PUT,
                jsonEntity(Map.of("qrMediaId", Long.MAX_VALUE), rootAdminHeaders),
                String.class);
        assertThat(invalidQrContact.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> invalidOverrideRequest = productRequest(
                "牛腩（更新）", enabledLeafId, List.of("牛腩"),
                List.of(mapWithNullablePrice("500g", null)));
        invalidOverrideRequest.put("contactMode", "OVERRIDE");
        ResponseEntity<String> invalidProductOverride = rest.exchange(
                url("/api/v1/admin/products/" + publishedProductId),
                HttpMethod.PUT,
                jsonEntity(invalidOverrideRequest, editorHeaders),
                String.class);
        assertThat(invalidProductOverride.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        rest.exchange(
                url("/api/v1/admin/contact/global"),
                HttpMethod.PUT,
                jsonEntity(Map.of("wechatId", "global-two"), rootAdminHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        Map<String, Object> updatedInheritedOriginal = getData(rest.exchange(
                url("/api/v1/products/" + publishedProductId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<Map<String, Object>>() {}));
        assertThat(asMap(updatedInheritedOriginal.get("contact")))
                .containsEntry("wechatId", "global-two")
                .doesNotContainKey("qrImageUrl");
        Map<String, Object> inheritedAfterUpdate = getData(rest.exchange(
                url("/api/v1/products/" + aliasId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<Map<String, Object>>() {}));
        assertThat(asMap(inheritedAfterUpdate.get("contact")))
                .containsEntry("wechatId", "global-two")
                .doesNotContainKey("qrImageUrl");

        Map<String, Object> productOverride = productRequest(
                "牛腩（更新）", enabledLeafId, List.of("牛腩"),
                List.of(mapWithNullablePrice("500g", null)));
        productOverride.put("mainImageMediaId", mediaId);
        productOverride.put("contactMode", "OVERRIDE");
        productOverride.put("contactWechatId", "product-only");
        ResponseEntity<Map<String, Object>> savedOverride = rest.exchange(
                url("/api/v1/admin/products/" + publishedProductId),
                HttpMethod.PUT,
                jsonEntity(productOverride, editorHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(savedOverride.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertAuditRecorded("PRODUCT_UPDATED", "products", publishedProductId);
        rest.exchange(
                url("/api/v1/admin/contact/global"),
                HttpMethod.PUT,
                jsonEntity(Map.of("wechatId", "global-three", "qrMediaId", mediaId), rootAdminHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        Map<String, Object> overriddenDetail = getData(rest.exchange(
                url("/api/v1/products/" + publishedProductId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<Map<String, Object>>() {}));
        assertThat(asMap(overriddenDetail.get("contact")))
                .containsEntry("wechatId", "product-only")
                .doesNotContainKey("qrImageUrl");
        Map<String, Object> inheritedDetail = getData(rest.exchange(
                url("/api/v1/products/" + aliasId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<Map<String, Object>>() {}));
        assertThat(asMap(inheritedDetail.get("contact")))
                .containsEntry("wechatId", "global-three")
                .containsEntry("qrImageUrl", "/api/v1/media/" + mediaId);

        Map<String, Object> disabledContactRequest = productRequest(
                "安格斯牛肉", enabledLeafId, List.of("牛腩"),
                List.of(mapWithNullablePrice("500g", null)));
        disabledContactRequest.put("mainImageMediaId", mediaId);
        disabledContactRequest.put("contactMode", "DISABLED");
        ResponseEntity<Map<String, Object>> disabledContact = rest.exchange(
                url("/api/v1/admin/products/" + aliasId),
                HttpMethod.PUT,
                jsonEntity(disabledContactRequest, editorHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(disabledContact.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> disabledDetail = getData(rest.exchange(
                url("/api/v1/products/" + aliasId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<Map<String, Object>>() {}));
        assertThat(disabledDetail).doesNotContainKey("contact");

        ResponseEntity<String> hideTree = rest.exchange(
                url("/api/v1/admin/categories/" + enabledRootId + "/status"),
                HttpMethod.PATCH,
                jsonEntity(Map.of("active", false), editorHeaders),
                String.class);
        assertThat(hideTree.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map<String, Object>> hiddenCategories = rest.exchange(
                url("/api/v1/categories"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        assertThat((List<?>) hiddenCategories.getBody().get("data"))
                .noneMatch(category -> number(((Map<?, ?>) category).get("id")) == enabledRootId);
        ResponseEntity<String> hiddenProduct = rest.exchange(
                url("/api/v1/products/" + publishedProductId),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                String.class);
        assertThat(hiddenProduct.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map<String, Object>> hiddenTreeSearch = rest.exchange(
                url("/api/v1/products/search?q=牛腩"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
        List<Map<String, Object>> hiddenTreeMatches = pageItems(hiddenTreeSearch);
        assertThat(hiddenTreeMatches).extracting(product -> number(product.get("id")))
                .doesNotContain(publishedProductId, prefixLowSortId, prefixHighSortId,
                        aliasId, exactNameId);
        ResponseEntity<Map<String, Object>> stillPublished = rest.exchange(
                url("/api/v1/admin/products/" + publishedProductId),
                HttpMethod.GET,
                new HttpEntity<>(editorHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(getData(stillPublished)).containsEntry("status", "PUBLISHED");
        Map<String, Object> stillEnabledLeaf = getData(rest.exchange(
                url("/api/v1/admin/categories/" + enabledLeafId),
                HttpMethod.GET,
                new HttpEntity<>(editorHeaders),
                new ParameterizedTypeReference<Map<String, Object>>() {}));
        assertThat(stillEnabledLeaf).containsEntry("active", true);
        rest.exchange(
                url("/api/v1/admin/categories/" + enabledRootId + "/status"),
                HttpMethod.PATCH,
                jsonEntity(Map.of("active", true), editorHeaders),
                String.class);

        ResponseEntity<Map<String, Object>> unpublished = rest.exchange(
                url("/api/v1/admin/products/" + publishedProductId + "/unpublish"),
                HttpMethod.POST,
                new HttpEntity<>(editorHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(getData(unpublished)).containsEntry("status", "UNPUBLISHED");
        assertAuditRecorded("PRODUCT_UNPUBLISHED", "products", publishedProductId);
        assertThat(rest.getForEntity(
                url("/api/v1/products/" + publishedProductId), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> editorArchive = rest.exchange(
                url("/api/v1/admin/products/" + publishedProductId + "/archive"),
                HttpMethod.POST,
                new HttpEntity<>(editorHeaders),
                String.class);
        assertThat(editorArchive.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map<String, Object>> republished = rest.exchange(
                url("/api/v1/admin/products/" + publishedProductId + "/publish"),
                HttpMethod.POST,
                new HttpEntity<>(editorHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(getData(republished)).containsEntry("status", "PUBLISHED");
        ResponseEntity<Map<String, Object>> archived = rest.exchange(
                url("/api/v1/admin/products/" + publishedProductId + "/archive"),
                HttpMethod.POST,
                new HttpEntity<>(rootAdminHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(getData(archived)).containsEntry("status", "ARCHIVED");
        assertAuditRecorded("PRODUCT_ARCHIVED", "products", publishedProductId);
        ResponseEntity<Map<String, Object>> restored = rest.exchange(
                url("/api/v1/admin/products/" + publishedProductId + "/restore"),
                HttpMethod.POST,
                new HttpEntity<>(rootAdminHeaders),
                new ParameterizedTypeReference<>() {});
        assertThat(getData(restored)).containsEntry("status", "UNPUBLISHED");
        assertAuditRecorded("PRODUCT_RESTORED", "products", publishedProductId);
        assertThat(rest.getForEntity(
                url("/api/v1/products/" + publishedProductId), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Map<String, Object> createCategory(
            String name, Long parentId, int sortOrder, HttpHeaders headers) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/admin/categories"),
                HttpMethod.POST,
                jsonEntity(categoryRequest(name, parentId, sortOrder), headers),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return getData(response);
    }

    private Map<String, Object> createDraft(Map<String, Object> request, HttpHeaders headers) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/admin/products"),
                HttpMethod.POST,
                jsonEntity(request, headers),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return getData(response);
    }

    private long createAndPublishProduct(
            String name,
            long categoryId,
            List<String> keywords,
            int sortOrder,
            long mediaId,
            HttpHeaders headers) {
        Map<String, Object> request = productRequest(
                name,
                categoryId,
                keywords,
                List.of(mapWithNullablePrice("500g", null)));
        request.put("sortOrder", sortOrder);
        request.put("mainImageMediaId", mediaId);
        long productId = number(createDraft(request, headers).get("id"));
        ResponseEntity<Map<String, Object>> published = rest.exchange(
                url("/api/v1/admin/products/" + productId + "/publish"),
                HttpMethod.POST,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getData(published)).containsEntry("status", "PUBLISHED");
        return productId;
    }

    private Map<String, Object> categoryRequest(String name, Long parentId, int sortOrder) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", name);
        request.put("parentId", parentId);
        request.put("sortOrder", sortOrder);
        return request;
    }

    private Map<String, Object> productRequest(
            String name, Long categoryId, List<String> keywords, List<Map<String, Object>> specs) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", name);
        request.put("categoryId", categoryId);
        request.put("detailText", "适合炖煮");
        request.put("keywords", keywords);
        request.put("sortOrder", 5);
        request.put("specifications", specs);
        return request;
    }

    private Map<String, Object> mapWithNullablePrice(String name, Object price) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", name);
        spec.put("referencePrice", price);
        return spec;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pageItems(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).containsEntry("success", true);
        Map<String, Object> page = (Map<String, Object>) response.getBody().get("data");
        return (List<Map<String, Object>>) page.get("items");
    }

    private Map<String, Object> getData(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return data;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }

    private void assertAuditRecorded(String action, String targetType, long targetId)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM audit_logs
                     WHERE action = ? AND target_type = ? AND target_id = ?
                       AND actor_admin_user_id IS NOT NULL
                     """)) {
            statement.setString(1, action);
            statement.setString(2, targetType);
            statement.setString(3, Long.toString(targetId));
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isGreaterThan(0);
            }
        }
    }

    private long insertReadyMedia(String username) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO media_resources (
                         object_key, media_type, byte_size, resource_status,
                         public_url, uploaded_by, is_deleted
                     )
                     SELECT ?, 'image/jpeg', 1, 'READY', ?, id, FALSE
                     FROM admin_users WHERE username = ?
                     """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "test/catalog-" + System.nanoTime() + ".jpg");
            statement.setString(2, "/api/v1/media/test");
            statement.setString(3, username);
            assertThat(statement.executeUpdate()).isEqualTo(1);
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private String login(String username, String password, CsrfCookie csrf) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/admin/auth/login"),
                HttpMethod.POST,
                jsonEntity(Map.of("username", username, "password", password),
                        csrfHeaders(csrf)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return cookieValue(response.getHeaders(), "ADMIN_SESSION");
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
