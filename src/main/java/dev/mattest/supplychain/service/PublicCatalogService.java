package dev.mattest.supplychain.service;

import dev.mattest.supplychain.dto.catalog.ProductPageRequest;
import dev.mattest.supplychain.vo.catalog.BootstrapCategoryVO;
import dev.mattest.supplychain.vo.catalog.ProductPageVO;
import dev.mattest.supplychain.vo.catalog.PublicCategoryVO;
import dev.mattest.supplychain.vo.catalog.PublicProductDetailVO;
import dev.mattest.supplychain.vo.catalog.PublicProductSummaryVO;

import java.util.List;

public interface PublicCatalogService {
    List<PublicCategoryVO> listCategories();
    ProductPageVO listProducts(long categoryId, ProductPageRequest pageRequest);
    ProductPageVO listAllProducts(ProductPageRequest pageRequest);
    ProductPageVO searchProducts(String query, Long categoryId, ProductPageRequest pageRequest);
    PublicProductDetailVO getProduct(long productId);
    List<PublicProductSummaryVO> listRecommended(int limit);
    List<BootstrapCategoryVO> bootstrap();
}
