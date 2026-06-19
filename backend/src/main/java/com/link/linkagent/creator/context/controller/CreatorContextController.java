package com.link.linkagent.creator.context.controller;

import com.link.linkagent.creator.context.model.CreatorContextBundleResponse;
import com.link.linkagent.creator.context.model.CreatorContextTermCreateRequest;
import com.link.linkagent.creator.context.model.CreatorContextTermFeedbackRequest;
import com.link.linkagent.creator.context.model.CreatorContextTermResponse;
import com.link.linkagent.creator.context.service.CreatorContextService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

/**
 * 创作者语境库接口。
 * 第一版只开放用户显式维护和反馈闭环，不做后台自动抓取，避免污染创作者私有语境。
 */
@Validated
@RestController
@RequestMapping("/api/creator/context")
public class CreatorContextController {

    private final CreatorContextService creatorContextService;

    public CreatorContextController(CreatorContextService creatorContextService) {
        this.creatorContextService = creatorContextService;
    }

    @PostMapping("/terms")
    public CreatorContextTermResponse saveTerm(@Valid @RequestBody CreatorContextTermCreateRequest request) {
        return creatorContextService.saveTerm(request);
    }

    @GetMapping("/terms")
    public List<CreatorContextTermResponse> listTerms(
            @RequestParam(required = false)
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId,

            @RequestParam(required = false)
            @Size(max = 64, message = "视频类型长度不能超过64个字符")
            String videoType,

            @RequestParam(required = false)
            @Pattern(
                    regexp = "KEYWORD|SLANG|MEME|TABOO|TITLE_PATTERN|AUDIENCE_CONCERN",
                    message = "词条类型只能是 KEYWORD、SLANG、MEME、TABOO、TITLE_PATTERN 或 AUDIENCE_CONCERN"
            )
            String termType,

            @RequestParam(required = false)
            Boolean includeDisabled,

            @RequestParam(required = false)
            @Min(value = 1, message = "查询数量不能小于1")
            @Max(value = 100, message = "查询数量不能超过100")
            Integer limit) {
        return creatorContextService.listTerms(userId, videoType, termType, includeDisabled, limit);
    }

    @GetMapping("/bundle")
    public CreatorContextBundleResponse buildBundle(
            @RequestParam(required = false)
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId,

            @RequestParam(required = false)
            @Size(max = 64, message = "视频类型长度不能超过64个字符")
            String videoType,

            @RequestParam(required = false)
            @Size(max = 32, message = "场景长度不能超过32个字符")
            String scene) {
        return creatorContextService.buildBundle(userId, videoType, scene);
    }

    @DeleteMapping("/terms/{termId}")
    public CreatorContextTermResponse disableTerm(
            @PathVariable
            @NotBlank(message = "语境词条ID不能为空")
            @Size(max = 64, message = "语境词条ID长度不能超过64个字符")
            String termId) {
        return creatorContextService.disableTerm(termId);
    }

    @PostMapping("/terms/{termId}/feedback")
    public CreatorContextTermResponse recordFeedback(
            @PathVariable
            @NotBlank(message = "语境词条ID不能为空")
            @Size(max = 64, message = "语境词条ID长度不能超过64个字符")
            String termId,

            @Valid @RequestBody CreatorContextTermFeedbackRequest request) {
        return creatorContextService.recordFeedback(termId, request.accepted());
    }
}
