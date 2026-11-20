package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.mattest.supplychain.dto.auth.ChangePasswordRequest;
import dev.mattest.supplychain.dto.auth.LoginRequest;
import dev.mattest.supplychain.entity.AdminSession;
import dev.mattest.supplychain.entity.AdminUser;
import dev.mattest.supplychain.mapper.AdminSessionMapper;
import dev.mattest.supplychain.mapper.AdminUserMapper;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.security.SessionTokenHasher;
import dev.mattest.supplychain.service.AdminAuthService;
import dev.mattest.supplychain.service.LoginRateLimiter;
import dev.mattest.supplychain.service.PasswordPolicy;
import dev.mattest.supplychain.vo.auth.LoginVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

@Service
public class AdminAuthServiceImpl implements AdminAuthService {
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final AdminUserMapper userMapper;
    private final AdminSessionMapper sessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;
    private final AuditLogWriter auditLogWriter;
    private final Duration sessionTimeout;
    private final Duration lockDuration;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String dummyPasswordHash;

    public AdminAuthServiceImpl(
            AdminUserMapper userMapper,
            AdminSessionMapper sessionMapper,
            PasswordEncoder passwordEncoder,
            LoginRateLimiter rateLimiter,
            AuditLogWriter auditLogWriter,
            Clock clock,
            @Value("${app.security.session-idle-timeout:PT12H}") Duration sessionTimeout,
            @Value("${app.security.lock-duration:PT15M}") Duration lockDuration) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.auditLogWriter = auditLogWriter;
        this.clock = clock;
        this.sessionTimeout = sessionTimeout;
        this.lockDuration = lockDuration;
        this.dummyPasswordHash = passwordEncoder.encode("unmatched-account-password");
    }

    @Override
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public LoginSession login(LoginRequest request, String remoteAddress) {
        if (!rateLimiter.tryAcquire(remoteAddress)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts");
        }

        AdminUser user = userMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, request.username())
                .eq(AdminUser::getIsActive, true));
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw invalidCredentials();
        }

        LocalDateTime now = now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw invalidCredentials();
        }
        if (!PasswordPolicy.isBcryptCompatible(request.password())) {
            passwordEncoder.matches("invalid-password", user.getPasswordHash());
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            userMapper.registerFailedLogin(user.getId(), MAX_FAILED_ATTEMPTS, now.plus(lockDuration));
            throw invalidCredentials();
        }

        userMapper.update(null, new LambdaUpdateWrapper<AdminUser>()
                .eq(AdminUser::getId, user.getId())
                .set(AdminUser::getFailedLoginAttempts, 0)
                .set(AdminUser::getLockedUntil, null)
                .set(AdminUser::getLastLoginAt, now));

        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        AdminSession session = new AdminSession();
        session.setAdminUserId(user.getId());
        session.setSessionTokenHash(SessionTokenHasher.hash(token));
        session.setLastActivityAt(now);
        session.setExpiresAt(now.plus(sessionTimeout));
        sessionMapper.insert(session);
        return new LoginSession(
                new LoginVO(user.getUsername(), user.getNickname(), user.getRole(),
                        Boolean.TRUE.equals(user.getMustChangePassword())),
                token);
    }

    @Override
    @Transactional
    public void logout(long sessionId) {
        sessionMapper.deleteById(sessionId);
    }

    @Override
    @Transactional
    public void changePassword(AdminPrincipal principal, ChangePasswordRequest request) {
        if (!principal.mustChangePassword()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password change is not required");
        }
        if (!PasswordPolicy.isBcryptCompatible(request.newPassword())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Password must be at most 72 UTF-8 bytes");
        }
        userMapper.update(null, new LambdaUpdateWrapper<AdminUser>()
                .eq(AdminUser::getId, principal.id())
                .set(AdminUser::getPasswordHash, passwordEncoder.encode(request.newPassword()))
                .set(AdminUser::getMustChangePassword, false)
                .set(AdminUser::getFailedLoginAttempts, 0)
                .set(AdminUser::getLockedUntil, null));
        sessionMapper.delete(new LambdaQueryWrapper<AdminSession>()
                .eq(AdminSession::getAdminUserId, principal.id()));
    }

    @Override
    public AdminPrincipal authenticateSession(String token) {
        LocalDateTime now = now();
        LocalDateTime idleCutoff = now.minus(sessionTimeout);
        AdminSession session = sessionMapper.selectOne(new LambdaQueryWrapper<AdminSession>()
                .eq(AdminSession::getSessionTokenHash, SessionTokenHasher.hash(token))
                .gt(AdminSession::getExpiresAt, now)
                .gt(AdminSession::getLastActivityAt, idleCutoff));
        if (session == null) {
            return null;
        }

        AdminUser user = userMapper.selectById(session.getAdminUserId());
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            return null;
        }
        int refreshed = sessionMapper.update(null, new LambdaUpdateWrapper<AdminSession>()
                .eq(AdminSession::getId, session.getId())
                .gt(AdminSession::getExpiresAt, now)
                .gt(AdminSession::getLastActivityAt, idleCutoff)
                .set(AdminSession::getLastActivityAt, now)
                .set(AdminSession::getExpiresAt, now.plus(sessionTimeout)));
        if (refreshed == 0) {
            return null;
        }
        return new AdminPrincipal(
                user.getId(), user.getUsername(), user.getNickname(), user.getRole(), session.getId(),
                Boolean.TRUE.equals(user.getMustChangePassword()));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }

}
