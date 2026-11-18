package dev.mattest.supplychain.vo.catalog;

import java.util.List;

public record PublicCategoryVO(
        long id,
        String name,
        int sortOrder,
        List<PublicCategoryVO> children) {
}
