package com.link.linkagent.knowledge.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BilibiliCoverUrlPolicyTest {

    @Test
    void shouldNormalizeTrustedBilibiliCoverUrlsToHttps() {
        assertThat(BilibiliCoverUrlPolicy.normalize("//i0.hdslb.com/bfs/archive/cover.jpg"))
                .isEqualTo("https://i0.hdslb.com/bfs/archive/cover.jpg");
        assertThat(BilibiliCoverUrlPolicy.normalize("http://i1.hdslb.com/bfs/archive/cover.jpg"))
                .isEqualTo("https://i1.hdslb.com/bfs/archive/cover.jpg");
    }

    @Test
    void shouldRejectUntrustedOrTemporaryCoverUrls() {
        assertThat(BilibiliCoverUrlPolicy.normalize(
                "https://i0.hdslb.com.example.com/bfs/archive/cover.jpg")).isNull();
        assertThat(BilibiliCoverUrlPolicy.normalize(
                "https://example.com/cover.jpg")).isNull();
        assertThat(BilibiliCoverUrlPolicy.normalize(
                "https://i0.hdslb.com/bfs/archive/cover.jpg?Expires=123")).isNull();
        assertThat(BilibiliCoverUrlPolicy.normalize(
                "https://i0.hdslb.com/bfs/archive/cover.jpg?x-oss-signature=abc")).isNull();
        assertThat(BilibiliCoverUrlPolicy.normalize(
                "https://i0.hdslb.com/bfs/archive/cover.jpg?security-token=abc")).isNull();
        assertThat(BilibiliCoverUrlPolicy.normalize(
                "https://i0.hdslb.com/bfs/archive/cover.jpg?wsSecret=abc")).isNull();
    }

    @Test
    void shouldHideInvalidDatabaseValueFromReferenceVideoResponse() {
        ReferenceVideoRecord record = new ReferenceVideoRecord();
        record.setCoverUrl("https://oss.example.com/cover.jpg?signature=temporary");

        assertThat(ReferenceVideoResponse.from(record).coverUrl()).isNull();
    }
}
