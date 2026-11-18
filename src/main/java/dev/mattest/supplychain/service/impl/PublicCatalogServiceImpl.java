package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.mattest.supplychain.entity.Category;
import dev.mattest.supplychain.entity.GlobalContactConfig;
import dev.mattest.supplychain.entity.Product;
import dev.mattest.supplychain.entity.ProductGallery;
import dev.mattest.supplychain.entity.ProductKeyword;
import dev.mattest.supplychain.entity.ProductSpec;
import dev.mattest.supplychain.mapper.CategoryMapper;
import dev.mattest.supplychain.mapper.GlobalContactConfigMapper;
import dev.mattest.supplychain.mapper.ProductGalleryMapper;
import dev.mattest.supplychain.mapper.ProductKeywordMapper;
import dev.mattest.supplychain.mapper.ProductMapper;
import dev.mattest.supplychain.mapper.ProductSpecMapper;
import dev.mattest.supplychain.service.PublicCatalogService;
import dev.mattest.supplychain.vo.catalog.PublicCategoryVO;
import dev.mattest.supplychain.vo.catalog.ContactDisplayVO;
import dev.mattest.supplychain.vo.catalog.PublicProductDetailVO;
import dev.mattest.supplychain.vo.catalog.PublicProductSpecVO;
import dev.mattest.supplychain.vo.catalog.PublicProductSummaryVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PublicCatalogServiceImpl implements PublicCatalogService {
    private static final String PRICE_NOTICE =
            "参考价仅供参考，实际价格以客服沟通为准";

    private final CategoryMapper categoryMapper;
    private final ProductMapper productMapper;
    private final ProductKeywordMapper keywordMapper;
    private final ProductSpecMapper specMapper;
    private final ProductGalleryMapper galleryMapper;
    private final GlobalContactConfigMapper globalContactConfigMapper;

    public PublicCatalogServiceImpl(
            CategoryMapper categoryMapper,
            ProductMapper productMapper,
            ProductKeywordMapper keywordMapper,
            ProductSpecMapper specMapper,
            ProductGalleryMapper galleryMapper,
            GlobalContactConfigMapper globalContactConfigMapper) {
        this.categoryMapper = categoryMapper;
        this.productMapper = productMapper;
        this.keywordMapper = keywordMapper;
        this.specMapper = specMapper;
        this.galleryMapper = galleryMapper;
        this.globalContactConfigMapper = globalContactConfigMapper;
    }

    @Override
    public List<PublicCategoryVO> listCategories() {
        List<Category> roots = categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .isNull(Category::getParentId)
                .eq(Category::getIsActive, true)
                .orderByAsc(Category::getSortOrder)
                .orderByAsc(Category::getId));
        return roots.stream()
                .map(root -> new PublicCategoryVO(
                        root.getId(),
                        root.getName(),
                        root.getSortOrder(),
                        childrenOf(root.getId())))
                .toList();
    }

    @Override
    public List<PublicProductSummaryVO> listProducts(long categoryId) {
        List<Long> visibleCategoryIds = visibleCategoryIds(categoryId);
        if (visibleCategoryIds.isEmpty()) {
            return List.of();
        }
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .eq(Product::getStatus, "PUBLISHED")
                        .eq(Product::getIsDeleted, false)
                        .in(Product::getCategoryId, visibleCategoryIds)
                        .orderByAsc(Product::getSortOrder)
                        .orderByAsc(Product::getId))
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public List<PublicProductSummaryVO> searchProducts(String query, Long categoryId) {
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        Set<Long> visibleCategoryIds = categoryId == null
                ? allVisibleCategoryIds()
                : new HashSet<>(visibleCategoryIds(categoryId));
        if (visibleCategoryIds.isEmpty()) {
            return List.of();
        }

        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, "PUBLISHED")
                .eq(Product::getIsDeleted, false));
        if (products.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> keywordsByProduct = keywordMapper.selectList(
                        new LambdaQueryWrapper<ProductKeyword>()
                                .in(ProductKeyword::getProductId,
                                        products.stream().map(Product::getId).toList()))
                .stream()
                .collect(Collectors.groupingBy(
                        ProductKeyword::getProductId,
                        Collectors.mapping(ProductKeyword::getKeyword, Collectors.toList())));

        return products.stream()
                .filter(product -> visibleCategoryIds.contains(product.getCategoryId()))
                .map(product -> new SearchResult(
                        product, matchRank(product, keywordsByProduct.get(product.getId()), normalizedQuery)))
                .filter(result -> result.matchRank() < Integer.MAX_VALUE)
                .sorted(Comparator.comparingInt(SearchResult::matchRank)
                        .thenComparingInt(result -> result.product().getSortOrder() == null
                                ? 0
                                : result.product().getSortOrder())
                        .thenComparingLong(result -> result.product().getId()))
                .map(result -> toSummary(result.product()))
                .toList();
    }

    @Override
    public PublicProductDetailVO getProduct(long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null || !"PUBLISHED".equals(product.getStatus())
                || Boolean.TRUE.equals(product.getIsDeleted())) {
            throw notFound();
        }
        Category category = categoryMapper.selectById(product.getCategoryId());
        if (category == null || !Boolean.TRUE.equals(category.getIsActive())
                || category.getParentId() == null) {
            throw notFound();
        }
        if (!hasVisibleParent(category)) {
            throw notFound();
        }
        List<String> galleryUrls = galleryMapper.selectList(
                        new LambdaQueryWrapper<ProductGallery>()
                                .eq(ProductGallery::getProductId, productId)
                                .orderByAsc(ProductGallery::getSortOrder)
                                .orderByAsc(ProductGallery::getId))
                .stream()
                .map(image -> mediaUrl(image.getMediaId()))
                .toList();
        return new PublicProductDetailVO(
                product.getId(),
                product.getName(),
                product.getCategoryId(),
                mediaUrl(product.getMainImageMediaId()),
                galleryUrls,
                product.getDetailHtml(),
                specifications(productId),
                PRICE_NOTICE,
                resolveContact(product));
    }

    private List<PublicCategoryVO> childrenOf(long parentId) {
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                        .eq(Category::getParentId, parentId)
                        .eq(Category::getIsActive, true)
                        .orderByAsc(Category::getSortOrder)
                        .orderByAsc(Category::getId))
                .stream()
                .map(child -> new PublicCategoryVO(
                        child.getId(), child.getName(), child.getSortOrder(), List.of()))
                .toList();
    }

    private PublicProductSummaryVO toSummary(Product product) {
        return new PublicProductSummaryVO(
                product.getId(),
                product.getName(),
                product.getCategoryId(),
                mediaUrl(product.getMainImageMediaId()),
                specifications(product.getId()),
                PRICE_NOTICE);
    }

    private List<PublicProductSpecVO> specifications(long productId) {
        return specMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                        .eq(ProductSpec::getProductId, productId)
                        .orderByAsc(ProductSpec::getSortOrder)
                        .orderByAsc(ProductSpec::getId))
                .stream()
                .map(spec -> new PublicProductSpecVO(
                        spec.getName(),
                        spec.getReferencePrice(),
                        displayPrice(spec.getReferencePrice())))
                .toList();
    }

    private String displayPrice(BigDecimal price) {
        return price == null ? "面议" : "¥" + price.setScale(2).toPlainString();
    }

    private String mediaUrl(Long mediaId) {
        if (mediaId == null) {
            throw new IllegalStateException("Published product media reference is missing");
        }
        return "/api/v1/media/" + mediaId;
    }

    private Category findCategory(long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null || !Boolean.TRUE.equals(category.getIsActive())) {
            throw notFound();
        }
        return category;
    }

    private boolean hasVisibleParent(Category category) {
        Category parent = category.getParentId() == null
                ? null
                : categoryMapper.selectById(category.getParentId());
        return parent != null && parent.getParentId() == null
                && Boolean.TRUE.equals(parent.getIsActive());
    }

    private List<Long> visibleCategoryIds(long categoryId) {
        Category category = findCategory(categoryId);
        if (category.getParentId() == null) {
            return childrenOf(categoryId).stream()
                    .map(PublicCategoryVO::id)
                    .toList();
        }
        if (!hasVisibleParent(category)) {
            throw notFound();
        }
        return List.of(categoryId);
    }

    private Set<Long> allVisibleCategoryIds() {
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                        .isNull(Category::getParentId)
                        .eq(Category::getIsActive, true))
                .stream()
                .flatMap(root -> childrenOf(root.getId()).stream())
                .map(PublicCategoryVO::id)
                .collect(Collectors.toSet());
    }

    private int matchRank(Product product, List<String> keywords, String query) {
        String name = product.getName().toLowerCase(Locale.ROOT);
        if (name.equals(query)) {
            return 0;
        }
        if (name.startsWith(query)) {
            return 1;
        }
        if (keywords != null && keywords.stream()
                .anyMatch(keyword -> keyword.toLowerCase(Locale.ROOT).contains(query))) {
            return 2;
        }
        return name.contains(query) ? 3 : Integer.MAX_VALUE;
    }

    private ContactDisplayVO resolveContact(Product product) {
        String mode = product.getContactMode() == null ? "INHERIT" : product.getContactMode();
        if ("DISABLED".equals(mode)) {
            return null;
        }
        if ("OVERRIDE".equals(mode)) {
            return contactDisplay(product.getContactWechatId(), product.getContactQrMediaId());
        }
        if (!"INHERIT".equals(mode)) {
            throw new IllegalStateException("Product has an unsupported customer-service strategy");
        }
        GlobalContactConfig config = globalContactConfigMapper.selectOne(
                new LambdaQueryWrapper<GlobalContactConfig>()
                        .eq(GlobalContactConfig::getConfigKey, "GLOBAL"));
        return config == null ? null : contactDisplay(config.getWechatId(), config.getQrMediaId());
    }

    private ContactDisplayVO contactDisplay(String wechatId, Long qrMediaId) {
        return new ContactDisplayVO(
                wechatId,
                qrMediaId == null ? null : mediaUrl(qrMediaId));
    }

    private record SearchResult(Product product, int matchRank) {
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog resource not found");
    }
}
