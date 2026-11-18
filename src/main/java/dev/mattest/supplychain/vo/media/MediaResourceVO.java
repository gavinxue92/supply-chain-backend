package dev.mattest.supplychain.vo.media;

public record MediaResourceVO(
        long id,
        String originalFilename,
        String mediaType,
        long byteSize,
        String status,
        String publicUrl) {
}
