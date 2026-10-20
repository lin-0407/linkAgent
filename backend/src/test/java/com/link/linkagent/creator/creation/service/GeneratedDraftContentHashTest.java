package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 授权绑定内容，重试只换运行身份时不要求重新授权，也不能偷偷更换内容。 */
class GeneratedDraftContentHashTest {
    private final ObjectMapper json = new ObjectMapper();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "1,7c2768966af9de0160206f5aac5bff4dc4825c781323b7974cb566b9ee8e0eff",
            "1.0,3fda219902378725d7d52d4969b58c77c06618cc1159b154f814fbefb02efc03",
            "-0.0,ed1714b813573d19b6094d4a3566b7970dfe04a082f572731ec7a99d1e59bf55",
            "-0.0001,a4de44a59e32475fa00d5aec29a20acf3b9345eee722a3cce5c5e8d2dfd99d82",
            "-0.1,d42f706842d3abddddc4d3b086e34a0313d274dafe3e3ca134dfdb2f28a29d45",
            "-1e-6,11e36a5dd65f056abadd139af5e78f9de2a26073c5aee85ff9101fcfc9754932",
            "-1e-300,7456c88f32a389dfd327b55f12e245c9d4174218252468e5afd23a18ef178094",
            "-5e-324,11721a3b8ed1af8557dd261ce5451463d3fae29b62846e651cf7ace87dc11ad0"})
    void sharedPythonNumericVectorsMatchActualJson(String number, String expected) throws Exception {
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("{\"gainDb\":" + number + "}");
        assertThat(CreationInputRules.renderInputHash(json, document)).isEqualTo(expected);
        assertThat(CreationInputRules.renderContentHash(json, document.toString())).isEqualTo(expected);
    }

    @Test void floatNodesUseTheValueActuallyWrittenToJsonAndRejectNonFiniteBeforeSerialization() throws Exception {
        var document = json.createObjectNode().put("gainDb", 0.1f);
        var roundTrip = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(document.toString());
        assertThat(CreationInputRules.renderInputHash(json, document)).isEqualTo(CreationInputRules.renderInputHash(json, roundTrip));
        document.put("gainDb", Double.POSITIVE_INFINITY);
        assertThatThrownBy(() -> CreationInputRules.renderInputHash(json, document)).hasMessageContaining("有限");
        assertThatThrownBy(() -> CreationInputRules.renderDocument(json, "{\"gainDb\":1e999}")).hasMessageContaining("有限");
    }

    @Test void floatingTokenCannotCollideWithUserText() throws Exception {
        assertThat(CreationInputRules.renderContentHash(json, "{\"gainDb\":-0.0001}"))
                .isNotEqualTo(CreationInputRules.renderContentHash(json, "{\"gainDb\":\"f64:bf1a36e2eb1c432d\"}"));
    }

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
