package dev.mattest.supplychain.vo.catalog;

import java.util.List;

public record PublicProductSummaryVO(
        long id,
        String name,
        long categoryId,
        String mainImageUrl,
        List<PublicProductSpecVO> specifications,
        String referencePriceNotice) {
}
