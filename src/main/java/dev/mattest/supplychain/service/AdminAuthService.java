package dev.mattest.supplychain.service;

import dev.mattest.supplychain.dto.auth.ChangePasswordRequest;
import dev.mattest.supplychain.dto.auth.LoginRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.vo.auth.LoginVO;

public interface AdminAuthService {
    LoginSession login(LoginRequest request, String remoteAddress);

    void logout(long sessionId);

    void changePassword(AdminPrincipal principal, ChangePasswordRequest request);

    AdminPrincipal authenticateSession(String token);

    record LoginSession(LoginVO response, String token) {
    }
}
