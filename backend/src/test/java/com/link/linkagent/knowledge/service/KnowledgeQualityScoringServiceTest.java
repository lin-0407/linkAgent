package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.config.KnowledgeQualityProperties;
import com.link.linkagent.knowledge.mapper.KnowledgeReferenceVideoMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoScoringRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;

class KnowledgeQualityScoringServiceTest {
    private final KnowledgeReferenceVideoMapper mapper = mock(KnowledgeReferenceVideoMapper.class);
    private final KnowledgeQualityScoringService service = new KnowledgeQualityScoringService(
            mapper, new KnowledgeQualityProperties());

    @Test
    void singleVideoMustKeepScriptScoreWithoutSampleThreshold() {
        when(mapper.listScoringRowsByCategory("日常")).thenReturn(List.of(row("single", 100_000, 1)));
        service.recomputeCategories(Set.of("日常"));
        verify(mapper).updateQualityScores("single", new BigDecimal("47.619048"), new BigDecimal("47.62"), 1, true);
    }

    @Test
    void halfAndDoubleMedianRatesMustOnlyChangeBaseByFiveThirds() {
        ReferenceVideoScoringRow half = row("half", 100_000, 0.5);
        ReferenceVideoScoringRow middle = row("middle", 100_000, 1);
        ReferenceVideoScoringRow twice = row("twice", 100_000, 2);
        when(mapper.listScoringRowsByCategory("日常")).thenReturn(List.of(half, middle, twice));
        service.recomputeCategories(Set.of("日常"));
        // 三组计数分别为固定刻度的一半、一倍和两倍，可手算基础分和正负三分之五修正。
        verify(mapper).updateQualityScores("half", new BigDecimal("35.714286"), new BigDecimal("34.05"), 3, true);
        verify(mapper).updateQualityScores("middle", new BigDecimal("47.619048"), new BigDecimal("47.62"), 3, true);
        verify(mapper).updateQualityScores("twice", new BigDecimal("59.523810"), new BigDecimal("61.19"), 3, true);
    }

    @Test
    void equalRatesMustKeepDifferentBaseScoresInsteadOfForcingZeroAndHundred() {
        when(mapper.listScoringRowsByCategory("知识")).thenReturn(List.of(
                row("small", 100_000, 1), row("large", 200_000, 2)));
        service.recomputeCategories(Set.of("知识"));
        verify(mapper).updateQualityScores("small", new BigDecimal("47.619048"), new BigDecimal("47.62"), 2, true);
        verify(mapper).updateQualityScores("large", new BigDecimal("65.040650"), new BigDecimal("65.04"), 2, true);
    }

    @Test
    void incompleteStatisticsMustRemainMissingAndNotMoveOtherVideosMedian() {
        ReferenceVideoScoringRow incomplete = row("missing", 100_000, 200);
        incomplete.setFavoriteCount(null);
        when(mapper.listScoringRowsByCategory("知识")).thenReturn(List.of(row("valid", 100_000, 1), incomplete));
        service.recomputeCategories(Set.of("知识"));
        verify(mapper).updateQualityScores("missing", null, null, 1, false);
        verify(mapper).updateQualityScores("valid", new BigDecimal("47.619048"), new BigDecimal("47.62"), 1, true);
    }

    @Test
    void realZeroViewsMustKeepZeroScoreWithoutPollutingRateMedian() {
        when(mapper.listScoringRowsByCategory("知识")).thenReturn(List.of(
                row("zero", 0, 0), row("valid", 100_000, 1)));
        service.recomputeCategories(Set.of("知识"));
        verify(mapper).updateQualityScores("zero", new BigDecimal("0.000000"), new BigDecimal("0.00"), 2, true);
        verify(mapper).updateQualityScores("valid", new BigDecimal("47.619048"), new BigDecimal("47.62"), 2, true);
    }

    @Test
    void emptyCategoryMustUseBaseWithoutComparingUnclassifiedVideos() {
        when(mapper.listScoringRowsByCategory(null)).thenReturn(List.of(
                row("first", 100_000, 0.5), row("second", 100_000, 2)));
        service.recomputeCategories(java.util.Collections.singleton(null));
        verify(mapper).updateQualityScores("first", new BigDecimal("35.714286"), new BigDecimal("35.71"), 0, true);
        verify(mapper).updateQualityScores("second", new BigDecimal("59.523810"), new BigDecimal("59.52"), 0, true);
    }

    private ReferenceVideoScoringRow row(String id, long views, double factor) {
        ReferenceVideoScoringRow row = new ReferenceVideoScoringRow();
        row.setVideoId(id);
        row.setViewCount(views);
        row.setLikeCount((long) (1000 * factor));
        row.setCoinCount((long) (2000 * factor));
        row.setFavoriteCount((long) (1000 * factor));
        row.setReplyCount((long) (1000 * factor));
        row.setDanmakuCount((long) (1000 * factor));
        return row;
    }
}
