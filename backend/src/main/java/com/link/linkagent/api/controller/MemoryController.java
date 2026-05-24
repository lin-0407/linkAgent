package com.link.linkagent.api.controller;

import com.link.linkagent.api.dto.LongTermMemoryResponse;
import com.link.linkagent.api.dto.LongTermMemorySaveRequest;
import com.link.linkagent.memory.LongTermMemory;
import com.link.linkagent.memory.LongTermMemoryRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 记忆管理接口。
 * 当前阶段只暴露长期记忆的手动读写，便于先验证 MySQL 存储链路。
 */
@Validated
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final LongTermMemory longTermMemory;

    public MemoryController(LongTermMemory longTermMemory) {
        this.longTermMemory = longTermMemory;
    }

    @PostMapping("/long-term")
    public LongTermMemoryResponse saveLongTermMemory(@Valid @RequestBody LongTermMemorySaveRequest request) {
        longTermMemory.save(request.userId(), request.memoryKey(), request.content(), request.sourceSessionId());
        return longTermMemory.findByKey(request.userId(), request.memoryKey())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "长期记忆不存在"));
    }

    @GetMapping("/long-term/users/{userId}")
    public List<LongTermMemoryResponse> listLongTermMemories(
            @PathVariable
            @NotBlank(message = "用户ID不能为空")
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId,

            @RequestParam(required = false)
            @Min(value = 1, message = "查询数量不能小于1")
            @Max(value = 100, message = "查询数量不能超过100")
            Integer limit) {
        return longTermMemory.listByUser(userId, limit).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/long-term/users/{userId}/keys/{memoryKey}")
    public LongTermMemoryResponse getLongTermMemory(
            @PathVariable
            @NotBlank(message = "用户ID不能为空")
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId,

            @PathVariable
            @NotBlank(message = "记忆键不能为空")
            @Size(max = 128, message = "记忆键长度不能超过128个字符")
            String memoryKey) {
        return longTermMemory.findByKey(userId, memoryKey)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "长期记忆不存在"));
    }

    private LongTermMemoryResponse toResponse(LongTermMemoryRecord record) {
        return new LongTermMemoryResponse(
                record.getId(),
                record.getUserId(),
                record.getMemoryKey(),
                record.getContent(),
                record.getSourceSessionId(),
                record.getEmbeddingId(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
