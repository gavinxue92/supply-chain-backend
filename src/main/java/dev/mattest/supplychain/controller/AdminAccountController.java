package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.common.Result;
import dev.mattest.supplychain.dto.auth.CreateAccountRequest;
import dev.mattest.supplychain.dto.auth.NicknameRequest;
import dev.mattest.supplychain.dto.auth.ResetPasswordRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.AdminAccountService;
import dev.mattest.supplychain.vo.auth.AccountVO;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/accounts")
@SecurityRequirement(name = "adminSession")
public class AdminAccountController {
    private final AdminAccountService adminAccountService;

    public AdminAccountController(AdminAccountService adminAccountService) {
        this.adminAccountService = adminAccountService;
    }

    @GetMapping
    public Result<List<AccountVO>> list() {
        return Result.success(adminAccountService.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<AccountVO> create(
            @AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody CreateAccountRequest request) {
        return Result.success(adminAccountService.create(actor, request));
    }

    @PostMapping("/{id}/deactivate")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<Void> deactivate(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id) {
        adminAccountService.deactivate(actor, id);
        return Result.success(null);
    }

    @PutMapping("/{id}/temporary-password")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<Void> resetPassword(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id,
            @Valid @RequestBody ResetPasswordRequest request) {
        adminAccountService.resetPassword(actor, id, request);
        return Result.success(null);
    }

    @PatchMapping("/me/nickname")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<Void> updateOwnNickname(
            @AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody NicknameRequest request) {
        adminAccountService.updateNickname(actor, actor.id(), request.nickname());
        return Result.success(null);
    }

    @PatchMapping("/{id}/nickname")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<Void> updateNickname(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id,
            @Valid @RequestBody NicknameRequest request) {
        adminAccountService.updateNickname(actor, id, request.nickname());
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<Void> hardDelete(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id) {
        adminAccountService.hardDelete(actor, id);
        return Result.success(null);
    }
}
