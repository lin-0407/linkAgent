package com.link.linkagent.creator.suggestion.service;

import com.link.linkagent.creator.suggestion.model.PrePublishAnalyzeRequest;
import com.link.linkagent.creator.suggestion.model.PrePublishEvidenceRef;
import com.link.linkagent.creator.task.model.CreatorMaterialRecord;
import com.link.linkagent.creator.task.model.CreatorMaterialType;
import com.link.linkagent.creator.task.model.CreatorTaskRecord;
import com.link.linkagent.knowledge.model.ReferenceVideoEvidence;
import com.link.linkagent.knowledge.model.ReferenceVideoEvidenceItem;
import com.link.linkagent.knowledge.model.ReferenceVideoResponse;
import com.link.linkagent.knowledge.model.ReferenceVideoSearchRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoSearchResponse;
import com.link.linkagent.knowledge.service.KnowledgeReferenceRetrievalService;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 发布前优化证据收集器。
 * <p>
 * 这层放在建议生成前，是为了把“模型可以参考什么”变成可回放的数据结构，而不是只把一大段上下文塞进提示词。
 */
@Service
public class PrePublishEvidenceCollector {

    private static final Logger log = LoggerFactory.getLogger(PrePublishEvidenceCollector.class);

    private static final int MATERIAL_EVIDENCE_MAX_COUNT = 4;
    private static final int REFERENCE_QUERY_MAX_COUNT = 3;
    private static final int REFERENCE_TOP_K = 3;
    private static final int QUOTE_PREVIEW_LENGTH = 180;
    private static final int SUMMARY_PREVIEW_LENGTH = 220;

    private final ObjectProvider<KnowledgeReferenceRetrievalService> retrievalServiceProvider;

    public PrePublishEvidenceCollector(ObjectProvider<KnowledgeReferenceRetrievalService> retrievalServiceProvider) {
        this.retrievalServiceProvider = retrievalServiceProvider;
    }

    public List<PrePublishEvidenceRef> collect(CreatorTaskRecord taskRecord,
                                               List<CreatorMaterialRecord> materials,
                                               PrePublishAnalyzeRequest request,
                                               String preferenceContext) {
        AtomicInteger sequence = new AtomicInteger(1);
        List<PrePublishEvidenceRef> evidences = new ArrayList<>();
        appendMaterialEvidence(evidences, sequence, materials);
        appendPreferenceEvidence(evidences, sequence, request, preferenceContext);
        appendReferenceCaseEvidence(evidences, sequence, taskRecord, materials, request);
        if (evidences.isEmpty()) {
            evidences.add(new PrePublishEvidenceRef(
                    nextEvidenceId(sequence),
                    "SYSTEM_LIMITATION",
                    "证据收集",
                    "pre_publish:evidence",
                    "",
                    "未收集到足够证据，本次建议只能基于模型对任务描述的保守理解生成。",
                    0.3D
            ));
        }
        return evidences;
    }

    private void appendMaterialEvidence(List<PrePublishEvidenceRef> evidences,
                                        AtomicInteger sequence,
                                        List<CreatorMaterialRecord> materials) {
        int count = 0;
        for (CreatorMaterialRecord material : materials) {
            if (count >= MATERIAL_EVIDENCE_MAX_COUNT) {
                break;
            }
            String content = TextUtil.trimToNull(material.getContent());
            if (content == null) {
                continue;
            }
            evidences.add(new PrePublishEvidenceRef(
                    nextEvidenceId(sequence),
                    "TASK_MATERIAL",
                    toChineseMaterialName(material.getMaterialType()),
                    "task_material:" + material.getId(),
                    TextUtil.preview(content, QUOTE_PREVIEW_LENGTH, ""),
                    "用户本次提供的" + toChineseMaterialName(material.getMaterialType()) + "，应作为发布前优化的事实来源。",
                    0.92D
            ));
            count++;
        }
    }

