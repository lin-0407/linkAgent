package com.link.linkagent.creator.creation.service;

import com.link.linkagent.creator.creation.aivideo.service.CreationVideoProperties;
import com.link.linkagent.creator.creation.voice.service.CreationVoiceProperties;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import com.link.linkagent.settings.mapper.RuntimeSettingMapper;
import com.link.linkagent.settings.model.RuntimeSettingKey;
import com.link.linkagent.settings.model.RuntimeSettingRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 把设置表里的付费能力开关与部署默认值合并成运行期可用状态。
 * <p>
 * 每次读取都重新查设置表，让面板修改在下一次受理或下一轮运行扫描时生效；
 * 读取失败时回退部署默认值，不能因为设置表短暂不可用而阻断正常制作。
 */
@Service
public class CreationCapabilityService implements CreationCapabilityGate {

    private static final Logger log = LoggerFactory.getLogger(CreationCapabilityService.class);

    private final RuntimeSettingMapper settings;
    private final CreationVoiceProperties voice;
    private final CreationVideoProperties video;
    private final CreatorMediaProperties media;

    public CreationCapabilityService(RuntimeSettingMapper settings, CreationVoiceProperties voice,
                                     CreationVideoProperties video, CreatorMediaProperties media) {
        this.settings = settings;
        this.voice = voice;
        this.video = video;
        this.media = media;
    }

    @Override
    public boolean mediaInfrastructureEnabled() {
        // 对象存储、上传过滤器与控制器由启动期的 creator.media.enabled 决定，运行期不能重新装配。
        return media.isEnabled();
    }

    /** 媒体基础设施存在，且运行期没有暂停付费分析。 */
    public boolean mediaEnabled() {
        return mediaInfrastructureEnabled() && read(RuntimeSettingKey.CREATION_MEDIA_ENABLED, true);
    }

    @Override
    public boolean mediaAnalysisEnabled() {
        return mediaEnabled();
    }

    @Override
    public boolean voiceEnabled() {
        return mediaEnabled() && read(RuntimeSettingKey.CREATION_VOICE_ENABLED, voice.isEnabled());
    }

    @Override
    public boolean videoEnabled() {
        return mediaEnabled() && read(RuntimeSettingKey.CREATION_VIDEO_ENABLED, video.isEnabled());
    }

    private boolean read(RuntimeSettingKey key, boolean fallback) {
        try {
            return settings.findByKey(key.key())
                    .map(RuntimeSettingRecord::getSettingValue)
                    .map(value -> parse(key, value, fallback))
                    .orElse(fallback);
        } catch (DataAccessException exception) {
            log.warn("读取制作能力设置失败，回退部署默认值。key={}", key.key(), exception);
            return fallback;
        }
    }

    private boolean parse(RuntimeSettingKey key, String value, boolean fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if ("true".equals(normalized)) return true;
        if ("false".equals(normalized)) return false;
        log.warn("制作能力设置值非法，回退部署默认值。key={}, value={}", key.key(), value);
        return fallback;
    }
}
