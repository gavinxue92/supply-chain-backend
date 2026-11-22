package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.common.Result;
import dev.mattest.supplychain.dto.catalog.ProductPageRequest;
import dev.mattest.supplychain.dto.catalog.ProductSearchRequest;
import dev.mattest.supplychain.service.HotKeywordService;
import dev.mattest.supplychain.service.PublicCatalogService;
import dev.mattest.supplychain.service.ShopInfoService;
import dev.mattest.supplychain.vo.catalog.BootstrapCategoryVO;
import dev.mattest.supplychain.vo.catalog.ProductPageVO;
import dev.mattest.supplychain.vo.catalog.PublicCategoryVO;
import dev.mattest.supplychain.vo.catalog.PublicProductDetailVO;
import dev.mattest.supplychain.vo.catalog.PublicProductSummaryVO;
import dev.mattest.supplychain.vo.shop.ShopInfoVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PublicCatalogController {
    private static final int MAX_RECOMMENDED_LIMIT = 12;

    private final PublicCatalogService publicCatalogService;
    private final HotKeywordService hotKeywordService;
    private final ShopInfoService shopInfoService;

    public PublicCatalogController(
            PublicCatalogService publicCatalogService,
            HotKeywordService hotKeywordService,
            ShopInfoService shopInfoService) {
        this.publicCatalogService = publicCatalogService;
        this.hotKeywordService = hotKeywordService;
        this.shopInfoService = shopInfoService;
    }

    @GetMapping("/categories")
    public Result<List<PublicCategoryVO>> listCategories() {
        return Result.success(publicCatalogService.listCategories());
    }

    @GetMapping("/categories/{id}/products")
    public Result<ProductPageVO> listProducts(
            @PathVariable long id, @Valid @ModelAttribute ProductPageRequest pageRequest) {
        return Result.success(publicCatalogService.listProducts(id, pageRequest));
    }

    @GetMapping("/products")
    public Result<ProductPageVO> listAllProducts(@Valid @ModelAttribute ProductPageRequest pageRequest) {
        return Result.success(publicCatalogService.listAllProducts(pageRequest));
    }

    @GetMapping("/products/search")
    public Result<ProductPageVO> searchProducts(
            @Valid @ModelAttribute ProductSearchRequest request) {
        return Result.success(publicCatalogService.searchProducts(
                request.q(), request.categoryId(), request.pageRequest()));
    }

    @GetMapping("/products/{id}")
    public Result<PublicProductDetailVO> getProduct(@PathVariable long id) {
        return Result.success(publicCatalogService.getProduct(id));
    }

    @GetMapping("/products/recommended")
    public Result<List<PublicProductSummaryVO>> recommended(
            @RequestParam(defaultValue = "2") int limit) {
        return Result.success(publicCatalogService.listRecommended(
                Math.max(1, Math.min(limit, MAX_RECOMMENDED_LIMIT))));
    }

    @GetMapping("/search/hot-keywords")
    public Result<List<String>> hotKeywords() {
        return Result.success(hotKeywordService.listPublicKeywords());
    }

    @GetMapping("/catalog/bootstrap")
    public Result<List<BootstrapCategoryVO>> bootstrap() {
        return Result.success(publicCatalogService.bootstrap());
    }

    @GetMapping("/shop")
    public Result<ShopInfoVO> shop() {
        return Result.success(shopInfoService.getPublic());
    }
}
