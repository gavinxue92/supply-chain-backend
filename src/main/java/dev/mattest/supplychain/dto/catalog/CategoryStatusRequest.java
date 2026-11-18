package dev.mattest.supplychain.dto.catalog;

import jakarta.validation.constraints.NotNull;

public record CategoryStatusRequest(@NotNull Boolean active) {
}
