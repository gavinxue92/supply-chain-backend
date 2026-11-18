package dev.mattest.supplychain.dto.contact;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record GlobalContactRequest(
        @Size(max = 128) String wechatId,
        @Positive Long qrMediaId) {
}
