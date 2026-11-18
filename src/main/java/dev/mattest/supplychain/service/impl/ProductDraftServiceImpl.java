package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.mattest.supplychain.dto.catalog.ProductDraftRequest;
import dev.mattest.supplychain.dto.catalog.ProductSpecRequest;
import dev.mattest.supplychain.entity.Category;
import dev.mattest.supplychain.entity.MediaResource;
import dev.mattest.supplychain.entity.Product;
import dev.mattest.supplychain.entity.ProductGallery;
import dev.mattest.supplychain.entity.ProductKeyword;
import dev.mattest.supplychain.entity.ProductSpec;
import dev.mattest.supplychain.mapper.CategoryMapper;
import dev.mattest.supplychain.mapper.MediaResourceMapper;
import dev.mattest.supplychain.mapper.ProductGalleryMapper;
import dev.mattest.supplychain.mapper.ProductKeywordMapper;
import dev.mattest.supplychain.mapper.ProductMapper;
import dev.mattest.supplychain.mapper.ProductSpecMapper;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.ProductDraftService;
import dev.mattest.supplychain.vo.catalog.ProductDraftVO;
import dev.mattest.supplychain.vo.catalog.ProductSpecVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class ProductDraftServiceImpl implements ProductDraftService {
    private final ProductMapper productMapper;
    private final ProductSpecMapper specMapper;
    private final ProductKeywordMapper keywordMapper;
    private final CategoryMapper categoryMapper;
    private final MediaResourceMapper mediaResourceMapper;
    private final ProductGalleryMapper galleryMapper;
    private final ProductDetailSanitizer detailSanitizer;
    private final AuditLogWriter auditLogWriter;

    public ProductDraftServiceImpl(
            ProductMapper productMapper,
            ProductSpecMapper specMapper,
            ProductKeywordMapper keywordMapper,
            CategoryMapper categoryMapper,
            MediaResourceMapper mediaResourceMapper,
            ProductGalleryMapper galleryMapper,
            ProductDetailSanitizer detailSanitizer,
            AuditLogWriter auditLogWriter) {
        this.productMapper = productMapper;
        this.specMapper = specMapper;
        this.keywordMapper = keywordMapper;
        this.categoryMapper = categoryMapper;
        this.mediaResourceMapper = mediaResourceMapper;
        this.galleryMapper = galleryMapper;
        this.detailSanitizer = detailSanitizer;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    public List<ProductDraftVO> list() {
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .orderByAsc(Product::getSortOrder)
                        .orderByAsc(Product::getStatus)
                        .orderByDesc(Product::getUpdatedAt)
                        .orderByAsc(Product::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public ProductDraftVO get(long id) {
        return toView(findProduct(id));
    }

    @Override
    @Transactional
    public ProductDraftVO create(AdminPrincipal actor, ProductDraftRequest request) {
        validateCategory(request.categoryId());
        ProductDetailSanitizer.SanitizedDetail detail = validateContentMedia(actor.id(), request);
        Product product = new Product();
        product.setName(normalizeOptionalText(request.name()));
        product.setStatus("DRAFT");
        product.setCategoryId(request.categoryId());
        product.setMainImageMediaId(request.mainImageMediaId());
        product.setDetailHtml(detail.html());
        applyContactStrategy(product, request);
        product.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        productMapper.insert(product);
        replaceSpecifications(product.getId(), request.specifications());
        replaceKeywords(product.getId(), request.keywords());
        replaceGallery(product.getId(), request.galleryMediaIds());
        auditLogWriter.write(actor.id(), "PRODUCT_DRAFT_CREATED", "products", product.getId(),
                auditSummary(product, request, detail));
        return toView(productMapper.selectById(product.getId()));
    }

    @Override
    @Transactional
    public ProductDraftVO update(AdminPrincipal actor, long id, ProductDraftRequest request) {
        Product product = findEditable(id);
        validateCategory(request.categoryId());
        ProductDetailSanitizer.SanitizedDetail detail = validateContentMedia(actor.id(), request);
        product.setName(normalizeOptionalText(request.name()));
        product.setCategoryId(request.categoryId());
        product.setMainImageMediaId(request.mainImageMediaId());
        product.setDetailHtml(detail.html());
        applyContactStrategy(product, request);
        product.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        productMapper.updateById(product);
        replaceSpecifications(id, request.specifications());
        replaceKeywords(id, request.keywords());
        replaceGallery(id, request.galleryMediaIds());
        if ("PUBLISHED".equals(product.getStatus())) {
            validatePublishable(product, request.specifications());
        }
        auditLogWriter.write(actor.id(),
                "DRAFT".equals(product.getStatus()) ? "PRODUCT_DRAFT_UPDATED" : "PRODUCT_UPDATED",
                "products", id,
                auditSummary(product, request, detail));
        return toView(productMapper.selectById(id));
    }

    @Override
    @Transactional
    public ProductDraftVO publish(AdminPrincipal actor, long id) {
        Product product = findEditable(id);
        if ("PUBLISHED".equals(product.getStatus())) {
            return toView(product);
        }
        if (!"DRAFT".equals(product.getStatus()) && !"UNPUBLISHED".equals(product.getStatus())) {
            throw conflict("Product cannot be published from its current status");
        }
        validatePublishable(product, null);
        product.setStatus("PUBLISHED");
        productMapper.updateById(product);
        auditLogWriter.write(actor.id(), "PRODUCT_PUBLISHED", "products", id,
                Map.of("name", product.getName()));
        return toView(productMapper.selectById(id));
    }

    @Override
    @Transactional
    public ProductDraftVO unpublish(AdminPrincipal actor, long id) {
        Product product = findEditable(id);
        if (!"PUBLISHED".equals(product.getStatus())) {
            throw conflict("Only published products can be unpublished");
        }
        product.setStatus("UNPUBLISHED");
        productMapper.updateById(product);
        auditLogWriter.write(actor.id(), "PRODUCT_UNPUBLISHED", "products", id,
                Map.of("name", product.getName()));
        return toView(productMapper.selectById(id));
    }

    @Override
    @Transactional
    public ProductDraftVO archive(AdminPrincipal actor, long id) {
        requireAdministrator(actor);
        Product product = findEditable(id);
        product.setStatus("ARCHIVED");
        product.setIsDeleted(true);
        productMapper.updateById(product);
        auditLogWriter.write(actor.id(), "PRODUCT_ARCHIVED", "products", id,
                Map.of("name", product.getName() == null ? "" : product.getName()));
        return toView(product);
    }

    @Override
    @Transactional
    public ProductDraftVO restore(AdminPrincipal actor, long id) {
        requireAdministrator(actor);
        Product product = findProduct(id);
        if (!"ARCHIVED".equals(product.getStatus()) || !Boolean.TRUE.equals(product.getIsDeleted())) {
            throw conflict("Only archived products can be restored");
        }
        product.setStatus("UNPUBLISHED");
        product.setIsDeleted(false);
        productMapper.updateById(product);
        auditLogWriter.write(actor.id(), "PRODUCT_RESTORED", "products", id,
                Map.of("name", product.getName() == null ? "" : product.getName(),
                        "status", "UNPUBLISHED"));
        return toView(productMapper.selectById(id));
    }

    private void validatePublishable(Product product, List<ProductSpecRequest> requestedSpecs) {
        if (product.getName() == null || product.getName().isBlank()) {
            throw badRequest("Product name is required before publishing");
        }
        Category category = product.getCategoryId() == null
                ? null
                : categoryMapper.selectById(product.getCategoryId());
        if (category == null || !Boolean.TRUE.equals(category.getIsActive())
                || categoryMapper.countChildrenIncludingDeleted(category.getId()) > 0) {
            throw badRequest("Product must belong to an enabled leaf category");
        }
        if (product.getMainImageMediaId() == null
                || mediaResourceMapper.selectCount(new LambdaQueryWrapper<MediaResource>()
                        .eq(MediaResource::getId, product.getMainImageMediaId())
                        .eq(MediaResource::getResourceStatus, "READY")) != 1) {
            throw badRequest("A confirmed main image is required before publishing");
        }
        List<ProductSpecRequest> specs = requestedSpecs;
        if (specs == null) {
            List<ProductSpec> storedSpecs = specMapper.selectList(
                    new LambdaQueryWrapper<ProductSpec>()
                            .eq(ProductSpec::getProductId, product.getId()));
            if (storedSpecs.isEmpty()) {
                throw badRequest("At least one specification is required before publishing");
            }
            for (ProductSpec spec : storedSpecs) {
                validateReferencePrice(spec.getReferencePrice());
            }
            return;
        }
        if (specs.isEmpty()) {
            throw badRequest("At least one specification is required before publishing");
        }
        for (ProductSpecRequest spec : specs) {
            validateReferencePrice(spec.referencePrice());
        }
    }

    private void validateReferencePrice(java.math.BigDecimal price) {
        if (price != null && (price.signum() <= 0 || price.scale() > 2)) {
            throw badRequest("Reference prices must be positive and precise to cents");
        }
    }

    private void requireAdministrator(AdminPrincipal actor) {
        if (!"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
    }

    private void validateCategory(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw badRequest("Category does not exist");
        }
        if (categoryMapper.countChildrenIncludingDeleted(categoryId) > 0) {
            throw badRequest("Products must belong to a leaf category");
        }
    }

    private void replaceSpecifications(long productId, List<ProductSpecRequest> specifications) {
        specMapper.delete(new LambdaQueryWrapper<ProductSpec>()
                .eq(ProductSpec::getProductId, productId));
        if (specifications == null) {
            return;
        }
        for (int index = 0; index < specifications.size(); index++) {
            ProductSpecRequest request = specifications.get(index);
            ProductSpec spec = new ProductSpec();
            spec.setProductId(productId);
            spec.setName(request.name().trim());
            spec.setReferencePrice(request.referencePrice());
            spec.setSortOrder(index);
            specMapper.insert(spec);
        }
    }

    private void replaceKeywords(long productId, List<String> keywords) {
        keywordMapper.delete(new LambdaQueryWrapper<ProductKeyword>()
                .eq(ProductKeyword::getProductId, productId));
        if (keywords == null) {
            return;
        }
        for (String value : keywords) {
            if (value == null || value.isBlank()) {
                throw badRequest("Keywords must not be blank");
            }
            ProductKeyword keyword = new ProductKeyword();
            keyword.setProductId(productId);
            keyword.setKeyword(value.trim());
            keywordMapper.insert(keyword);
        }
    }

    private void applyContactStrategy(Product product, ProductDraftRequest request) {
        String mode = request.contactMode() == null ? "INHERIT" : request.contactMode();
        String wechatId = normalizeContactText(request.contactWechatId());
        Long qrMediaId = request.contactQrMediaId();

        if ("OVERRIDE".equals(mode)) {
            if (wechatId == null && qrMediaId == null) {
                throw badRequest("Product contact override must provide a WeChat ID or QR code");
            }
            if (qrMediaId != null && mediaResourceMapper.selectCount(
                    new LambdaQueryWrapper<MediaResource>()
                            .eq(MediaResource::getId, qrMediaId)
                            .eq(MediaResource::getResourceStatus, "READY")) != 1) {
                throw badRequest("The product customer-service QR code must reference confirmed media");
            }
        } else if ("INHERIT".equals(mode) || "DISABLED".equals(mode)) {
            if (wechatId != null || qrMediaId != null) {
                throw badRequest("Only product contact overrides may contain contact details");
            }
        } else {
            throw badRequest("Unsupported product customer-service strategy");
        }

        product.setContactMode(mode);
        product.setContactWechatId("OVERRIDE".equals(mode) ? wechatId : null);
        product.setContactQrMediaId("OVERRIDE".equals(mode) ? qrMediaId : null);
    }

    private String normalizeContactText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private ProductDetailSanitizer.SanitizedDetail validateContentMedia(
            long actorId, ProductDraftRequest request) {
        ProductDetailSanitizer.SanitizedDetail detail =
                detailSanitizer.sanitize(request.detailText());
        List<Long> galleryMediaIds = request.galleryMediaIds() == null
                ? List.of()
                : request.galleryMediaIds();
        if (new LinkedHashSet<>(galleryMediaIds).size() != galleryMediaIds.size()) {
            throw badRequest("Gallery images must not contain duplicates");
        }

        Set<Long> referencedIds = new LinkedHashSet<>(detail.mediaIds());
        if (request.mainImageMediaId() != null) {
            referencedIds.add(request.mainImageMediaId());
        }
        referencedIds.addAll(galleryMediaIds);
        if (referencedIds.isEmpty()) {
            return detail;
        }

        long availableCount = mediaResourceMapper.selectCount(
                new LambdaQueryWrapper<MediaResource>()
                        .in(MediaResource::getId, referencedIds)
                        .eq(MediaResource::getUploadedBy, actorId)
                        .eq(MediaResource::getResourceStatus, "READY"));
        if (availableCount != referencedIds.size()) {
            throw badRequest("Products may only reference your confirmed media resources");
        }
        return detail;
    }

    private void replaceGallery(long productId, List<Long> mediaIds) {
        galleryMapper.delete(new LambdaQueryWrapper<ProductGallery>()
                .eq(ProductGallery::getProductId, productId));
        if (mediaIds == null) {
            return;
        }
        for (int index = 0; index < mediaIds.size(); index++) {
            ProductGallery image = new ProductGallery();
            image.setProductId(productId);
            image.setMediaId(mediaIds.get(index));
            image.setSortOrder(index);
            galleryMapper.insert(image);
        }
    }

    private Product findEditable(long id) {
        Product product = findProduct(id);
        if ("ARCHIVED".equals(product.getStatus()) || Boolean.TRUE.equals(product.getIsDeleted())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        return product;
    }

    private Product findProduct(long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        return product;
    }

    private ProductDraftVO toView(Product product) {
        List<ProductSpecVO> specifications = specMapper.selectList(
                        new LambdaQueryWrapper<ProductSpec>()
                                .eq(ProductSpec::getProductId, product.getId())
                                .orderByAsc(ProductSpec::getSortOrder)
                                .orderByAsc(ProductSpec::getId))
                .stream()
                .map(spec -> new ProductSpecVO(
                        spec.getId(), spec.getName(), spec.getReferencePrice(), spec.getSortOrder()))
                .toList();
        List<String> keywords = keywordMapper.selectList(
                        new LambdaQueryWrapper<ProductKeyword>()
                                .eq(ProductKeyword::getProductId, product.getId())
                                .orderByAsc(ProductKeyword::getId))
                .stream()
                .map(ProductKeyword::getKeyword)
                .toList();
        List<Long> galleryMediaIds = galleryMapper.selectList(
                        new LambdaQueryWrapper<ProductGallery>()
                                .eq(ProductGallery::getProductId, product.getId())
                                .orderByAsc(ProductGallery::getSortOrder))
                .stream()
                .map(ProductGallery::getMediaId)
                .toList();
        return new ProductDraftVO(
                product.getId(),
                product.getStatus(),
                product.getName(),
                product.getCategoryId(),
                product.getDetailHtml(),
                product.getMainImageMediaId(),
                galleryMediaIds,
                keywords,
                product.getContactMode(),
                product.getContactWechatId(),
                product.getContactQrMediaId(),
                product.getSortOrder(),
                specifications);
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 200) {
            throw badRequest("Product name must not exceed 200 characters");
        }
        return normalized;
    }

    private Map<String, Object> auditSummary(
            Product product,
            ProductDraftRequest request,
            ProductDetailSanitizer.SanitizedDetail detail) {
        return Map.ofEntries(
                Map.entry("name", product.getName() == null ? "" : product.getName()),
                Map.entry("categoryId", product.getCategoryId() == null ? "" : product.getCategoryId()),
                Map.entry("mainImageMediaId",
                        product.getMainImageMediaId() == null ? "" : product.getMainImageMediaId()),
                Map.entry("galleryImageCount",
                        request.galleryMediaIds() == null ? 0 : request.galleryMediaIds().size()),
                Map.entry("detailImageCount", detail.mediaIds().size()),
                Map.entry("keywordCount", request.keywords() == null ? 0 : request.keywords().size()),
                Map.entry("contactMode", product.getContactMode()),
                Map.entry("hasContactWechatId", product.getContactWechatId() != null),
                Map.entry("hasContactQrCode", product.getContactQrMediaId() != null),
                Map.entry("specificationCount",
                        request.specifications() == null ? 0 : request.specifications().size()),
                Map.entry("sortOrder", product.getSortOrder()));
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
