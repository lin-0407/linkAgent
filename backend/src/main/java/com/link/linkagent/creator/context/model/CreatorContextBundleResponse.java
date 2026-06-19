package com.link.linkagent.creator.context.model;

import java.util.List;

public record CreatorContextBundleResponse(
        String userId,
        String videoType,
        String scene,
        List<CreatorContextTermResponse> terms,
        List<String> keywords,
        List<String> slangTerms,
        List<String> titlePatterns,
        List<String> audienceConcerns,
        List<String> tabooTerms,
        String promptContext
) {
}
