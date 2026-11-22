package dev.mattest.supplychain.dto.shop;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShopInfoRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 200) String slogan,
        @NotBlank @Size(max = 300) String address,
        @NotBlank @Size(max = 120) String hours,
        @NotBlank @Size(max = 20) String phone,
        @NotBlank @Size(max = 128) String wechat) {
}
