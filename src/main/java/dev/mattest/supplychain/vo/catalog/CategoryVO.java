package dev.mattest.supplychain.vo.catalog;

public record CategoryVO(
        long id,
        Long parentId,
        String name,
        boolean active,
        int sortOrder) {
}
