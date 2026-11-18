package dev.mattest.supplychain.dto.catalog;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductSpecRequest(
        @NotBlank @Size(max = 120) String name,
        @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal referencePrice) {
}
