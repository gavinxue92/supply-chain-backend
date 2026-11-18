package dev.mattest.supplychain.service;

import dev.mattest.supplychain.vo.catalog.PublicCategoryVO;
import dev.mattest.supplychain.vo.catalog.PublicProductDetailVO;
import dev.mattest.supplychain.vo.catalog.PublicProductSummaryVO;

import java.util.List;

public interface PublicCatalogService {
    List<PublicCategoryVO> listCategories();
    List<PublicProductSummaryVO> listProducts(long categoryId);
    PublicProductDetailVO getProduct(long productId);
}
