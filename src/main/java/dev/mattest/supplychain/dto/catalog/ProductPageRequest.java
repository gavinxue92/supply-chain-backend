package dev.mattest.supplychain.dto.catalog;

public record ProductPageRequest(Integer page, Integer pageSize, String sort) {
    public int resolvedPage() {
        return page == null || page < 1 ? 1 : page;
    }

    public int resolvedPageSize() {
        return pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 50);
    }

    public String resolvedSort() {
        return sort == null || sort.isBlank() ? "default" : sort;
    }
}
