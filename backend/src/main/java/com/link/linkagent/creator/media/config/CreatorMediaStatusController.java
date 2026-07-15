package com.link.linkagent.creator.media.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 媒体能力状态接口。
 * <p>
 * 前端只需要据此决定是否展示可选的成片上传步骤，避免在未配置对象存储时展示不可用入口。
 */
@RestController
@RequestMapping("/api/creator/media")
public class CreatorMediaStatusController {

    private final CreatorMediaProperties mediaProperties;

    public CreatorMediaStatusController(CreatorMediaProperties mediaProperties) {
        this.mediaProperties = mediaProperties;
    }

    @GetMapping("/status")
    public Map<String, Boolean> getStatus() {
        return Map.of("enabled", mediaProperties.isEnabled());
    }
}
