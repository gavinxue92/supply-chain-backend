package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.mattest.supplychain.entity.MediaResource;
import dev.mattest.supplychain.mapper.MediaResourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存量图片一次性迁移：把历史上直接入库的大图转成 JPEG 并压到 {@link ImageTransformer#MAX_EDGE}。
 *
 * <p>只在显式打开开关时执行，跑完请关掉：
 * <pre>
 * docker compose exec app java -jar /app/app.jar \
 *   --spring.main.web-application-type=none --app.media.migrate=true
 * </pre>
 *
 * <p>两条安全边界：
 * <ul>
 *   <li>改写后写的是<b>新对象</b>，旧对象保留。回滚只需把 {@code object_key} 改回旧值。</li>
 *   <li>{@code public_url} 按 id 生成，与对象键无关，所以前端无需改动。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.media.migrate", havingValue = "true")
public class MediaMigrationRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(MediaMigrationRunner.class);
    /** 已经是 JPEG 且小于该体积时视为已优化，重复执行不会反复压缩。 */
    private static final long SKIP_JPEG_BYTES = 400L * 1024;

    private final MediaResourceMapper mediaResourceMapper;
    private final S3ObjectStorage storage;
    private final ImageTransformer imageTransformer;

    public MediaMigrationRunner(
            MediaResourceMapper mediaResourceMapper,
            S3ObjectStorage storage,
            ImageTransformer imageTransformer) {
        this.mediaResourceMapper = mediaResourceMapper;
        this.storage = storage;
        this.imageTransformer = imageTransformer;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<MediaResource> resources = mediaResourceMapper.selectList(
                new LambdaQueryWrapper<MediaResource>()
                        .eq(MediaResource::getResourceStatus, "READY")
                        .orderByAsc(MediaResource::getId));
        LOGGER.info("Media migration started: {} ready resources", resources.size());

        int migrated = 0;
        int skipped = 0;
        int failed = 0;
        long bytesBefore = 0;
        long bytesAfter = 0;

        for (MediaResource resource : resources) {
            String type = resource.getMediaType();
            long size = resource.getByteSize() == null ? 0 : resource.getByteSize();
            if ("image/jpeg".equalsIgnoreCase(type) && size <= SKIP_JPEG_BYTES) {
                skipped++;
                continue;
            }
            try {
                byte[] original = storage.readConfirmedObject(resource.getObjectKey());
                byte[] normalized = imageTransformer.normalize(original);
                if (normalized == null) {
                    LOGGER.warn("Media {} skipped: cannot decode or no gain ({} bytes, {})",
                            resource.getId(), original.length, type);
                    skipped++;
                    continue;
                }
                String newKey = storage.newObjectKey("confirmed");
                storage.storeConfirmedObject(newKey, "image/jpeg", normalized);
                mediaResourceMapper.update(null, new LambdaUpdateWrapper<MediaResource>()
                        .eq(MediaResource::getId, resource.getId())
                        .set(MediaResource::getObjectKey, newKey)
                        .set(MediaResource::getMediaType, "image/jpeg")
                        .set(MediaResource::getByteSize, (long) normalized.length));

                bytesBefore += original.length;
                bytesAfter += normalized.length;
                migrated++;
                LOGGER.info("Media {} migrated: {} -> {} bytes (old key kept at {})",
                        resource.getId(), original.length, normalized.length, resource.getObjectKey());
            } catch (RuntimeException exception) {
                failed++;
                LOGGER.error("Media {} migration failed", resource.getId(), exception);
            }
        }

        LOGGER.info("Media migration finished: migrated={} skipped={} failed={}", migrated, skipped, failed);
        if (bytesBefore > 0) {
            LOGGER.info("Media migration saved {} bytes ({})",
                    bytesBefore - bytesAfter, percent(bytesBefore, bytesAfter));
        }
    }

    private static String percent(long before, long after) {
        if (before <= 0) {
            return "0%";
        }
        return Math.round(100.0 * (before - after) / before) + "% smaller";
    }
}
