package com.link.linkagent.memory;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 长期记忆入口。
 * 先只封装 MySQL 读写，后续再在这里接入 LLM 抽取和向量检索。
 */
@Component
public class LongTermMemory {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
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
        record.setSourceSessionId(normalizeBlank(sourceSessionId));
        longTermMemoryMapper.upsert(record);
    }

    public Optional<LongTermMemoryRecord> findByKey(String userId, String memoryKey) {
        return longTermMemoryMapper.findByKey(userId.trim(), memoryKey.trim());
    }

    public List<LongTermMemoryRecord> listByUser(String userId, Integer limit) {
        int safeLimit = normalizeLimit(limit);
        return longTermMemoryMapper.listByUser(userId.trim(), safeLimit);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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
