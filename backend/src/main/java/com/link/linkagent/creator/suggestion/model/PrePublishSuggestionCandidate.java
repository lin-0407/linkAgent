package com.link.linkagent.creator.suggestion.model;

import java.util.Objects;

/**
 * 发布方案尚未确认保存前的候选结果。
 * 工作流先审查候选，再统一保存，避免偏离用户意图的中间结果覆盖当前可用方案。
 */
public record PrePublishSuggestionCandidate(
        CreatorSuggestionRecord record
) {
    public PrePublishSuggestionCandidate {
        Objects.requireNonNull(record, "发布方案候选记录不能为空");
    }

    public String rawOutput() {
        return record.getRawOutput();
    }

    public String parseStatus() {
        return record.getParseStatus();
    }
}
