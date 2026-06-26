package com.link.linkagent.creator.autofill.controller;

import com.link.linkagent.creator.autofill.model.FieldAutofillRequest;
import com.link.linkagent.creator.autofill.model.FieldAutofillResponse;
import com.link.linkagent.creator.autofill.service.TaskAutofillService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务字段自动补全接口。
 * 前端输入框旁的 AI 按钮点击后调用，返回基于任务全局上下文的补全建议。
 */
@RestController
@RequestMapping("/api/creator/tasks/{taskId}")
public class TaskAutofillController {

    private final TaskAutofillService taskAutofillService;

    public TaskAutofillController(TaskAutofillService taskAutofillService) {
        this.taskAutofillService = taskAutofillService;
    }

    @PostMapping("/autofill")
    public FieldAutofillResponse autofillField(@PathVariable String taskId,
                                                @Valid @RequestBody FieldAutofillRequest request) {
        String suggestion = taskAutofillService.suggestField(taskId, request.fieldType());
        return new FieldAutofillResponse(request.fieldType(), suggestion);
    }
}
