package com.link.linkagent.creator.evaluation.controller;

import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseCreateRequest;
import com.link.linkagent.creator.evaluation.model.CreatorEvalCaseResponse;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultCreateRequest;
import com.link.linkagent.creator.evaluation.model.CreatorEvalResultResponse;
import com.link.linkagent.creator.evaluation.service.CreatorEvaluationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测集接口。
 * 这一组接口服务的是人工评分和失败回放，不直接插入创作主流程，避免把演示项目做成复杂评测平台。
 */
@Validated
@RestController
@RequestMapping("/api/creator/evaluations")
public class CreatorEvaluationController {

    private final CreatorEvaluationService creatorEvaluationService;

    public CreatorEvaluationController(CreatorEvaluationService creatorEvaluationService) {
        this.creatorEvaluationService = creatorEvaluationService;
    }

    @PostMapping("/cases")
    public CreatorEvalCaseResponse createCase(@Valid @RequestBody CreatorEvalCaseCreateRequest request) {
        return creatorEvaluationService.createCase(request);
    }

    @GetMapping("/cases")
    public List<CreatorEvalCaseResponse> listCases(
            @RequestParam(required = false)
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId,

            @RequestParam(required = false)
            @Pattern(regexp = "PRE_PUBLISH|FEEDBACK|REPORT", message = "评测阶段只能是 PRE_PUBLISH、FEEDBACK 或 REPORT")
            String targetStage,

            @RequestParam(required = false)
            @Positive(message = "分页上限必须大于0")
            @Max(value = 100, message = "分页上限不能超过100")
            Integer limit) {
        return creatorEvaluationService.listCases(userId, targetStage, limit);
    }

    @GetMapping("/cases/{caseId}")
    public CreatorEvalCaseResponse getCase(
            @PathVariable
            @NotBlank(message = "评测用例ID不能为空")
            @Size(max = 64, message = "评测用例ID长度不能超过64个字符")
            String caseId) {
        return creatorEvaluationService.getCase(caseId);
    }

    @PostMapping("/cases/{caseId}/results")
    public CreatorEvalResultResponse recordResult(
            @PathVariable
            @NotBlank(message = "评测用例ID不能为空")
            @Size(max = 64, message = "评测用例ID长度不能超过64个字符")
            String caseId,

            @Valid @RequestBody CreatorEvalResultCreateRequest request) {
        return creatorEvaluationService.recordResult(caseId, request);
    }

    @GetMapping("/cases/{caseId}/results")
    public List<CreatorEvalResultResponse> listResults(
            @PathVariable
            @NotBlank(message = "评测用例ID不能为空")
            @Size(max = 64, message = "评测用例ID长度不能超过64个字符")
            String caseId,

            @RequestParam(required = false)
            @Positive(message = "分页上限必须大于0")
            @Max(value = 100, message = "分页上限不能超过100")
            Integer limit) {
        return creatorEvaluationService.listResults(caseId, limit);
    }
}
