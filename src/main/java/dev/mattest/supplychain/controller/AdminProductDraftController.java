package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.common.Result;
import dev.mattest.supplychain.dto.catalog.ProductDraftRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.ProductDraftService;
import dev.mattest.supplychain.vo.catalog.ProductDraftVO;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/products")
@SecurityRequirement(name = "adminSession")
public class AdminProductDraftController {
    private final ProductDraftService productDraftService;

    public AdminProductDraftController(ProductDraftService productDraftService) {
        this.productDraftService = productDraftService;
    }

    @GetMapping
    public Result<List<ProductDraftVO>> listDrafts() {
        return Result.success(productDraftService.list());
    }

    @GetMapping("/{id}")
    public Result<ProductDraftVO> getDraft(@PathVariable long id) {
        return Result.success(productDraftService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<ProductDraftVO> createDraft(
            @AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody ProductDraftRequest request) {
        return Result.success(productDraftService.create(actor, request));
    }

    @PutMapping("/{id}")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<ProductDraftVO> updateDraft(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id,
            @Valid @RequestBody ProductDraftRequest request) {
        return Result.success(productDraftService.update(actor, id, request));
    }

    @PostMapping("/{id}/publish")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<ProductDraftVO> publish(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id) {
        return Result.success(productDraftService.publish(actor, id));
    }

    @PostMapping("/{id}/unpublish")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<ProductDraftVO> unpublish(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id) {
        return Result.success(productDraftService.unpublish(actor, id));
    }

    @PostMapping("/{id}/archive")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<ProductDraftVO> archive(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id) {
        return Result.success(productDraftService.archive(actor, id));
    }

    @PostMapping("/{id}/restore")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<ProductDraftVO> restore(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id) {
        return Result.success(productDraftService.restore(actor, id));
    }
}
