package com.link.linkagent.creator.production.controller;

import com.link.linkagent.creator.production.model.CreateProductionPlanRequest;
import com.link.linkagent.creator.production.model.ProductionWorkspaceResponse;
import com.link.linkagent.creator.production.model.UpdateProductionStepRequest;
import com.link.linkagent.creator.production.service.ProductionPlanApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 阶段 7 P0-1 制作蓝图接口。 */
@Validated
@RestController
@RequestMapping("/api/creator/tasks/{taskId}")
public class ProductionPlanController {

    private final ProductionPlanApplicationService applicationService;

    public ProductionPlanController(ProductionPlanApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping("/production-plan/current")
    public ProductionWorkspaceResponse getCurrent(
            @PathVariable @NotBlank @Size(max = 64) String taskId) {
        return applicationService.getCurrent(taskId);
    }

    @PostMapping("/production-plans")
    public ProductionWorkspaceResponse create(
            @PathVariable @NotBlank @Size(max = 64) String taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateProductionPlanRequest request) {
        return applicationService.create(taskId, request, idempotencyKey);
    }

    @PatchMapping("/production-plans/{planId}/steps/{stepId}")
    public ProductionWorkspaceResponse updateStep(
            @PathVariable @NotBlank @Size(max = 64) String taskId,
            @PathVariable @NotBlank @Size(max = 64) String planId,
            @PathVariable @NotBlank @Size(max = 64) String stepId,
            @Valid @RequestBody UpdateProductionStepRequest request) {
        return applicationService.updateStep(taskId, planId, stepId, request);
    }
}
