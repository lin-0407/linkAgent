package com.link.linkagent.creator.task.service;

import com.link.linkagent.creator.task.mapper.CreatorPrePublishSettingsMapper;
import com.link.linkagent.creator.task.model.PrePublishSettingsRecord;
import com.link.linkagent.creator.task.model.PrePublishSettingsResponse;
import com.link.linkagent.creator.task.model.PrePublishSettingsUpdateRequest;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CreatorPrePublishSettingsService {

    private static final String DEFAULT_PREFERENCE_MODE = "USE_HISTORY";

    private final CreatorPrePublishSettingsMapper mapper;

    public CreatorPrePublishSettingsService(CreatorPrePublishSettingsMapper mapper) {
        this.mapper = mapper;
    }

    public PrePublishSettingsResponse getSettings(String taskId) {
        String normalizedTaskId = requireTask(taskId);
        return mapper.findByTaskId(normalizedTaskId)
                .map(this::toResponse)
                .orElseGet(() -> new PrePublishSettingsResponse(
                        normalizedTaskId,
                        DEFAULT_PREFERENCE_MODE,
                        "",
                        "",
                        "",
                        "",
                        null
                ));
    }

    @Transactional
    public PrePublishSettingsResponse saveSettings(String taskId, PrePublishSettingsUpdateRequest request) {
        String normalizedTaskId = requireTask(taskId);
        PrePublishSettingsRecord record = new PrePublishSettingsRecord();
        record.setTaskId(normalizedTaskId);
        record.setPreferenceMode(request.preferenceMode().trim());
        record.setCreatorPreference(normalizeOptional(request.creatorPreference()));
        record.setTitleStyle(normalizeOptional(request.titleStyle()));
        record.setExtraRequirement(normalizeOptional(request.extraRequirement()));
        record.setCustomGuidance(normalizeOptional(request.customGuidance()));
        mapper.upsert(record);
        return mapper.findByTaskId(normalizedTaskId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "发布前设置保存后读取失败"
                ));
    }

    private String requireTask(String taskId) {
        String normalizedTaskId = TextUtil.trimToNull(taskId);
        if (normalizedTaskId == null || mapper.countTask(normalizedTaskId) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在");
        }
        return normalizedTaskId;
    }

    private String normalizeOptional(String value) {
        return TextUtil.trimToDefault(value, "");
    }

    private PrePublishSettingsResponse toResponse(PrePublishSettingsRecord record) {
        return new PrePublishSettingsResponse(
                record.getTaskId(),
                TextUtil.trimToDefault(record.getPreferenceMode(), DEFAULT_PREFERENCE_MODE),
                TextUtil.trimToDefault(record.getCreatorPreference(), ""),
                TextUtil.trimToDefault(record.getTitleStyle(), ""),
                TextUtil.trimToDefault(record.getExtraRequirement(), ""),
                TextUtil.trimToDefault(record.getCustomGuidance(), ""),
                record.getUpdateTime()
        );
    }
}
