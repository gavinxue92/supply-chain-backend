package dev.mattest.supplychain.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mattest.supplychain.entity.AuditLog;
import dev.mattest.supplychain.mapper.AuditLogMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class AuditLogWriter {
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditLogWriter(AuditLogMapper auditLogMapper, ObjectMapper objectMapper, Clock clock) {
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void write(Long actorId, String action, String targetType, Long targetId, Object summary) {
        AuditLog entry = new AuditLog();
        entry.setActorAdminUserId(actorId);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId == null ? null : targetId.toString());
        entry.setChangeSummary(serialize(summary));
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
