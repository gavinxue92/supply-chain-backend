package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.common.Result;
import dev.mattest.supplychain.dto.media.UploadAuthorizationRequest;
import dev.mattest.supplychain.security.AdminPrincipal;
import dev.mattest.supplychain.service.MediaResourceService;
import dev.mattest.supplychain.vo.media.MediaResourceVO;
import dev.mattest.supplychain.vo.media.UploadAuthorizationVO;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/media")
@SecurityRequirement(name = "adminSession")
public class AdminMediaController {
    private final MediaResourceService mediaResourceService;

    public AdminMediaController(MediaResourceService mediaResourceService) {
        this.mediaResourceService = mediaResourceService;
    }

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<UploadAuthorizationVO> authorizeUpload(
            @AuthenticationPrincipal AdminPrincipal actor,
            @Valid @RequestBody UploadAuthorizationRequest request) {
        return Result.success(mediaResourceService.authorizeUpload(
                actor, request.originalFilename(), request.contentType()));
    }

    @PostMapping("/{id}/confirm")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "CSRF token returned by GET /api/v1/admin/auth/csrf.")
    public Result<MediaResourceVO> confirmUpload(
            @AuthenticationPrincipal AdminPrincipal actor,
            @PathVariable long id) {
        return Result.success(mediaResourceService.confirmUpload(actor, id));
    }
}
