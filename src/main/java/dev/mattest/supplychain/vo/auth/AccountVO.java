package dev.mattest.supplychain.vo.auth;

import java.time.Instant;

public record AccountVO(
        long id,
        String username,
        String nickname,
        String role,
        boolean active,
        boolean mustChangePassword,
        Instant createdAt) {
}
