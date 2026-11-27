package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.service.MediaResourceService;
import dev.mattest.supplychain.service.MediaResourceService.MediaContent;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/media")
public class PublicMediaController {
    private static final CacheControl CACHE_CONTROL =
            CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable();
    /**
     * 只接受固定档位：缓存键空间随档位数线性增长，放开任意宽度会让
     * ThumbnailCache 被上万种尺寸打满。
     */
    private static final int[] ALLOWED_WIDTHS = {160, 240, 320, 480, 640, 750, 1080, 1440};

    private final MediaResourceService mediaResourceService;

    public PublicMediaController(MediaResourceService mediaResourceService) {
        this.mediaResourceService = mediaResourceService;
    }

    /**
     * @param width 期望的最长边，缺省返回原图。非白名单值会吸附到最近的档位
     */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> getImage(
            @PathVariable long id,
            @RequestParam(name = "w", required = false) Integer width,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Integer normalizedWidth = snapWidth(width);
        MediaContent media = mediaResourceService.readPublic(id, normalizedWidth);
        byte[] bytes = media.bytes();
        // 尺寸与字节数都进 ETag：换图或换档位都会让缓存失效。
        String etag = "\"" + id + "-" + (normalizedWidth == null ? 0 : normalizedWidth) + "-" + bytes.length + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CACHE_CONTROL)
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(media.mediaType())
                .eTag(etag)
                .cacheControl(CACHE_CONTROL)
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }

    private static Integer snapWidth(Integer width) {
        if (width == null || width <= 0) {
            return null;
        }
        int closest = ALLOWED_WIDTHS[0];
        for (int candidate : ALLOWED_WIDTHS) {
            if (Math.abs(candidate - width) < Math.abs(closest - width)) {
                closest = candidate;
            }
        }
        return closest;
    }
}
