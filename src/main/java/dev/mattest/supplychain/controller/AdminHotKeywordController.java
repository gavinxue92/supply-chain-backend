package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.common.Result;
import dev.mattest.supplychain.dto.hotkeyword.HotKeywordsSaveRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.HotKeywordService;
import dev.mattest.supplychain.vo.hotkeyword.HotKeywordVO;
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

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/search/hot-keywords")
@SecurityRequirement(name = "adminSession")
public class AdminHotKeywordController {
    private final HotKeywordService hotKeywordService;

    public AdminHotKeywordController(HotKeywordService hotKeywordService) {
        this.hotKeywordService = hotKeywordService;
    }

    @GetMapping
    public Result<List<HotKeywordVO>> list(@AuthenticationPrincipal AdminPrincipal actor) {
        return Result.success(hotKeywordService.listForAdmin(actor));
    }

    @PutMapping
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<List<HotKeywordVO>> save(
            @AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody HotKeywordsSaveRequest request) {
        return Result.success(hotKeywordService.save(actor, request));
    }
}
