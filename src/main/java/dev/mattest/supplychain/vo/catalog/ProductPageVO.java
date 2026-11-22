package dev.mattest.supplychain.vo.catalog;

import java.util.List;

public record ProductPageVO(
        List<PublicProductSummaryVO> items,
        long total,
        int page,
        int pageSize) {
}