    private void appendPreferenceEvidence(List<PrePublishEvidenceRef> evidences,
                                          AtomicInteger sequence,
                                          PrePublishAnalyzeRequest request,
                                          String preferenceContext) {
        String manualPreference = TextUtil.trimToNull(request.creatorPreference());
        if (manualPreference != null) {
            evidences.add(new PrePublishEvidenceRef(
                    nextEvidenceId(sequence),
                    "CREATOR_PREFERENCE",
                    "本次手动偏好",
                    "request.creatorPreference",
                    TextUtil.preview(manualPreference, QUOTE_PREVIEW_LENGTH, ""),
                    "用户本次主动补充的偏好优先级高于历史推断。",
                    0.9D
            ));
        }
        String context = TextUtil.trimToNull(preferenceContext);
        if (context != null) {
            evidences.add(new PrePublishEvidenceRef(
                    nextEvidenceId(sequence),
                    "CREATOR_CONTEXT",
                    "创作者偏好与类型语境",
                    "creator_context:prompt",
                    TextUtil.preview(context, QUOTE_PREVIEW_LENGTH, ""),
                    "历史偏好和当前视频类型语境只能用于风格约束与避坑，不能替代本期材料。",
                    0.78D
            ));
        }
    }

    private void appendReferenceCaseEvidence(List<PrePublishEvidenceRef> evidences,
                                             AtomicInteger sequence,
                                             CreatorTaskRecord taskRecord,
                                             List<CreatorMaterialRecord> materials,
                                             PrePublishAnalyzeRequest request) {
        KnowledgeReferenceRetrievalService retrievalService = retrievalServiceProvider.getIfAvailable();
        if (retrievalService == null) {
            evidences.add(systemLimitation(sequence, "案例库检索服务未启用，本次不会引用同类视频案例。"));
            return;
        }
        List<String> queries = buildReferenceQueries(taskRecord, materials, request);
        if (queries.isEmpty()) {
            evidences.add(systemLimitation(sequence, "当前任务缺少明确主题或标题方向，未触发同类案例检索。"));
            return;
        }
        int added = 0;
        for (String query : queries) {
            try {
                ReferenceVideoSearchResponse response = retrievalService.search(
                        new ReferenceVideoSearchRequest(query, null, null, REFERENCE_TOP_K, null));
                added += appendSearchEvidence(evidences, sequence, query, response);
            } catch (RuntimeException exception) {
                log.warn("发布前优化前置案例检索失败，query={}", TextUtil.preview(query, 80, ""), exception);
            }
            if (added >= REFERENCE_TOP_K) {
                break;
            }
        }
        if (added == 0) {
            evidences.add(systemLimitation(sequence, "未检索到可用同类案例，本次建议应主要基于任务材料和创作者偏好。"));
        }
    }

    private int appendSearchEvidence(List<PrePublishEvidenceRef> evidences,
                                     AtomicInteger sequence,
                                     String query,
                                     ReferenceVideoSearchResponse response) {
        if (response == null || response.items() == null || response.items().isEmpty()) {
            return 0;
        }
        Map<String, ReferenceVideoEvidence> evidenceByVideoId = response.evidence() == null
                ? Map.of()
                : response.evidence().stream().collect(Collectors.toMap(
                ReferenceVideoEvidence::videoId,
                evidence -> evidence,
                (left, right) -> left
        ));
        int added = 0;
        for (ReferenceVideoResponse item : response.items()) {
            if (added >= REFERENCE_TOP_K) {
                break;
            }
            String audienceQuote = firstAudienceQuote(evidenceByVideoId.get(item.videoId()));
            String quote = TextUtil.hasText(audienceQuote)
                    ? audienceQuote
                    : TextUtil.trimToDefault(item.highlightSummary(), item.title());
            evidences.add(new PrePublishEvidenceRef(
                    nextEvidenceId(sequence),
                    "REFERENCE_CASE",
                    "同类案例：" + TextUtil.preview(item.title(), 60, "无标题案例"),
                    "reference_video:" + item.videoId(),
                    TextUtil.preview(quote, QUOTE_PREVIEW_LENGTH, ""),
                    buildReferenceSummary(query, item, response.mode()),
                    0.72D
            ));
            added++;
        }
        return added;
    }

