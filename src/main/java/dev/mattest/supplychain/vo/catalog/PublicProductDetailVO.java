package dev.mattest.supplychain.vo.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record PublicProductDetailVO(
        long id,
        String name,
        long categoryId,
        String mainImageUrl,
        List<String> galleryImageUrls,
        String detailHtml,
        List<PublicProductSpecVO> specifications,
        String referencePriceNotice,
        @JsonInclude(JsonInclude.Include.NON_NULL) ContactDisplayVO contact) {
}
