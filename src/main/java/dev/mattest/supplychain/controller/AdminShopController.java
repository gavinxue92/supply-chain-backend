package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.common.Result;
import dev.mattest.supplychain.dto.shop.ShopInfoRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.ShopInfoService;
import dev.mattest.supplychain.vo.shop.ShopInfoVO;
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
@RequestMapping("/api/v1/admin/shop")
@SecurityRequirement(name = "adminSession")
public class AdminShopController {
    private final ShopInfoService shopInfoService;

    public AdminShopController(ShopInfoService shopInfoService) {
        this.shopInfoService = shopInfoService;
    }

    @GetMapping
    public Result<ShopInfoVO> get(@AuthenticationPrincipal AdminPrincipal actor) {
        return Result.success(shopInfoService.getForAdmin(actor));
    }

    @PutMapping
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<ShopInfoVO> save(
            @AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody ShopInfoRequest request) {
        return Result.success(shopInfoService.save(actor, request));
    }
}
