package dev.mattest.supplychain.vo.catalog;

import java.math.BigDecimal;

public record PublicProductSpecVO(
        String name,
        BigDecimal referencePrice,
        String priceDisplay) {
}
