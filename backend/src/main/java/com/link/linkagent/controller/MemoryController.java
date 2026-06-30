package com.link.linkagent.controller;

import com.link.linkagent.dto.LongTermMemoryResponse;
import com.link.linkagent.dto.LongTermMemorySaveRequest;
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
 * <p>
 * <b>架构定位：</b>
 * 当前阶段只暴露长期记忆的手动 CRUD 接口，用于验证 MySQL 存储链路
 * （写入 → 持久化 → 按用户查询 → 按 key 精确查询）。
 * 后续阶段会集成 Milvus 向量检索，实现语义相似记忆的自动召回。
 * <p>
 * <b>为什么先手动读写：</b>
 * 自动记忆提取（从 Agent 对话中由 LLM 判断哪些信息值得长期记住）依赖
 * 稳定的对话流程和工具链，在项目早期阶段，手动 API 更便于调试和验证存储层正确性。
 * <p>
 * <b>路由前缀：</b>{@code /api/memory}
 * <p>
 * <b>方法级校验说明：</b>
 * 类级 {@code @Validated} 注解激活了方法参数的校验（@NotBlank/@Size/@Min/@Max），
 * 与请求体 DTO 中 {@code @Valid} 的职责不同：
 * <ul>
 *   <li>DTO 的 @Valid → 校验嵌套对象的字段</li>
 *   <li>方法参数的 @NotBlank 等 → 校验路径参数和查询参数</li>
 * </ul>
 * <p>
 * <b>端点一览：</b>
 * <ul>
 *   <li>{@code POST /api/memory/long-term} — 保存长期记忆</li>
 *   <li>{@code GET  /api/memory/long-term/users/{userId}} — 列出用户的长期记忆</li>
 *   <li>{@code GET  /api/memory/long-term/users/{userId}/keys/{memoryKey}} — 按 key 精确查询</li>
 * </ul>
 *
 * @see LongTermMemory MySQL 长期记忆存储服务
 */
@Validated
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final LongTermMemory longTermMemory;

    public MemoryController(LongTermMemory longTermMemory) {
        this.longTermMemory = longTermMemory;
    }

    /**
     * 保存一条长期记忆。
     * <p>
     * <b>端点：</b>{@code POST /api/memory/long-term}
     * <p>
     * <b>请求格式：</b>
     * <pre>{@code
     * {
     *   "userId": "user-123",            // 必填，最大 64 字符
     *   "memoryKey": "preferred_style",  // 必填，最大 128 字符
     *   "content": "用户偏好简洁风格...", // 必填，最大 2000 字符
     *   "sourceSessionId": "sess-456"    // 可选，记录来源会话
     * }
     * }</pre>
     * <p>
     * <b>响应格式：</b>见 {@link LongTermMemoryResponse}，包含完整记忆记录（含数据库生成的 id 和时间戳）。
     * <p>
     * <b>幂等性说明：</b>
     * (userId, memoryKey) 是业务唯一键 —— 相同用户下重复保存同一 key 会覆盖旧内容
     * （实现层用 INSERT ON DUPLICATE KEY UPDATE 或先删后插）。
     *
     * @param request 保存请求
     * @return 保存后的完整记忆记录
     * @throws ResponseStatusException 404 如果保存后无法回读（理论上不应发生）
     */
    @PostMapping("/long-term")
    public LongTermMemoryResponse saveLongTermMemory(@Valid @RequestBody LongTermMemorySaveRequest request) {
        longTermMemory.save(request.userId(), request.memoryKey(), request.content(), request.sourceSessionId());
        return longTermMemory.findByKey(request.userId(), request.memoryKey())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "长期记忆不存在"));
    }

    /**
     * 列出指定用户的所有长期记忆。
     * <p>
     * <b>端点：</b>{@code GET /api/memory/long-term/users/{userId}?limit=20}
     * <p>
     * <b>路径参数：</b>
     * <ul>
     *   <li>{@code userId} — 用户唯一标识，必填，最大 64 字符</li>
     * </ul>
     * <b>查询参数：</b>
     * <ul>
     *   <li>{@code limit} — 返回条数上限，可选，范围 [1, 100]</li>
     * </ul>
     * <p>
     * <b>用途：</b>前端"我的记忆"面板，展示用户已存储的全部长期记忆。
     *
     * @param userId 用户唯一标识
     * @param limit  返回数量上限（可选，默认由服务层控制）
     * @return 长期记忆列表
     */
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

    /**
     * 按 (userId, memoryKey) 精确查询一条长期记忆。
     * <p>
     * <b>端点：</b>{@code GET /api/memory/long-term/users/{userId}/keys/{memoryKey}}
     * <p>
     * <b>路径参数：</b>
     * <ul>
     *   <li>{@code userId} — 用户唯一标识</li>
     *   <li>{@code memoryKey} — 记忆键（如 "writing_style"、"content_preferences"）</li>
     * </ul>
     * <p>
     * <b>用途：</b>按 key 精确读取特定类型的长期记忆，通常在 Agent 对话中按需加载上下文时使用。
     *
     * @param userId    用户唯一标识
     * @param memoryKey 记忆键
     * @return 对应的长期记忆记录
     * @throws ResponseStatusException 404 如果指定 key 的记忆不存在
     */
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

    /**
     * 将持久层实体 {@link LongTermMemoryRecord} 转换为 API 响应 DTO。
     * 转换过程不丢字段，保证前端能拿到完整的数据库记录（包括内部 id 和时间戳）。
     */
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
