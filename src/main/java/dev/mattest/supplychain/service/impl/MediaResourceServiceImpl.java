package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.mattest.supplychain.entity.MediaResource;
import dev.mattest.supplychain.mapper.MediaResourceMapper;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.MediaReferenceService;
import dev.mattest.supplychain.service.MediaResourceService;
import dev.mattest.supplychain.vo.media.MediaResourceVO;
import dev.mattest.supplychain.vo.media.UploadAuthorizationVO;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MediaResourceServiceImpl implements MediaResourceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaResourceServiceImpl.class);
    public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_MEDIA_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    private final MediaResourceMapper mediaResourceMapper;
    private final S3ObjectStorage storage;
    private final MediaReferenceService mediaReferenceService;
    private final AuditLogWriter auditLogWriter;
    private final ImageTransformer imageTransformer;
    private final ThumbnailCache thumbnailCache;
    private final Clock clock;

    public MediaResourceServiceImpl(
            MediaResourceMapper mediaResourceMapper,
            S3ObjectStorage storage,
            MediaReferenceService mediaReferenceService,
            AuditLogWriter auditLogWriter,
            ImageTransformer imageTransformer,
            ThumbnailCache thumbnailCache,
            Clock clock) {
        this.mediaResourceMapper = mediaResourceMapper;
        this.storage = storage;
        this.mediaReferenceService = mediaReferenceService;
        this.auditLogWriter = auditLogWriter;
        this.imageTransformer = imageTransformer;
        this.thumbnailCache = thumbnailCache;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UploadAuthorizationVO authorizeUpload(
            AdminPrincipal actor, String originalFilename, String contentType) {
        if (!ALLOWED_MEDIA_TYPES.contains(contentType)) {
            throw badRequest("Only JPEG, PNG, and WebP images are supported");
        }
        long expirationSeconds = storage.uploadExpirationSeconds();
        if (expirationSeconds < 1 || expirationSeconds > 900) {
            throw new IllegalStateException("Media upload expiration must be between 1 and 900 seconds");
        }

        String objectKey = storage.newObjectKey("pending");
        String uploadUrl = storage.presignPut(objectKey, contentType);
        Instant expiresAt = clock.instant().plusSeconds(expirationSeconds);

        MediaResource resource = new MediaResource();
        resource.setObjectKey(objectKey);
        resource.setOriginalFilename(normalizeFilename(originalFilename));
        resource.setMediaType(contentType);
        resource.setByteSize(0L);
        resource.setResourceStatus("PENDING");
        resource.setUploadedBy(actor.id());
        resource.setUploadExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        mediaResourceMapper.insert(resource);

        return new UploadAuthorizationVO(
                resource.getId(),
                uploadUrl,
                "PUT",
                Map.of("Content-Type", contentType),
                expiresAt);
    }

    @Override
    @Transactional
    public MediaResourceVO confirmUpload(AdminPrincipal actor, long mediaId) {
        MediaResource resource = findOwned(mediaId, actor.id());
        if (!"PENDING".equals(resource.getResourceStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Media upload is not pending");
        }
        String pendingObjectKey = resource.getObjectKey();
        try {
            if (resource.getUploadExpiresAt() == null
                    || !clock.instant().isBefore(resource.getUploadExpiresAt().toInstant(ZoneOffset.UTC))) {
                throw new ResponseStatusException(HttpStatus.GONE, "Media upload authorization has expired");
            }

            long objectSize;
            try {
                objectSize = storage.objectSize(pendingObjectKey);
            } catch (S3ObjectStorage.MissingUploadedObjectException exception) {
                throw badRequest("Uploaded media object was not found");
            }
            if (objectSize <= 0 || objectSize > MAX_IMAGE_BYTES) {
                throw badRequest("Image size must be between 1 byte and 10 MB");
            }

            byte[] bytes;
            try {
                bytes = storage.readPendingObject(pendingObjectKey, MAX_IMAGE_BYTES);
            } catch (S3ObjectStorage.MissingUploadedObjectException exception) {
                throw badRequest("Uploaded media object was not found");
            }
            if (bytes.length != objectSize || bytes.length > MAX_IMAGE_BYTES) {
                throw badRequest("Image size must be between 1 byte and 10 MB");
            }

            String detectedType = detectMediaType(bytes);
            if (!resource.getMediaType().equals(detectedType)) {
                throw badRequest("Uploaded image content does not match the declared content type");
            }

            // 入库即压到可展示的尺寸，避免 2048×2048 的无损 PNG 直接进 CDN 链路。
            // WebP 解码器不在 JDK 内，normalize 返回 null 时保留原图。
            byte[] storedBytes = bytes;
            String storedType = detectedType;
            byte[] normalized = imageTransformer.normalize(bytes);
            if (normalized != null) {
                storedBytes = normalized;
                storedType = "image/jpeg";
                LOGGER.info("Normalized media {}: {} -> {} bytes", mediaId, bytes.length, storedBytes.length);
            }

            String confirmedKey = storage.newObjectKey("confirmed");
            storage.storeConfirmedObject(confirmedKey, storedType, storedBytes);
            String publicUrl = storage.publicUrl(mediaId);
            int updated = mediaResourceMapper.update(
                    null,
                    new LambdaUpdateWrapper<MediaResource>()
                            .eq(MediaResource::getId, mediaId)
                            .eq(MediaResource::getUploadedBy, actor.id())
                            .eq(MediaResource::getResourceStatus, "PENDING")
                            .set(MediaResource::getObjectKey, confirmedKey)
                            .set(MediaResource::getMediaType, storedType)
                            .set(MediaResource::getByteSize, (long) storedBytes.length)
                            .set(MediaResource::getResourceStatus, "READY")
                            .set(MediaResource::getPublicUrl, publicUrl));
            if (updated != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Media upload is no longer pending");
            }

            resource.setObjectKey(confirmedKey);
            resource.setMediaType(storedType);
            resource.setByteSize((long) storedBytes.length);
            resource.setResourceStatus("READY");
            resource.setPublicUrl(publicUrl);
            auditLogWriter.write(actor.id(), "MEDIA_RESOURCE_REGISTERED", "media_resources", mediaId,
                    Map.of("mediaType", storedType, "byteSize", storedBytes.length));
            return toView(resource);
        } finally {
            try {
                storage.deleteObject(pendingObjectKey);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not clean pending object {} after confirmation of media {}",
                        pendingObjectKey, mediaId, exception);
            }
        }
    }

    @Override
    public MediaContent readPublic(long mediaId, Integer width) {
        MediaResource resource = mediaResourceMapper.selectById(mediaId);
        if (resource == null || !"READY".equals(resource.getResourceStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media resource not found");
        }
        byte[] original = storage.readConfirmedObject(resource.getObjectKey());
        if (width == null || width <= 0) {
            return new MediaContent(MediaType.parseMediaType(resource.getMediaType()), original);
        }
        String cacheKey = mediaId + ":" + width;
        byte[] cached = thumbnailCache.get(cacheKey);
        if (cached != null) {
            return new MediaContent(MediaType.IMAGE_JPEG, cached);
        }
        byte[] thumbnail = imageTransformer.toThumbnail(original, width);
        if (thumbnail == null) {
            // WebP 等 JDK 不支持的格式退回原图，宁可大一点也不要 404。
            return new MediaContent(MediaType.parseMediaType(resource.getMediaType()), original);
        }
        thumbnailCache.put(cacheKey, thumbnail);
        return new MediaContent(MediaType.IMAGE_JPEG, thumbnail);
    }

    private MediaResource findOwned(long id, long actorId) {
        MediaResource resource = mediaResourceMapper.selectOne(
                new LambdaQueryWrapper<MediaResource>()
                        .eq(MediaResource::getId, id)
                        .eq(MediaResource::getUploadedBy, actorId));
        if (resource == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media resource not found");
        }
        return resource;
    }

    @Override
    @Transactional
    public void delete(AdminPrincipal actor, long mediaId) {
        MediaResource resource = mediaResourceMapper.selectById(mediaId);
        if (resource == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Media resource not found");
        }
        if (mediaReferenceService.isReferenced(mediaId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Media resource is still referenced");
        }
        deleteUnreferenced(actor, resource);
    }

    @Override
    @Transactional
    public void cleanupUnreferenced(AdminPrincipal actor, Collection<Long> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return;
        }
        for (Long mediaId : new LinkedHashSet<>(mediaIds)) {
            if (mediaId == null || mediaReferenceService.isReferenced(mediaId)) {
                continue;
            }
            MediaResource resource = mediaResourceMapper.selectById(mediaId);
            if (resource != null) {
                deleteUnreferenced(actor, resource);
            }
        }
    }

    private void deleteUnreferenced(AdminPrincipal actor, MediaResource resource) {
        long mediaId = resource.getId();
        String objectKey = resource.getObjectKey();
        mediaResourceMapper.update(null, new LambdaUpdateWrapper<MediaResource>()
                .eq(MediaResource::getId, mediaId)
                .set(MediaResource::getResourceStatus, "DELETING"));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteObjectAndRecord(actor, mediaId, objectKey, resource.getMediaType());
                }
            });
        } else {
            deleteObjectAndRecord(actor, mediaId, objectKey, resource.getMediaType());
        }
    }

    @Scheduled(fixedDelayString = "${app.media.pending-cleanup-ms:300000}")
    public void cleanupExpiredPendingUploads() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<MediaResource> expired = mediaResourceMapper.selectList(new LambdaQueryWrapper<MediaResource>()
                .eq(MediaResource::getResourceStatus, "PENDING")
                .lt(MediaResource::getUploadExpiresAt, cutoff));
        for (MediaResource resource : expired) {
            try {
                storage.deleteObject(resource.getObjectKey());
                mediaResourceMapper.physicalDeleteById(resource.getId());
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not clean expired pending upload {}",
                        resource.getId(), exception);
            }
        }
    }

    private void deleteObjectAndRecord(
            AdminPrincipal actor, long mediaId, String objectKey, String mediaType) {
        try {
            storage.deleteObject(objectKey);
            mediaResourceMapper.physicalDeleteById(mediaId);
            auditLogWriter.write(actor == null ? null : actor.id(), "MEDIA_RESOURCE_DELETED",
                    "media_resources", mediaId, Map.of("objectKey", objectKey, "mediaType", mediaType));
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not delete object {} for media {}; keeping DELETING state",
                    objectKey, mediaId, exception);
        }
    }

    private MediaResourceVO toView(MediaResource resource) {
        return new MediaResourceVO(
                resource.getId(),
                resource.getOriginalFilename(),
                resource.getMediaType(),
                resource.getByteSize(),
                resource.getResourceStatus(),
                resource.getPublicUrl());
    }

    private String normalizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String normalized = filename.trim().replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        normalized = separator >= 0 ? normalized.substring(separator + 1) : normalized;
        if (normalized.isBlank() || normalized.length() > 255) {
            throw badRequest("Filename must contain at most 255 characters");
        }
        return normalized;
    }

    private String detectMediaType(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return requireReadableImage(bytes, "JPEG", "image/jpeg");
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && (bytes[4] & 0xff) == 0x0d && (bytes[5] & 0xff) == 0x0a
                && (bytes[6] & 0xff) == 0x1a && (bytes[7] & 0xff) == 0x0a) {
            return requireReadableImage(bytes, "PNG", "image/png");
        }
        if (bytes.length >= 20
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P'
                && bytes[12] == 'V' && bytes[13] == 'P' && bytes[14] == '8'
                && (bytes[15] == ' ' || bytes[15] == 'L' || bytes[15] == 'X')) {
            return isWebpContainer(bytes) ? "image/webp" : unsupportedImage();
        }
        return unsupportedImage();
    }

    private String requireReadableImage(byte[] bytes, String format, String mediaType) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return unsupportedImage();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return unsupportedImage();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!format.equalsIgnoreCase(reader.getFormatName())
                        || reader.getWidth(0) <= 0
                        || reader.getHeight(0) <= 0) {
                    return unsupportedImage();
                }
                return mediaType;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            return unsupportedImage();
        }
    }

    private boolean isWebpContainer(byte[] bytes) {
        long riffSize = littleEndianUnsignedInt(bytes, 4);
        long chunkSize = littleEndianUnsignedInt(bytes, 16);
        long paddedChunkSize = chunkSize + (chunkSize & 1);
        int minimumChunkSize = bytes[15] == 'L' ? 5 : 10;
        return riffSize == bytes.length - 8L
                && chunkSize >= minimumChunkSize
                && chunkSize <= Integer.MAX_VALUE
                && 20L + paddedChunkSize <= bytes.length;
    }

    private long littleEndianUnsignedInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private String unsupportedImage() {
        throw badRequest("Uploaded file is not a supported image");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
