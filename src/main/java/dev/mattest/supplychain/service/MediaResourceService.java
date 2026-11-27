package dev.mattest.supplychain.service;

import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.vo.media.MediaResourceVO;
import dev.mattest.supplychain.vo.media.UploadAuthorizationVO;
import org.springframework.http.MediaType;

import java.util.Collection;

public interface MediaResourceService {
    UploadAuthorizationVO authorizeUpload(AdminPrincipal actor, String originalFilename, String contentType);

    MediaResourceVO confirmUpload(AdminPrincipal actor, long mediaId);

    /**
     * 读取公开图片。
     *
     * @param width 目标最长边；为 {@code null} 或 0 时返回原图。非法尺寸按原图处理
     */
    MediaContent readPublic(long mediaId, Integer width);

    void delete(AdminPrincipal actor, long mediaId);

    void cleanupUnreferenced(AdminPrincipal actor, Collection<Long> mediaIds);

    record MediaContent(MediaType mediaType, byte[] bytes) {
    }
}
