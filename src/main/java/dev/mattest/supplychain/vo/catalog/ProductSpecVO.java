package dev.mattest.supplychain.vo.catalog;

import java.math.BigDecimal;

public record ProductSpecVO(long id, String name, BigDecimal referencePrice, int sortOrder) {
}
