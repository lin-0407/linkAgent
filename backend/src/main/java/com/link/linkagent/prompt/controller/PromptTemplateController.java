package com.link.linkagent.prompt.controller;

import com.link.linkagent.prompt.dto.UpdatePromptTemplateRequest;
import com.link.linkagent.prompt.model.PromptTemplate;
import com.link.linkagent.prompt.service.PromptService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提示词模板管理接口。
 * 读接口（列出全部 / 按 key 查正文，5.5-1）+ 写接口（按 key 改正文、改完即时生效的热更新，5.5-2a）。
 * 本接口只读写新建的 llm_prompt_template 表，不碰任何现有 Service——把现有调用处切到按 key 取词放在 5.5-2b。
 */
@Validated
@RestController
@RequestMapping("/api/prompt-templates")
public class PromptTemplateController {

    private final PromptService promptService;

    public PromptTemplateController(PromptService promptService) {
        this.promptService = promptService;
    }

    /**
     * 列出全部提示词（含场景、类型、说明），给前端按场景分组展示用。
     */
    @GetMapping
    public List<PromptTemplate> listPromptTemplates() {
        return promptService.listAll();
    }

    /**
     * 按 key 查一条提示词的正文。
     * 复用取词方法 get(key)：key 不存在时直接响亮报错（fail-loud），而不是返回空串误导调用方。
     */
    @GetMapping("/{key}")
    public String getPromptTemplate(
            @PathVariable
            @NotBlank(message = "提示词 key 不能为空")
            @Size(max = 128, message = "提示词 key 长度不能超过128个字符")
            String key) {
        return promptService.get(key);
    }

    /**
     * 改写一条提示词的正文，改完即时生效（热更新）。
     * 写库后 PromptService 会把缓存里这条直接换成新值，下一次模型调用就用上新词、无需重启。
     * key 不存在时返回 404：只能改已存在的提示词，不允许凭空新建——key 与代码调用处一一对应。
     */
    @PutMapping("/{key}")
    public void updatePromptTemplate(
            @PathVariable
            @NotBlank(message = "提示词 key 不能为空")
            @Size(max = 128, message = "提示词 key 长度不能超过128个字符")
            String key,
            @RequestBody @Valid UpdatePromptTemplateRequest request) {
        promptService.update(key, request.content());
    }
}
