package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.mattest.supplychain.dto.contact.GlobalContactRequest;
import dev.mattest.supplychain.entity.GlobalContactConfig;
import dev.mattest.supplychain.entity.MediaResource;
import dev.mattest.supplychain.mapper.GlobalContactConfigMapper;
import dev.mattest.supplychain.mapper.MediaResourceMapper;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.GlobalContactService;
import dev.mattest.supplychain.vo.contact.GlobalContactVO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
public class GlobalContactServiceImpl implements GlobalContactService {
    private static final String CONFIG_KEY = "GLOBAL";

    private final GlobalContactConfigMapper configMapper;
    private final MediaResourceMapper mediaResourceMapper;
    private final AuditLogWriter auditLogWriter;

    public GlobalContactServiceImpl(
            GlobalContactConfigMapper configMapper,
            MediaResourceMapper mediaResourceMapper,
            AuditLogWriter auditLogWriter) {
        this.configMapper = configMapper;
        this.mediaResourceMapper = mediaResourceMapper;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    public GlobalContactVO get(AdminPrincipal actor) {
        requireAdministrator(actor);
        GlobalContactConfig config = findConfig();
        return config == null ? null : toView(config);
    }

    @Override
    @Transactional
    public GlobalContactVO save(AdminPrincipal actor, GlobalContactRequest request) {
        requireAdministrator(actor);
        String wechatId = normalize(request.wechatId());
        Long qrMediaId = request.qrMediaId();
        if (wechatId == null && qrMediaId == null) {
            throw badRequest("At least one global contact method is required");
        }
        validateQrMedia(qrMediaId);

        GlobalContactConfig config = findConfig();
        if (config == null) {
            config = new GlobalContactConfig();
            config.setConfigKey(CONFIG_KEY);
        }
        config.setWechatId(wechatId);
        config.setQrMediaId(qrMediaId);
        if (config.getId() == null) {
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }
        auditLogWriter.write(actor.id(), "GLOBAL_CONTACT_UPDATED",
                "global_contact_configs", config.getId(),
                Map.of("hasWechatId", wechatId != null, "hasQrCode", qrMediaId != null));
        return toView(configMapper.selectById(config.getId()));
    }

    private GlobalContactConfig findConfig() {
        return configMapper.selectOne(new LambdaQueryWrapper<GlobalContactConfig>()
                .eq(GlobalContactConfig::getConfigKey, CONFIG_KEY));
    }

    private void validateQrMedia(Long mediaId) {
        if (mediaId == null) {
            return;
        }
        long count = mediaResourceMapper.selectCount(new LambdaQueryWrapper<MediaResource>()
                .eq(MediaResource::getId, mediaId)
                .eq(MediaResource::getResourceStatus, "READY"));
        if (count != 1) {
            throw badRequest("The customer-service QR code must reference confirmed media");
        }
    }

    private GlobalContactVO toView(GlobalContactConfig config) {
        return new GlobalContactVO(
                config.getId(),
                config.getWechatId(),
                config.getQrMediaId(),
                config.getQrMediaId() == null ? null : mediaUrl(config.getQrMediaId()));
    }

    private String mediaUrl(long mediaId) {
        return "/api/v1/media/" + mediaId;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void requireAdministrator(AdminPrincipal actor) {
        if (!"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
