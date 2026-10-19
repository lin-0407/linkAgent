package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ReferenceVideoCollectionPolicyTest {
    private final LocalDateTime now = LocalDateTime.of(2024, 8, 31, 12, 0);
    private final ReferenceVideoImportRequest.VideoStats stats = new ReferenceVideoImportRequest.VideoStats(74229L,5116L,6807L,4929L,198L,482L);

    @Test void samePublicFormulaAllowsLessThanHundredThousandViews() {
        assertThat(ReferenceVideoCollectionPolicy.score(stats)).isCloseTo(64.1540578806, within(0.000001));
    }
    @Test void missingCountsCannotQualify() {
        assertThat(ReferenceVideoCollectionPolicy.score(new ReferenceVideoImportRequest.VideoStats(100L,1L,1L,null,0L,0L))).isNull();
    }
    @Test void zeroCoinsIsNotAnIndependentRejection() {
        var value = ReferenceVideoCollectionPolicy.score(new ReferenceVideoImportRequest.VideoStats(1000000L,20000L,0L,30000L,3000L,2000L));
        assertThat(value).isGreaterThan(60);
    }
    @Test void calendarBoundaryIncludesLeapDayAndRejectsOneSecondEarlier() {
        var boundary = now.minusMonths(6);
        assertThat(boundary.getDayOfMonth()).isEqualTo(29);
        assertThatCode(() -> ReferenceVideoCollectionPolicy.validateAutomatic(video(boundary, now),60,now)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ReferenceVideoCollectionPolicy.validateAutomatic(video(boundary.minusSeconds(1),now),60,now)).hasMessageContaining("六个自然月");
    }
    @Test void futureOrStaleStatisticsIsRejected() {
        assertThatThrownBy(() -> ReferenceVideoCollectionPolicy.validateAutomatic(video(now.minusDays(1),now.plusSeconds(1)),60,now)).hasMessageContaining("统计采集时间");
        assertThatThrownBy(() -> ReferenceVideoCollectionPolicy.validateAutomatic(video(now.minusDays(5),now.minusDays(3)),60,now)).hasMessageContaining("统计采集时间");
    }
    @Test void thresholdIsStrictAndUsesUnroundedNumber() {
        var value = ReferenceVideoCollectionPolicy.score(stats);
        assertThatThrownBy(() -> ReferenceVideoCollectionPolicy.validateAutomatic(video(now.minusDays(1),now),value,now)).hasMessageContaining("严格超过");
        assertThatCode(() -> ReferenceVideoCollectionPolicy.validateAutomatic(video(now.minusDays(1),now),value-0.0000001,now)).doesNotThrowAnyException();
    }
    @Test void legacyParsingIsStrictAndNeverUsesIngestDate() {
        assertThat(ReferenceVideoCollectionPolicy.parseLegacyTime("2024-02-29 12:34")).isEqualTo(LocalDateTime.of(2024,2,29,12,34,59));
        assertThat(ReferenceVideoCollectionPolicy.parseLegacyTime("2024-02-29")).isEqualTo(LocalDateTime.of(2024,2,29,23,59,59));
        assertThat(ReferenceVideoCollectionPolicy.parseLegacyTime("2023-02-29 12:34")).isNull();
        assertThat(ReferenceVideoCollectionPolicy.parseLegacyTime("去年")).isNull();
    }
    private ReferenceVideoImportRequest.VideoItem video(LocalDateTime published,LocalDateTime fetched) {
        return new ReferenceVideoImportRequest.VideoItem("BV1uVpn6eEhV",null,"近期视频",null,null,"知识",null,stats,List.of(),List.of(),
                published.atZone(ReferenceVideoCollectionPolicy.ZONE).toEpochSecond(), fetched.atZone(ReferenceVideoCollectionPolicy.ZONE).toEpochSecond());
    }
}
