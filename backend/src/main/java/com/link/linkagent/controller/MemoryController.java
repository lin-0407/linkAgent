package com.link.linkagent.controller;

import com.link.linkagent.dto.LongTermMemoryResponse;
import com.link.linkagent.dto.LongTermMemorySaveRequest;
import com.link.linkagent.memory.LongTermMemory;
import com.link.linkagent.memory.LongTermMemoryRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 记忆管理接口。
 * <p>
 * <b>架构定位：</b>
 * 暴露长期记忆的手动 CRUD 接口，支撑前端「我的记忆」管理页的浏览、搜索、删除与撤销删除。
 * 自动写入由摘要压缩链路与长期记忆抽取协调器负责，这个控制器只处理用户手动操作。
 * <p>
 * <b>记录身份：</b>
 * 长期记忆的身份是数据库主键 {@code id}（设计文档 D13 / E1）。{@code memoryKey} 已降级为分类展示字段，
 * 同一 key 下允许存在多条不同事实，因此按 key 访问的旧端点已移除——按 key 删除会删错行。
 * <p>
 * <b>路由前缀：</b>{@code /api/memory}
 * <p>
 * <b>方法级校验说明：</b>
 * 类级 {@code @Validated} 注解激活了方法参数的校验（@NotBlank/@Size/@Positive），
 * 与请求体 DTO 中 {@code @Valid} 的职责不同：
 * <ul>
 *   <li>DTO 的 @Valid → 校验嵌套对象的字段</li>
 *   <li>方法参数的 @NotBlank 等 → 校验路径参数和查询参数</li>
 * </ul>
 * <p>
 * <b>端点一览：</b>
 * <ul>
 *   <li>{@code POST   /api/memory/long-term} — 新建一条长期记忆</li>
 *   <li>{@code GET    /api/memory/long-term/users/{userId}} — 列出用户的长期记忆</li>
 *   <li>{@code GET    /api/memory/long-term/users/{userId}/memories/{memoryId}} — 按 id 精确查询</li>
 *   <li>{@code DELETE /api/memory/long-term/users/{userId}/memories/{memoryId}} — 用户主动删除（可撤销）</li>
 *   <li>{@code POST   /api/memory/long-term/users/{userId}/memories/{memoryId}/restore} — 撤销删除</li>
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
     * 新建一条长期记忆。
     * <p>
     * <b>端点：</b>{@code POST /api/memory/long-term}
     * <p>
     * <b>请求格式：</b>
     * <pre>{@code
     * {
     *   "userId": "user-123",            // 必填，最大 64 字符
     *   "memoryKey": "preferred_style",  // 必填，最大 128 字符；只作分类标签
     *   "content": "用户偏好简洁风格...", // 必填，最大 2000 字符
     *   "sourceSessionId": "sess-456"    // 可选，记录来源会话
     * }
     * }</pre>
     * <p>
     * <b>响应格式：</b>见 {@link LongTermMemoryResponse}，包含完整记忆记录（含数据库生成的 id 和时间戳）。
     * <p>
     * <b>写入语义：</b>每次调用新增一条记录，不会按 memoryKey 覆盖已有记忆。同 key 的取舍由自动写入路径的
     * 决策逻辑负责，用户手动新建时按「想加就加」处理，避免手写内容被后写的内容悄悄顶掉。
     *
     * @param request 保存请求
     * @return 新建后的完整记忆记录
     * @throws ResponseStatusException 404 如果保存后无法回读（理论上不应发生）
     */
    @PostMapping("/long-term")
    public LongTermMemoryResponse saveLongTermMemory(@Valid @RequestBody LongTermMemorySaveRequest request) {
        return longTermMemory.save(request.userId(), request.memoryKey(), request.content(), request.sourceSessionId())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "长期记忆不存在"));
    }

    /**
     * 列出指定用户的所有长期记忆。
     * <p>
     * <b>端点：</b>{@code GET /api/memory/long-term/users/{userId}}
     * <p>
     * <b>路径参数：</b>
     * <ul>
     *   <li>{@code userId} — 用户唯一标识，必填，最大 64 字符</li>
     * </ul>
     * <b>用途：</b>前端"我的记忆"面板，展示用户已存储的全部长期记忆。当前项目是单用户创作工作台，
     * 管理页需要完整浏览、搜索和删除记忆，因此这里不再做 limit 限制。
     *
     * @param userId 用户唯一标识
     * @return 长期记忆列表
     */
    @GetMapping("/long-term/users/{userId}")
    public List<LongTermMemoryResponse> listLongTermMemories(
            @PathVariable
            @NotBlank(message = "用户ID不能为空")
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId) {
        return longTermMemory.listByUser(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 按记忆 id 精确查询一条长期记忆。
     * <p>
     * <b>端点：</b>{@code GET /api/memory/long-term/users/{userId}/memories/{memoryId}}
     * <p>
     * <b>用途：</b>管理页打开某条记忆详情时按身份读取。id 是数据库主键，同一条记忆内容更新后 id 不变。
     *
     * @param userId   用户唯一标识
     * @param memoryId 记忆 id
     * @return 对应的长期记忆记录
     * @throws ResponseStatusException 404 如果该 id 的记忆不存在、已删除或不属于该用户
     */
    @GetMapping("/long-term/users/{userId}/memories/{memoryId}")
    public LongTermMemoryResponse getLongTermMemory(
            @PathVariable
            @NotBlank(message = "用户ID不能为空")
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId,

            @PathVariable
            @NotNull(message = "记忆ID不能为空")
            @Positive(message = "记忆ID必须为正整数")
            Long memoryId) {
        return longTermMemory.findById(userId, memoryId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "长期记忆不存在"));
    }

    /**
     * 用户主动删除一条长期记忆。
     * <p>
     * 这里使用软删除而不是物理删除，是为了保留后续问题排查和记忆误抽取复盘的可能性；
     * 同时打上 deleted_by_user 标记，使它能被撤销恢复，与被新事实取代的失效记忆区分开（设计文档 D15）。
     */
    @DeleteMapping("/long-term/users/{userId}/memories/{memoryId}")
    public void deleteLongTermMemory(
            @PathVariable
            @NotBlank(message = "用户ID不能为空")
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId,

            @PathVariable
            @NotNull(message = "记忆ID不能为空")
            @Positive(message = "记忆ID必须为正整数")
            Long memoryId) {
        longTermMemory.delete(userId, memoryId);
    }

    /**
     * 撤销一条长期记忆的删除。
     * <p>
     * 恢复端点只翻转删除标记，并返回数据库中的当前记录。这样不会像重新保存一样覆盖
     * 原内容、来源会话或向量标识；重复调用时也会返回已经恢复的记录（设计文档 D15）。
     */
    @PostMapping("/long-term/users/{userId}/memories/{memoryId}/restore")
    public LongTermMemoryResponse restoreLongTermMemory(
            @PathVariable
            @NotBlank(message = "用户ID不能为空")
            @Size(max = 64, message = "用户ID长度不能超过64个字符")
            String userId,

            @PathVariable
            @NotNull(message = "记忆ID不能为空")
            @Positive(message = "记忆ID必须为正整数")
            Long memoryId) {
        return longTermMemory.restore(userId, memoryId)
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
                record.getImportance(),
                record.getSourceSessionId(),
                record.getEmbeddingId(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
