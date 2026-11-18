package dev.mattest.supplychain.vo.media;

import java.time.Instant;
import java.util.Map;

public record UploadAuthorizationVO(
        long mediaId,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        Instant expiresAt) {
}
