package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.common.Result;
import dev.mattest.supplychain.service.PublicCatalogService;
import dev.mattest.supplychain.vo.catalog.PublicCategoryVO;
import dev.mattest.supplychain.vo.catalog.PublicProductDetailVO;
import dev.mattest.supplychain.vo.catalog.PublicProductSummaryVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PublicCatalogController {
    private final PublicCatalogService publicCatalogService;

    public PublicCatalogController(PublicCatalogService publicCatalogService) {
        this.publicCatalogService = publicCatalogService;
    }

    @GetMapping("/categories")
    public Result<List<PublicCategoryVO>> listCategories() {
        return Result.success(publicCatalogService.listCategories());
    }

    @GetMapping("/categories/{id}/products")
    public Result<List<PublicProductSummaryVO>> listProducts(@PathVariable long id) {
        return Result.success(publicCatalogService.listProducts(id));
    }

    @GetMapping("/products/{id}")
    public Result<PublicProductDetailVO> getProduct(@PathVariable long id) {
        return Result.success(publicCatalogService.getProduct(id));
    }
}
