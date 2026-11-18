package dev.mattest.supplychain.dto.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProductDraftRequest(
        @Size(max = 200) String name,
        Long categoryId,
        String detailText,
        @Positive Long mainImageMediaId,
        @Size(max = 8) List<@NotNull @Positive Long> galleryMediaIds,
        List<@NotBlank @Size(max = 120) String> keywords,
        @Pattern(regexp = "INHERIT|OVERRIDE|DISABLED") String contactMode,
        @Size(max = 128) String contactWechatId,
        @Positive Long contactQrMediaId,
        Integer sortOrder,
        List<@NotNull @Valid ProductSpecRequest> specifications) {
}
