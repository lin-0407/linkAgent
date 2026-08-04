package com.link.linkagent.memory;

import com.link.linkagent.util.TextUtil;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 长期记忆入口。
 * 先只封装 MySQL 读写，后续再在这里接入 LLM 抽取和向量检索。
 */
@Component
public class LongTermMemory {

    private static final String EXAMPLE_LANGUAGE_KEY = "user.preference.example_language";
    private static final String EXPLANATION_STYLE_KEY = "user.preference.explanation_style";
    private static final String USER_PROFILE_KEY = "user.profile.summary";
    private static final String PROJECT_PROFILE_KEY = "project.profile.summary";
    private static final String PROJECT_CONSTRAINT_KEY = "project.constraint.summary";

    private final LongTermMemoryMapper longTermMemoryMapper;

    public LongTermMemory(LongTermMemoryMapper longTermMemoryMapper) {
        this.longTermMemoryMapper = longTermMemoryMapper;
    }

    public void save(String userId, String memoryKey, String content, String sourceSessionId) {
        LongTermMemoryRecord record = new LongTermMemoryRecord();
        record.setUserId(userId.trim());
        record.setMemoryKey(normalizeMemoryKey(memoryKey));
        record.setContent(content.trim());
        record.setSourceSessionId(TextUtil.trimToNull(sourceSessionId));
        longTermMemoryMapper.upsert(record);
    }

    public Optional<LongTermMemoryRecord> findByKey(String userId, String memoryKey) {
        return longTermMemoryMapper.findByKey(userId.trim(), normalizeMemoryKey(memoryKey));
    }

    public List<LongTermMemoryRecord> listByUser(String userId) {
        return longTermMemoryMapper.listByUser(userId.trim());
    }

    public List<LongTermMemoryRecord> listRecentByUser(String userId, int limit) {
        // Agent 上下文只需要少量近期记忆，避免把管理页的全量查询语义带进 Prompt 拼接。
        int safeLimit = Math.max(limit, 1);
        return longTermMemoryMapper.listRecentByUser(userId.trim(), safeLimit);
    }

    public void delete(String userId, String memoryKey) {
        longTermMemoryMapper.softDelete(userId.trim(), normalizeMemoryKey(memoryKey));
    }

    public Optional<LongTermMemoryRecord> restore(String userId, String memoryKey) {
        String normalizedUserId = userId.trim();
        String normalizedMemoryKey = normalizeMemoryKey(memoryKey);
        // 撤销只恢复软删除标记，不能借用 save 覆盖原内容、来源会话或向量标识。
        longTermMemoryMapper.restore(normalizedUserId, normalizedMemoryKey);
        return longTermMemoryMapper.findByKey(normalizedUserId, normalizedMemoryKey);
    }

    private String normalizeMemoryKey(String memoryKey) {
        String normalized = memoryKey.trim().toLowerCase();
        if (containsAny(normalized, "language", "programming_language", "example")) {
            return EXAMPLE_LANGUAGE_KEY;
        }
        if (containsAny(normalized, "explain", "explanation", "style", "answer_style")) {
            return EXPLANATION_STYLE_KEY;
        }
        if (normalized.startsWith("user.profile") || containsAny(normalized, "learning", "career", "role")) {
            return USER_PROFILE_KEY;
        }
        if (normalized.startsWith("project.profile") || containsAny(normalized, "stack", "goal", "portfolio")) {
            return PROJECT_PROFILE_KEY;
        }
        if (normalized.startsWith("project.constraint") || containsAny(normalized, "constraint", "rule")) {
            return PROJECT_CONSTRAINT_KEY;
        }
        return normalized;
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
