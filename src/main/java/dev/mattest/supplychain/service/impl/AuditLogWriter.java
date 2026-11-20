package dev.mattest.supplychain.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mattest.supplychain.entity.AdminUser;
import dev.mattest.supplychain.entity.AuditLog;
import dev.mattest.supplychain.mapper.AdminUserMapper;
import dev.mattest.supplychain.mapper.AuditLogMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AuditLogWriter {
    private final AuditLogMapper auditLogMapper;
    private final AdminUserMapper adminUserMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditLogWriter(
            AuditLogMapper auditLogMapper,
            AdminUserMapper adminUserMapper,
            ObjectMapper objectMapper,
            Clock clock) {
        this.auditLogMapper = auditLogMapper;
        this.adminUserMapper = adminUserMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void write(Long actorId, String action, String targetType, Long targetId, Object summary) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        if (summary instanceof Map<?, ?> summaryMap) {
            summaryMap.forEach((key, value) -> enriched.put(String.valueOf(key), value));
        } else if (summary != null) {
            enriched.put("summary", summary);
        }
        if (actorId != null) {
            AdminUser actor = adminUserMapper.selectById(actorId);
            enriched.put("actorUsername", actor == null ? null : actor.getUsername());
            enriched.put("actorNickname", actor == null ? null : actor.getNickname());
        }
        AuditLog entry = new AuditLog();
        entry.setActorAdminUserId(actorId);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId == null ? null : targetId.toString());
        entry.setChangeSummary(serialize(enriched));
        entry.setOccurredAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        auditLogMapper.insert(entry);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize audit summary", exception);
        }
    }
}
