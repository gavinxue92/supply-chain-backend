package dev.mattest.supplychain.dto.contact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CustomerServiceRequest(
        @NotBlank @Size(max = 120) String displayName,
        @Size(max = 128) String wechatId,
        @Positive Long qrMediaId,
        @Pattern(regexp = "^1[3-9][0-9]{9}$") String phone) {
}
