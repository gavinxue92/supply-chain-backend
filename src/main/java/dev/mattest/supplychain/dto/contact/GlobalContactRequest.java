package dev.mattest.supplychain.dto.contact;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record GlobalContactRequest(
        @NotEmpty List<@NotNull @Positive Long> serviceIds) {
}
