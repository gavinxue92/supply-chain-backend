package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.common.Result;
import dev.mattest.supplychain.dto.contact.GlobalContactRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.GlobalContactService;
import dev.mattest.supplychain.vo.contact.GlobalContactVO;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/contact/global")
@SecurityRequirement(name = "adminSession")
public class AdminGlobalContactController {
    private final GlobalContactService globalContactService;

    public AdminGlobalContactController(GlobalContactService globalContactService) {
        this.globalContactService = globalContactService;
    }

    @GetMapping
    public Result<GlobalContactVO> get(@AuthenticationPrincipal AdminPrincipal actor) {
        return Result.success(globalContactService.get(actor));
    }

    @PutMapping
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<GlobalContactVO> save(
            @AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody GlobalContactRequest request) {
        return Result.success(globalContactService.save(actor, request));
    }
}