    private String buildReferenceSummary(String query, ReferenceVideoResponse item, String mode) {
        StringBuilder builder = new StringBuilder();
        builder.append("检索词「").append(query).append("」命中同类案例，检索模式 ").append(mode).append("。");
        if (TextUtil.hasText(item.category())) {
            builder.append("分区：").append(item.category()).append("。");
        }
        if (item.viewCount() != null) {
            builder.append("播放：").append(item.viewCount()).append("。");
        }
        if (TextUtil.hasText(item.highlightSummary())) {
            builder.append("亮点：").append(item.highlightSummary());
        }
        return TextUtil.preview(builder.toString(), SUMMARY_PREVIEW_LENGTH, "");
    }

    private String firstAudienceQuote(ReferenceVideoEvidence evidence) {
        if (evidence == null || evidence.items() == null || evidence.items().isEmpty()) {
            return null;
        }
        for (ReferenceVideoEvidenceItem item : evidence.items()) {
            if (TextUtil.hasText(item.content())) {
                return item.content();
            }
        }
        return null;
    }

    private List<String> buildReferenceQueries(CreatorTaskRecord taskRecord,
                                               List<CreatorMaterialRecord> materials,
                                               PrePublishAnalyzeRequest request) {
        List<String> queries = new ArrayList<>();
        String videoType = TextUtil.trimToNull(taskRecord.getVideoType());
        String titleDraft = firstMaterialContent(materials, CreatorMaterialType.TITLE_DRAFT.name());
        String materialTopic = firstLongMaterialPreview(materials);
        addQuery(queries, joinQuery(videoType, titleDraft, "B站同类标题表达"));
        addQuery(queries, joinQuery(videoType, materialTopic, "高收藏 高完播 案例"));
        addQuery(queries, joinQuery(TextUtil.trimToNull(request.titleStyle()), TextUtil.trimToNull(request.extraRequirement()), "观众痛点 同类案例"));
        if (queries.size() > REFERENCE_QUERY_MAX_COUNT) {
            return new ArrayList<>(queries.subList(0, REFERENCE_QUERY_MAX_COUNT));
        }
        return queries;
    }

    private void addQuery(List<String> queries, String query) {
        String value = TextUtil.trimToNull(query);
        if (value != null && !queries.contains(value)) {
            queries.add(TextUtil.abbreviate(value, 500));
        }
    }

    private String joinQuery(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            String value = TextUtil.trimToNull(part);
            if (value != null) {
                if (!builder.isEmpty()) {
                    builder.append(' ');
                }
                builder.append(TextUtil.preview(value, 80, ""));
            }
        }
        return builder.toString();
    }

    private String firstMaterialContent(List<CreatorMaterialRecord> materials, String materialType) {
        for (CreatorMaterialRecord material : materials) {
            if (materialType.equals(material.getMaterialType()) && TextUtil.hasText(material.getContent())) {
                return TextUtil.preview(material.getContent(), 80, "");
            }
        }
        return null;
    }

    private String firstLongMaterialPreview(List<CreatorMaterialRecord> materials) {
        for (CreatorMaterialRecord material : materials) {
            if ((CreatorMaterialType.MANUSCRIPT.name().equals(material.getMaterialType())
                    || CreatorMaterialType.SUBTITLE.name().equals(material.getMaterialType()))
                    && TextUtil.hasText(material.getContent())) {
                return TextUtil.preview(material.getContent(), 120, "");
            }
        }
        return firstMaterialContent(materials, CreatorMaterialType.DESCRIPTION_DRAFT.name());
    }

    private PrePublishEvidenceRef systemLimitation(AtomicInteger sequence, String summary) {
        return new PrePublishEvidenceRef(
                nextEvidenceId(sequence),
                "SYSTEM_LIMITATION",
                "证据收集限制",
                "pre_publish:evidence_collector",
                "",
                summary,
                0.4D
        );
    }

    private String nextEvidenceId(AtomicInteger sequence) {
        return "E" + sequence.getAndIncrement();
    }

    private String toChineseMaterialName(String materialType) {
        if (CreatorMaterialType.TITLE_DRAFT.name().equals(materialType)) {
            return "标题草稿";
        }
        if (CreatorMaterialType.DESCRIPTION_DRAFT.name().equals(materialType)) {
            return "简介草稿";
        }
        if (CreatorMaterialType.MANUSCRIPT.name().equals(materialType)) {
            return "文稿";
        }
        if (CreatorMaterialType.SUBTITLE.name().equals(materialType)) {
            return "字幕";
        }
        return materialType;
    }
}
