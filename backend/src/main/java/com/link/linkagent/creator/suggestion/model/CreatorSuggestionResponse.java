package com.link.linkagent.creator.suggestion.model;

import java.time.LocalDateTime;

public record CreatorSuggestionResponse(
        Long id,
        String suggestionId,
        String taskId,
        String contentSummary,
        String audienceProfile,
        String sellingPoints,
        String riskPoints,
        String titleSuggestions,
        String descriptionSuggestion,
        String tagSuggestions,
        String partitionSuggestion,
        String rawOutput,
        String parseStatus,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
