package com.link.linkagent.creator.creation.asset.model;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

public final class AssetRequests {
    private AssetRequests() { }

    public record Create(
            @NotBlank @Pattern(regexp = "IMAGE|NARRATION|MUSIC|VIDEO_CLIP") String kind,
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 100) String contentType,
            @Min(1) @Max(1073741824L) long fileSize,
            @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String sha256) { }

    /** 响应只含素材事实；对象键、存储句柄和短签均不持久暴露给页面。 */
    public record Snapshot(String assetId, String kind, String fileName, long fileSize, String sha256,
            String status, String failedStage, String failureCode, String failureMessage, JsonNode metadata,
            long partSize, int totalParts, List<Part> parts, boolean cleanupPending,
            LocalDateTime createTime, LocalDateTime updateTime) { }

    public record Part(int partNumber, String etag, long partSize) { }
    public record StoredFile(String role, String objectKey, String contentType, long bytes, String sha256) { }
}
