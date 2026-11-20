package dev.mattest.supplychain.dto.contact;

import jakarta.validation.constraints.NotNull;

public record CustomerServiceStatusRequest(@NotNull Boolean active) {
}
