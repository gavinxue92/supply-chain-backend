package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.common.Result;
import dev.mattest.supplychain.dto.auth.ChangePasswordRequest;
import dev.mattest.supplychain.dto.auth.LoginRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.security.AdminSessionFilter;
import dev.mattest.supplychain.service.AdminAuthService;
import dev.mattest.supplychain.vo.auth.CsrfVO;
import dev.mattest.supplychain.vo.auth.CurrentAdminVO;
import dev.mattest.supplychain.vo.auth.LoginVO;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthController {
    private final AdminAuthService adminAuthService;
    private final boolean cookieSecure;
    private final Duration sessionTimeout;

    public AdminAuthController(
            AdminAuthService adminAuthService,
            @Value("${app.security.cookie-secure:true}") boolean cookieSecure,
            @Value("${app.security.session-idle-timeout:PT12H}") Duration sessionTimeout) {
        this.adminAuthService = adminAuthService;
        this.cookieSecure = cookieSecure;
        this.sessionTimeout = sessionTimeout;
    }

    @GetMapping("/csrf")
    public Result<CsrfVO> csrf(CsrfToken csrfToken) {
        return Result.success(new CsrfVO(csrfToken.getToken()));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "adminSession")
    public Result<CurrentAdminVO> currentUser(
            @AuthenticationPrincipal AdminPrincipal principal) {
        return Result.success(new CurrentAdminVO(
                principal.username(), principal.nickname(), principal.role(), principal.mustChangePassword()));
    }

    @PostMapping("/login")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<LoginVO> login(
            @Valid @RequestBody LoginRequest requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        AdminAuthService.LoginSession login =
                adminAuthService.login(requestBody, request.getRemoteAddr());
        response.addHeader("Set-Cookie", sessionCookie(login.token(), sessionTimeout).toString());
        return Result.success(login.response());
    }

    @PostMapping("/logout")
    @SecurityRequirement(name = "adminSession")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<Void> logout(
            @AuthenticationPrincipal AdminPrincipal principal,
            HttpServletResponse response) {
        adminAuthService.logout(principal.sessionId());
        clearSessionCookie(response);
        return Result.success(null);
    }

    @PostMapping("/change-password")
    @SecurityRequirement(name = "adminSession")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<Void> changePassword(
            @AuthenticationPrincipal AdminPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest requestBody,
            HttpServletResponse response) {
        adminAuthService.changePassword(principal, requestBody);
        clearSessionCookie(response);
        return Result.success(null);
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(AdminSessionFilter.COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Strict")
                .path("/api/v1")
                .maxAge(maxAge)
                .build();
    }

    private void clearSessionCookie(HttpServletResponse response) {
        response.addHeader(
                "Set-Cookie", sessionCookie("", Duration.ZERO).toString());
    }
}
