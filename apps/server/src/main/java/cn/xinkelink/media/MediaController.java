package cn.xinkelink.media;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.ApiResponse;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.storage.ImageUpload;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@Profile("auth")
@RequestMapping("/api/v1/images")
public class MediaController {
    private final MediaService service;
    public MediaController(MediaService service) { this.service = service; }
    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<Map<String, Object>> upload(@AuthenticationPrincipal AuthPrincipal owner,
            @RequestParam("purpose") String purpose, @RequestParam("file") MultipartFile file) {
        try (var input = file.getInputStream()) {
            return ApiResponse.success(service.upload(owner, purpose, new ImageUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(), input)));
        } catch (IOException exception) { throw new BusinessException(ErrorCode.STORAGE_UNAVAILABLE); }
    }
    @GetMapping("/{imageId}/access")
    ApiResponse<Map<String, Object>> access(@PathVariable long imageId, @AuthenticationPrincipal AuthPrincipal viewer) {
        return ApiResponse.success(service.access(imageId, viewer));
    }
    @GetMapping("/{imageId}/content")
    ResponseEntity<byte[]> content(@PathVariable long imageId, @RequestParam String grant) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
                .header("Content-Type", "image/png").body(service.content(imageId, grant));
    }
    @DeleteMapping("/{imageId}")
    ApiResponse<Map<String, Boolean>> delete(@PathVariable long imageId, @AuthenticationPrincipal AuthPrincipal owner) {
        service.delete(imageId, owner); return ApiResponse.success(Map.of("deleted", true));
    }
}
