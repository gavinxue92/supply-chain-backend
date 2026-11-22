package dev.mattest.supplychain.vo.catalog;

import java.util.List;

public record BootstrapCategoryVO(
        long id,
        String name,
        int sortOrder,
        List<PublicProductSummaryVO> products) {
}
