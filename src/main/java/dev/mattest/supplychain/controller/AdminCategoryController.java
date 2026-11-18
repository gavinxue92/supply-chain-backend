package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.common.Result;
import dev.mattest.supplychain.dto.catalog.CategoryRequest;
import dev.mattest.supplychain.dto.catalog.CategoryStatusRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.CategoryService;
import dev.mattest.supplychain.vo.catalog.CategoryVO;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/categories")
@SecurityRequirement(name = "adminSession")
public class AdminCategoryController {
    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public Result<List<CategoryVO>> list() {
        return Result.success(categoryService.list());
    }

    @GetMapping("/{id}")
    public Result<CategoryVO> get(@PathVariable long id) {
        return Result.success(categoryService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<CategoryVO> create(
            @AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody CategoryRequest request) {
        return Result.success(categoryService.create(actor, request));
    }

    @PutMapping("/{id}")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<CategoryVO> update(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id,
            @Valid @RequestBody CategoryRequest request) {
        return Result.success(categoryService.update(actor, id, request));
    }

    @PatchMapping("/{id}/status")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<Void> setStatus(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id,
            @Valid @RequestBody CategoryStatusRequest request) {
        categoryService.setActive(actor, id, request.active());
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<Void> delete(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id) {
        categoryService.delete(actor, id);
        return Result.success(null);
    }
}
