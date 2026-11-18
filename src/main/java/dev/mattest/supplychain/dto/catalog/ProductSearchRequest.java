package dev.mattest.supplychain.dto.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ProductSearchRequest(
        @NotBlank @Size(max = 120) String q,
        @Positive Long categoryId) {
}
