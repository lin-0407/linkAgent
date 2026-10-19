package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 授权绑定内容，重试只换运行身份时不要求重新授权，也不能偷偷更换内容。 */
class GeneratedDraftContentHashTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void retryIdentityDoesNotChangeApprovedContentButTextDoes() {
        String first = "{\"runId\":\"one\",\"attemptId\":\"one\",\"inputHash\":\"first\",\"title\":\"原文\"}";
        String retry = "{\"title\":\"原文\",\"inputHash\":\"second\",\"attemptId\":\"two\",\"runId\":\"two\"}";
        assertThat(CreationInputRules.renderContentHash(json, first)).isEqualTo(CreationInputRules.renderContentHash(json, retry));
        assertThat(CreationInputRules.renderContentHash(json, retry.replace("原文", "修改")))
                .isNotEqualTo(CreationInputRules.renderContentHash(json, first));
    }

    @Test void renderingContentUsesRendererLimitWhileControlRequestsRemainBounded() throws Exception {
        String project = json.writeValueAsString(java.util.Map.of("text", "中".repeat(30000)));
        assertThat(CreationInputRules.renderContentHash(json, project)).hasSize(64);
        assertThatThrownBy(() -> CreationInputRules.canonical(json, project)).hasMessageContaining("过大");
    }

    @Test void renderingContentStillRejectsOverLimitAndDuplicateFields() {
        assertThatThrownBy(() -> CreationInputRules.renderContentHash(json, "{\"text\":\"" + "中".repeat(90000) + "\"}"))
                .hasMessageContaining("过大");
        assertThatThrownBy(() -> CreationInputRules.renderContentHash(json, "{\"title\":\"one\",\"title\":\"two\"}"))
                .hasMessageContaining("有效");
    }
}
