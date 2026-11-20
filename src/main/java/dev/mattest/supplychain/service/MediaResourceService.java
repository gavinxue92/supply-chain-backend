package dev.mattest.supplychain.service;

import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.vo.media.MediaResourceVO;
import dev.mattest.supplychain.vo.media.UploadAuthorizationVO;
import org.springframework.http.MediaType;

import java.util.Collection;

public interface MediaResourceService {
    UploadAuthorizationVO authorizeUpload(AdminPrincipal actor, String originalFilename, String contentType);

    MediaResourceVO confirmUpload(AdminPrincipal actor, long mediaId);

    MediaContent readPublic(long mediaId);

    void delete(AdminPrincipal actor, long mediaId);

    void cleanupUnreferenced(AdminPrincipal actor, Collection<Long> mediaIds);

    record MediaContent(MediaType mediaType, byte[] bytes) {
    }
}
