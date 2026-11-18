package dev.mattest.supplychain.controller;

import dev.mattest.supplychain.service.MediaResourceService;
import dev.mattest.supplychain.service.MediaResourceService.MediaContent;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/media")
public class PublicMediaController {
    private final MediaResourceService mediaResourceService;

    public PublicMediaController(MediaResourceService mediaResourceService) {
        this.mediaResourceService = mediaResourceService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> getImage(@PathVariable long id) {
        MediaContent media = mediaResourceService.readPublic(id);
        return ResponseEntity.ok()
                .contentType(media.mediaType())
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(media.bytes());
    }
}
