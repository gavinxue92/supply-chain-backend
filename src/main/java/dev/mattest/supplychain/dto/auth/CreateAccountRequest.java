package dev.mattest.supplychain.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 80) @Pattern(regexp = "^[^\\s]+$") String username,
        @Size(max = 50) String nickname,
        @NotBlank @Size(min = 12, max = 200) String temporaryPassword,
        @NotBlank @Pattern(regexp = "^(ADMIN|EDITOR)$") String role) {
}
