package com.link.linkagent.knowledge.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ReferenceAutoCollectionResponse(
        String runId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        String message,
        ReferenceAutoCollectionRequest settings,
        JsonNode report
) {}
