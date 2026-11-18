package dev.mattest.supplychain.dto.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UploadAuthorizationRequest(
        @Size(max = 255) String originalFilename,
        @NotBlank
        @Pattern(regexp = "image/(jpeg|png|webp)")
        String contentType) {
}
