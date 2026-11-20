package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.common.Result;
import dev.mattest.supplychain.dto.contact.CustomerServiceRequest;
import dev.mattest.supplychain.dto.contact.CustomerServiceStatusRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.CustomerServiceService;
import dev.mattest.supplychain.vo.contact.CustomerServiceVO;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/admin/customer-services")
@SecurityRequirement(name = "adminSession")
public class AdminCustomerServiceController {
    private final CustomerServiceService customerServiceService;

    public AdminCustomerServiceController(CustomerServiceService customerServiceService) {
        this.customerServiceService = customerServiceService;
    }

    @GetMapping
    public Result<List<CustomerServiceVO>> list() {
        return Result.success(customerServiceService.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<CustomerServiceVO> create(
            @AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody CustomerServiceRequest request) {
        return Result.success(customerServiceService.create(actor, request));
    }

    @PutMapping("/{id}")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<CustomerServiceVO> update(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id,
            @Valid @RequestBody CustomerServiceRequest request) {
        return Result.success(customerServiceService.update(actor, id, request));
    }

    @PatchMapping("/{id}/active")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<Void> setActive(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id,
            @Valid @RequestBody CustomerServiceStatusRequest request) {
        customerServiceService.setActive(actor, id, request);
        return Result.success(null);
    }

    @DeleteMapping("/{id}")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<Void> hardDelete(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id) {
        customerServiceService.hardDelete(actor, id);
        return Result.success(null);
    }
}
