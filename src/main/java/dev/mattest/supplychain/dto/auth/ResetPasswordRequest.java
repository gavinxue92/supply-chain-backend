package dev.mattest.supplychain.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Size(min = 12, max = 200) String temporaryPassword) {
}
