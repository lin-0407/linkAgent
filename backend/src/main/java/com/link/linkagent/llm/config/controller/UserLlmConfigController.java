package com.link.linkagent.llm.config.controller;

import com.link.linkagent.llm.config.model.UserLlmConfigResponse;
import com.link.linkagent.llm.config.model.UserLlmConfigSaveRequest;
import com.link.linkagent.llm.config.service.UserLlmConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户 LLM/Embedding 配置接口（P1-4）。
 * <p>
 * 允许用户配置自己的 API Key、Base URL 和模型名称，
 * 所有 Key 以 AES-256-GCM 密文存储，前端只看到脱敏值。
 * <p>
 * <b>路由前缀：</b>{@code /api/settings/llm-config}
 * <p>
 * <b>端点一览：</b>
 * <ul>
 *   <li>{@code GET    /api/settings/llm-config} — 列出用户所有配置</li>
 *   <li>{@code POST   /api/settings/llm-config} — 保存/更新配置</li>
 *   <li>{@code DELETE /api/settings/llm-config/{configId}} — 软删除配置</li>
 *   <li>{@code POST   /api/settings/llm-config/{configId}/test} — 测试连通性</li>
 * </ul>
 */
@Validated
@RestController
@RequestMapping("/api/settings/llm-config")
public class UserLlmConfigController {

    private final UserLlmConfigService service;

    public UserLlmConfigController(UserLlmConfigService service) {
        this.service = service;
    }

    /**
     * 列出当前用户的所有 LLM/Embedding 配置。
     * API Key 返回脱敏值（如 sk-****j8x2）。
     *
     * @param userId 用户标识（可选，默认 "default"）
     * @return 配置列表
     */
    @GetMapping
    public List<UserLlmConfigResponse> listConfigs(
            @RequestParam(required = false)
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId) {
        return service.listConfigs(userId);
    }

    /**
     * 保存或更新一条 LLM/Embedding 配置。
     * 按 (user_id, provider) upsert：同用户同 provider 只保留一条。
     *
     * @param userId  用户标识（可选，默认 "default"）
     * @param request 配置保存请求
     * @return 保存后的配置（Key 脱敏）
     */
    @PostMapping
    public UserLlmConfigResponse saveConfig(
            @RequestParam(required = false)
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId,

            @Valid @RequestBody UserLlmConfigSaveRequest request) {
        return service.saveConfig(userId, request);
    }

    /**
     * 软删除一条 LLM/Embedding 配置。
     * 校验归属权限——只能删除自己的配置。
     *
     * @param configId 配置唯一标识
     * @param userId   用户标识（可选，默认 "default"）
     */
    @DeleteMapping("/{configId}")
    public void deleteConfig(
            @PathVariable
            @NotBlank(message = "配置ID不能为空")
            @Size(max = 64, message = "配置ID长度不能超过64个字符")
            String configId,

            @RequestParam(required = false)
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId) {
        service.deleteConfig(userId, configId);
    }

    /**
     * 测试 LLM 连通性：用用户配置的 Base URL + API Key 发起一次简单调用（"回复 OK"）。
     * <p>
     * 成功返回 {@code {"success": true, "elapsedMs": 230, "response": "OK"}}；
     * 失败返回 {@code {"success": false, "elapsedMs": 1200, "error": "401 Unauthorized"}}。
     *
     * @param configId 配置唯一标识
     * @param userId   用户标识（可选，默认 "default"）
     * @return 测试结果
     */
    @PostMapping("/{configId}/test")
    public Map<String, Object> testConnectivity(
            @PathVariable
            @NotBlank(message = "配置ID不能为空")
            @Size(max = 64, message = "配置ID长度不能超过64个字符")
            String configId,

            @RequestParam(required = false)
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId) {
        return service.testConnectivity(userId, configId);
    }
}
