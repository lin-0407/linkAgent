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

    private final LongTermMemoryMapper longTermMemoryMapper;

    public LongTermMemory(LongTermMemoryMapper longTermMemoryMapper) {
        this.longTermMemoryMapper = longTermMemoryMapper;
    }

    public void save(String userId, String memoryKey, String content, String sourceSessionId) {
        LongTermMemoryRecord record = new LongTermMemoryRecord();
        record.setUserId(userId.trim());
        record.setMemoryKey(memoryKey.trim());
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
}
