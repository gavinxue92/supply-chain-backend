package dev.mattest.supplychain.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ProductSearchRequest(
        @NotBlank @Size(max = 120) String q,
        @Positive Long categoryId,
        Integer page,
        Integer pageSize,
        String sort) {

    public ProductPageRequest pageRequest() {
        return new ProductPageRequest(page, pageSize, sort);
    }
}
