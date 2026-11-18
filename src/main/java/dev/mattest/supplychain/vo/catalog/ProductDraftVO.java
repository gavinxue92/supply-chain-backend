package dev.mattest.supplychain.vo.catalog;

import java.util.List;

public record ProductDraftVO(
        long id,
        String status,
        String name,
        Long categoryId,
        String detailText,
        Long mainImageMediaId,
        List<Long> galleryMediaIds,
        List<String> keywords,
        String contactMode,
        String contactWechatId,
        Long contactQrMediaId,
        int sortOrder,
        List<ProductSpecVO> specifications) {
}
