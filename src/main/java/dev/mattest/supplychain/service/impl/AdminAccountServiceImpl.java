package dev.mattest.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.mattest.supplychain.dto.auth.CreateAccountRequest;
import dev.mattest.supplychain.dto.auth.ResetPasswordRequest;
import dev.mattest.supplychain.entity.AdminSession;
import dev.mattest.supplychain.entity.AdminUser;
import dev.mattest.supplychain.mapper.AdminSessionMapper;
import dev.mattest.supplychain.mapper.AdminUserMapper;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.AdminAccountService;
import dev.mattest.supplychain.service.PasswordPolicy;
import dev.mattest.supplychain.vo.auth.AccountVO;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Service
public class AdminAccountServiceImpl implements AdminAccountService {
    private final AdminUserMapper userMapper;
    private final AdminSessionMapper sessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogWriter auditLogWriter;

    public AdminAccountServiceImpl(
            AdminUserMapper userMapper,
            AdminSessionMapper sessionMapper,
            PasswordEncoder passwordEncoder,
            AuditLogWriter auditLogWriter) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditLogWriter = auditLogWriter;
    }

    @Override
    public List<AccountVO> list() {
        return userMapper.selectList(new LambdaQueryWrapper<AdminUser>()
                        .orderByAsc(AdminUser::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public AccountVO create(AdminPrincipal actor, CreateAccountRequest request) {
        validatePassword(request.temporaryPassword());
        AdminUser user = new AdminUser();
        user.setUsername(request.username());
        user.setNickname(normalizeNickname(request.nickname()));
        user.setPasswordHash(passwordEncoder.encode(request.temporaryPassword()));
        user.setRole(request.role());
        user.setIsActive(true);
        user.setMustChangePassword(true);
        user.setFailedLoginAttempts(0);
        userMapper.insert(user);
        auditLogWriter.write(actor.id(), "ADMIN_ACCOUNT_CREATED", "admin_users", user.getId(),
                Map.of("username", request.username(), "role", request.role()));
        return toView(userMapper.selectById(user.getId()));
    }

    @Override
    @Transactional
    public void deactivate(AdminPrincipal actor, long id) {
        int changed = userMapper.update(null, new LambdaUpdateWrapper<AdminUser>()
                .eq(AdminUser::getId, id)
                .eq(AdminUser::getIsActive, true)
                .set(AdminUser::getIsActive, false));
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active account not found");
        }
        revokeSessions(id);
        auditLogWriter.write(actor.id(), "ADMIN_ACCOUNT_DEACTIVATED", "admin_users", id, Map.of());
    }

    @Override
    @Transactional
    public void resetPassword(AdminPrincipal actor, long id, ResetPasswordRequest request) {
        validatePassword(request.temporaryPassword());
        int changed = userMapper.update(null, new LambdaUpdateWrapper<AdminUser>()
                .eq(AdminUser::getId, id)
                .set(AdminUser::getPasswordHash, passwordEncoder.encode(request.temporaryPassword()))
                .set(AdminUser::getMustChangePassword, true)
                .set(AdminUser::getFailedLoginAttempts, 0)
                .set(AdminUser::getLockedUntil, null));
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        revokeSessions(id);
        auditLogWriter.write(actor.id(), "ADMIN_ACCOUNT_PASSWORD_RESET", "admin_users", id, Map.of());
    }

    @Override
    @Transactional
    public void updateNickname(AdminPrincipal actor, long id, String nickname) {
        if (id != actor.id()) {
            requireAdministrator(actor);
        }
        AdminUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        user.setNickname(normalizeNickname(nickname));
        userMapper.updateById(user);
        auditLogWriter.write(actor.id(), "ADMIN_ACCOUNT_NICKNAME_UPDATED", "admin_users", id,
                Map.of("username", user.getUsername(), "nickname", user.getNickname() == null ? "" : user.getNickname()));
    }

    @Override
    @Transactional
    public void hardDelete(AdminPrincipal actor, long id) {
        requireAdministrator(actor);
        if (id == actor.id()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete the current account");
        }
        AdminUser user = userMapper.selectById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        if (Boolean.TRUE.equals(user.getIsActive())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Deactivate the account before deleting it");
        }
        sessionMapper.physicalDeleteByUserId(id);
        auditLogWriter.write(actor.id(), "ADMIN_ACCOUNT_HARD_DELETED", "admin_users", id,
                Map.of("username", user.getUsername(),
                        "nickname", user.getNickname() == null ? "" : user.getNickname(),
                        "role", user.getRole()));
        userMapper.physicalDeleteById(id);
    }

    private void requireAdministrator(AdminPrincipal actor) {
        if (!"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
    }

    private String normalizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return null;
        }
        String normalized = nickname.trim();
        if (normalized.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nickname must not exceed 50 characters");
        }
        return normalized;
    }

    private void revokeSessions(long userId) {
        sessionMapper.delete(new LambdaQueryWrapper<AdminSession>()
                .eq(AdminSession::getAdminUserId, userId));
    }

    private void validatePassword(String password) {
        if (!PasswordPolicy.isBcryptCompatible(password)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Password must be at most 72 UTF-8 bytes");
        }
    }

    private AccountVO toView(AdminUser user) {
        return new AccountVO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole(),
                Boolean.TRUE.equals(user.getIsActive()),
                Boolean.TRUE.equals(user.getMustChangePassword()),
                user.getCreatedAt() == null ? null : user.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
