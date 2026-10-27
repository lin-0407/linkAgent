package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.mapper.CreationMediaMapper;
import com.link.linkagent.creator.creation.model.CreationMediaLink;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.Charge;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 结算必须读取原价格快照，不能随服务重启或当前记账配置改变。 */
class CreationMediaBillingTest {
    final CreationMediaMapper links = mock(CreationMediaMapper.class);
    final CreationRuntimeService runtime = mock(CreationRuntimeService.class);
    final CreationBillingProperties properties = new CreationBillingProperties();
    static final String SNAPSHOT = "{\"currency\":\"USD\",\"usdUpperBound\":\"0.01\",\"usdToCny\":\"7.20\",\"capability\":\"MEDIA_ASR\",\"basis\":\"ASR\"}";

    @Test void manualReviewWithoutCreationRunIsNotCharged() {
        when(links.byReview("review")).thenReturn(Optional.empty());
        var handle=gate().reserve("review","MEDIA_ASR","key",new BigDecimal("0.01"),"ASR");
        assertThat(handle.markSent()).isTrue(); gate().settle("review","key",BigDecimal.ONE,"usage");
        verifyNoInteractions(runtime);
    }
    @Test void initialReservationConvertsAndFreezesTheRate() {
        existing(Optional.empty());
        gate().reserve("review","MEDIA_ASR","key",new BigDecimal("0.01"),"ASR");
        verify(runtime).reserveDependency(eq("task"),eq("default"),eq("run"),eq("key"),eq("MEDIA_ASR"),
                eq(new BigDecimal("0.072000")),argThat(s->s.contains("7.20")&&s.contains("USAGE_ESTIMATE")));
    }
    @Test void rebuiltServiceUsesOriginalRateForBothReplayAndSettlement() {
        existing(Optional.of(charge(SNAPSHOT,true)));
        properties.setUsdToCny(new BigDecimal("8.00"));
        gate().reserve("review","MEDIA_ASR","key",new BigDecimal("0.01"),"ASR");
        gate().settle("review","key",new BigDecimal("0.02"),"usage");
        verify(runtime).reserveDependency("task","default","run","key","MEDIA_ASR",new BigDecimal("0.072000"),SNAPSHOT);
        verify(runtime).settle("task","default","key",new BigDecimal("0.144000"),"USAGE_ESTIMATE","usage");
    }
    @Test void replayRejectsDifferentPhysicalCallIdentity() {
        existing(Optional.of(charge(SNAPSHOT,true)));
        assertThatThrownBy(()->gate().reserve("review","MEDIA_ASR","key",new BigDecimal("0.02"),"ASR"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->gate().reserve("review","MEDIA_VIDEO","key",new BigDecimal("0.01"),"ASR"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->gate().reserve("review","MEDIA_ASR","key",new BigDecimal("0.01"),"changed"))
                .isInstanceOf(ResponseStatusException.class);
        verify(runtime,never()).reserveDependency(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void missingUsageStaysUnknownAndRealZeroStaysZero() {
        existing(Optional.of(charge(SNAPSHOT,true)));
        gate().settle("review","key",null,"usage");
        verify(runtime).markUnknown("task","default","key");
        gate().settle("review","key",BigDecimal.ZERO,"usage");
        verify(runtime).settle("task","default","key",new BigDecimal("0.000000"),"USAGE_ESTIMATE","usage");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"{}","not-json","{\"currency\":\"USD\",\"usdToCny\":\"0\"}"})
    void invalidSentSnapshotCannotFallBackToCurrentConfiguration(String snapshot) {
        existing(Optional.of(charge(snapshot,true)));
        assertThatThrownBy(()->gate().settle("review","key",BigDecimal.ONE,"usage"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("快照");
        verify(runtime).markUnknown("task","default","key");
        verify(runtime,never()).settle(any(),any(),any(),any(),any(),any());
    }
    @Test void invalidUnsentSnapshotIsRejectedWithoutInventingSentUsage() {
        existing(Optional.of(charge("{}",false)));
        assertThatThrownBy(()->gate().reserve("review","MEDIA_ASR","key",new BigDecimal("0.01"),"ASR"))
                .isInstanceOf(ResponseStatusException.class);
        verify(runtime,never()).markUnknown(any(),any(),any());
    }
    @Test void missingHistoricalReservationIsNotRebuiltAtTodaysRate() {
        existing(Optional.empty()); gate().settle("review","key",BigDecimal.ONE,"usage");
        verify(runtime,never()).settle(any(),any(),any(),any(),any(),any());
    }
    @Test void settlementConflictsArePropagatedRatherThanCalledHistoricalGaps() {
        existing(Optional.of(charge(SNAPSHOT,true)));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"重复结算内容不一致"))
                .when(runtime).settle(any(),any(),any(),any(),any(),any());
        assertThatThrownBy(()->gate().settle("review","key",BigDecimal.ONE,"usage"))
                .isInstanceOf(ResponseStatusException.class);
    }
    @Test void configuredTextCostIsAnEstimateAndUnknownUsageRemainsUnknown() {
        assertThat(gate().estimateTextCost(null,null)).isNull();
        assertThat(gate().estimateTextCost(1_000_000L,1_000_000L)).isEqualByComparingTo("1");
        assertThat(gate().upperBound("MEDIA_VIDEO")).isEqualByComparingTo("0.30");
    }
    void existing(Optional<Charge> charge) {
        when(links.byReview("review")).thenReturn(Optional.of(new CreationMediaLink("run","task","default","version","job","review","PREFLIGHT_RUNNING",2,null)));
        when(runtime.findCharge("task","default","run","key")).thenReturn(charge);
    }
    CreationMediaBilling gate() { return new CreationMediaBilling(links,runtime,properties,new ObjectMapper()); }
    static Charge charge(String snapshot,boolean sent) {
        return new Charge("entry","key","task","default","run","approval",2,"MEDIA_ASR",new BigDecimal("0.072000"),null,"RESERVED",null,null,snapshot,sent?LocalDateTime.now():null);
    }
}
