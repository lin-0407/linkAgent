package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.aivideo.provider.CompatibleVideoProvider;
import com.link.linkagent.creator.creation.aivideo.provider.VideoGenerationProvider;
import com.link.linkagent.creator.creation.aivideo.service.CreationVideoProperties;
import com.link.linkagent.creator.creation.voice.provider.DashScopeCosyVoiceProvider;
import com.link.linkagent.creator.creation.voice.provider.SpeechSynthesisProvider;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceProperties;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** 通过真实 Spring 上下文装配适配器，覆盖直接 new 协议替身时遗漏的构造器选择。 */
class CreationProviderContextTest {
    @Test
    void assemblesBothProvidersWithoutCredentialsOrSupplierRequests() {
        try (var context = new AnnotationConfigApplicationContext(
                CreationVideoProperties.class, CreationVoiceProperties.class,
                CreatorMediaProperties.class, ObjectMapper.class,
                CompatibleVideoProvider.class, DashScopeCosyVoiceProvider.class)) {
            assertThat(context.getBean(VideoGenerationProvider.class)).isInstanceOf(CompatibleVideoProvider.class);
            assertThat(context.getBean(SpeechSynthesisProvider.class)).isInstanceOf(DashScopeCosyVoiceProvider.class);
        }
    }
}
