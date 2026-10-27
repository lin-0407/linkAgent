package com.link.linkagent.settings.service;

import com.link.linkagent.creator.creation.aivideo.service.CreationVideoProperties;
import com.link.linkagent.creator.creation.billing.BillingExchangeRate;
import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import com.link.linkagent.creator.creation.service.CreationCapabilityService;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceProperties;
import com.link.linkagent.creator.feedback.config.CreatorFeedbackRagProperties;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.knowledge.config.KnowledgeRagProperties;
import com.link.linkagent.llm.DeepSeekThinkingProperties;
import com.link.linkagent.llm.LlmCallGuardProperties;
import com.link.linkagent.memory.SummaryMemoryProperties;
import com.link.linkagent.settings.mapper.RuntimeSettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSettingServiceTest {

    private RuntimeSettingMapper mapper;
    private CreationVoiceProperties voice;
    private CreationVideoProperties video;
    private CreatorMediaProperties media;
    private CreationCapabilityService capabilities;

    @BeforeEach
    void setUp() {
        mapper = mock(RuntimeSettingMapper.class);
        when(mapper.findByKey(anyString())).thenReturn(Optional.empty());
        voice = new CreationVoiceProperties();
        video = new CreationVideoProperties();
        media = new CreatorMediaProperties();
        capabilities = new CreationCapabilityService(mapper, voice, video, media);
    }

    @Test
    void mediaStartupOffLocksAllPaidCapabilitiesUntilDeploymentChanges() {
        media.setEnabled(false);

        var status = service(BillingExchangeRate.fixed(new BigDecimal("6.7"))).status();

        assertThat(status.productionToggles()).hasSize(3);
        var mediaToggle = toggle(status, "creator.media.enabled");
        assertThat(mediaToggle.enabled()).isFalse();
        assertThat(mediaToggle.locked()).isTrue();
        assertThat(mediaToggle.lockedReason()).contains("启动时未启用");
        var voiceToggle = toggle(status, "creator.creation.voice.enabled");
        assertThat(voiceToggle.locked()).isTrue();
        var videoToggle = toggle(status, "creator.creation.video.enabled");
        assertThat(videoToggle.locked()).isTrue();
    }

    @Test
    void mediaOnExposesUnlockedButDefaultOffCapabilities() {
        media.setEnabled(true);

        var status = service(BillingExchangeRate.fixed(new BigDecimal("6.7"))).status();

        assertThat(toggle(status, "creator.media.enabled").enabled()).isTrue();
        assertThat(toggle(status, "creator.media.enabled").locked()).isFalse();
        assertThat(toggle(status, "creator.creation.voice.enabled").enabled()).isFalse();
        assertThat(toggle(status, "creator.creation.voice.enabled").locked()).isFalse();
        assertThat(toggle(status, "creator.creation.video.enabled").enabled()).isFalse();
    }

    @Test
    void cannotEnableMediaWhenItWasNotAssembledAtStartup() {
        media.setEnabled(false);

        assertThatThrownBy(() -> service(BillingExchangeRate.fixed(new BigDecimal("6.7")))
                .updateToggle("creator.media.enabled", true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void cannotEnableDependentCapabilityWhileMediaAnalysisIsOff() {
        media.setEnabled(false);

        assertThatThrownBy(() -> service(BillingExchangeRate.fixed(new BigDecimal("6.7")))
                .updateToggle("creator.creation.voice.enabled", true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void enablingCapabilityWritesRuntimeOverride() {
        media.setEnabled(true);

        service(BillingExchangeRate.fixed(new BigDecimal("6.7"))).updateToggle("creator.creation.voice.enabled", true);

        verify(mapper).upsert(eq("creator.creation.voice.enabled"), eq("true"), anyString());
    }

    @Test
    void billingExposesFallbackRateAndPriceRanges() {
        media.setEnabled(true);

        var billing = service(BillingExchangeRate.fixed(new BigDecimal("6.7"))).status().billing();

        assertThat(billing.usdToCny()).isEqualByComparingTo("6.7");
        assertThat(billing.source()).isEqualTo("CONFIG_FALLBACK");
        assertThat(billing.stale()).isTrue();
        assertThat(billing.ranges()).hasSize(6);
        assertThat(billing.ranges()).allSatisfy(range -> {
            assertThat(range.lowCny()).isPositive();
            assertThat(range.highCny()).isGreaterThanOrEqualTo(range.lowCny());
        });
    }

    @Test
    void refreshBillingUsesNewLiveRateForNextEstimates() {
        media.setEnabled(true);
        var current = new AtomicReference<>(new BillingExchangeRate.Rate(
                new BigDecimal("7.2"), null, BillingExchangeRate.Source.CONFIG_FALLBACK));
        var switching = new BillingExchangeRate() {
            @Override public Rate current() { return current.get(); }
            @Override public Rate refresh() {
                current.set(new Rate(new BigDecimal("6.5"), Instant.now(), Source.LIVE));
                return current.get();
            }
        };

        var refreshed = service(switching).refreshBilling();

        assertThat(refreshed.usdToCny()).isEqualByComparingTo("6.5");
        assertThat(refreshed.source()).isEqualTo("LIVE");
        assertThat(refreshed.stale()).isFalse();
        assertThat(service(switching).status().billing().usdToCny()).isEqualByComparingTo("6.5");
    }

    private RuntimeSettingService service(BillingExchangeRate rates) {
        return new RuntimeSettingService(mapper, new LlmCallGuardProperties(),
                new SummaryMemoryProperties(false, 256000, 2),
                new KnowledgeRagProperties(), new CreatorFeedbackRagProperties(), new DeepSeekThinkingProperties(),
                capabilities, voice, video, media, new CreationBillingProperties(), rates, new StandardEnvironment());
    }

    private static com.link.linkagent.settings.dto.RuntimeToggleResponse toggle(
            com.link.linkagent.settings.dto.SettingsStatusResponse status, String key) {
        return status.productionToggles().stream().filter(item -> key.equals(item.key())).findFirst().orElseThrow();
    }
}
