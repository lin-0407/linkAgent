package com.link.linkagent.creator.context.service;

import com.link.linkagent.creator.context.mapper.CreatorContextMapper;
import com.link.linkagent.creator.context.model.CreatorContextBundleResponse;
import com.link.linkagent.creator.context.model.CreatorContextTermCreateRequest;
import com.link.linkagent.creator.context.model.CreatorContextTermRecord;
import com.link.linkagent.creator.context.model.CreatorContextTermResponse;
import com.link.linkagent.util.NumberUtil;
import com.link.linkagent.util.TextUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 创作者语境库服务。
 * 这里保存的是“某个创作者在某类视频里怎么表达”的业务上下文，不替代评论弹幕事实证据。
 */
@Service
public class CreatorContextService {

    public static final String GLOBAL_VIDEO_TYPE = "GLOBAL";

    private static final String DEFAULT_USER_ID = "default";
    private static final String DEFAULT_VIDEO_TYPE = "未分类";
    private static final String DEFAULT_TERM_TYPE = "KEYWORD";
    private static final String DEFAULT_POLARITY = "NEUTRAL";
    private static final String DEFAULT_SOURCE_TYPE = "USER_SAVE";
    private static final String SCENE_PRE_PUBLISH = "PRE_PUBLISH";
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 100;
    private static final int PROMPT_TERM_LIMIT = 40;
    private static final int PROMPT_SECTION_LIMIT = 12;
    private static final int PROMPT_CONTEXT_MAX_LENGTH = 4000;

    private final CreatorContextMapper creatorContextMapper;

    public CreatorContextService(CreatorContextMapper creatorContextMapper) {
        this.creatorContextMapper = creatorContextMapper;
    }

    @Transactional
    public CreatorContextTermResponse saveTerm(CreatorContextTermCreateRequest request) {
        CreatorContextTermRecord record = new CreatorContextTermRecord();
        record.setTermId(UUID.randomUUID().toString());
        record.setUserId(normalizeUserId(request.userId()));
        record.setVideoType(normalizeVideoType(request.videoType()));
        record.setTerm(normalizeTermDisplay(request.term()));
        record.setNormalizedTerm(normalizeTermIdentity(request.term()));
        record.setTermType(normalizeTermType(request.termType()));
        record.setPolarity(normalizePolarity(request.polarity(), record.getTermType()));
        record.setSourceType(normalizeSourceType(request.sourceType()));
        record.setSourceTaskId(TextUtil.trimToNull(request.sourceTaskId()));
        record.setEvidenceText(TextUtil.trimToNull(request.evidenceText()));
        record.setWeight(initialWeight(record.getTermType(), record.getPolarity(), record.getSourceType()));
        record.setEnabled(true);

        creatorContextMapper.upsertTerm(record);
        return creatorContextMapper.findByIdentity(
                        record.getUserId(),
                        record.getVideoType(),
                        record.getNormalizedTerm(),
                        record.getTermType()
                )
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "语境词条保存后读取失败"));
    }

    public List<CreatorContextTermResponse> listTerms(String userId,
                                                      String videoType,
                                                      String termType,
                                                      Boolean includeDisabled,
                                                      Integer limit) {
        int safeLimit = NumberUtil.limitOrDefault(limit, DEFAULT_LIST_LIMIT, MAX_LIST_LIMIT);
        return creatorContextMapper.listTerms(
                        normalizeUserId(userId),
                        TextUtil.hasText(videoType) ? normalizeVideoType(videoType) : null,
                        TextUtil.hasText(termType) ? normalizeTermType(termType) : null,
                        Boolean.TRUE.equals(includeDisabled),
                        safeLimit
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public CreatorContextBundleResponse buildBundle(String userId, String videoType, String scene) {
        String safeUserId = normalizeUserId(userId);
        String safeVideoType = normalizeVideoType(videoType);
        String safeScene = normalizeScene(scene);
        List<CreatorContextTermResponse> terms = creatorContextMapper
                .listForPrompt(safeUserId, safeVideoType, GLOBAL_VIDEO_TYPE, PROMPT_TERM_LIMIT)
                .stream()
                .map(this::toResponse)
                .toList();

        List<String> keywords = filterTerms(terms, "KEYWORD");
        List<String> slangTerms = filterTerms(terms, "SLANG", "MEME");
        List<String> titlePatterns = filterTerms(terms, "TITLE_PATTERN");
        List<String> audienceConcerns = filterTerms(terms, "AUDIENCE_CONCERN");
        List<String> tabooTerms = terms.stream()
                .filter(term -> "TABOO".equals(term.termType()) || "NEGATIVE".equals(term.polarity()))
                .limit(PROMPT_SECTION_LIMIT)
                .map(this::formatPromptTerm)
                .toList();

        return new CreatorContextBundleResponse(
                safeUserId,
                safeVideoType,
                safeScene,
                terms,
                keywords,
                slangTerms,
                titlePatterns,
                audienceConcerns,
                tabooTerms,
                buildPromptContext(safeVideoType, safeScene, keywords, slangTerms, titlePatterns, audienceConcerns, tabooTerms)
        );
    }

    /**
     * 发布前优化读取的是整理后的语境摘要，而不是原始词条 JSON，避免提示词被低价值字段撑长。
     */
    public String buildPromptContext(String userId, String videoType, String scene) {
        return buildBundle(userId, videoType, scene).promptContext();
    }

    @Transactional
    public CreatorContextTermResponse disableTerm(String termId) {
        String safeTermId = normalizeTermId(termId);
        CreatorContextTermRecord before = creatorContextMapper.findByTermId(safeTermId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "语境词条不存在"));
        creatorContextMapper.disableTerm(before.getTermId());
        return creatorContextMapper.findByTermId(before.getTermId())
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "语境词条禁用后读取失败"));
    }

    @Transactional
    public CreatorContextTermResponse recordFeedback(String termId, boolean accepted) {
        String safeTermId = normalizeTermId(termId);
        if (accepted) {
            creatorContextMapper.acceptTerm(safeTermId);
        } else {
            creatorContextMapper.rejectTerm(safeTermId);
        }
        return creatorContextMapper.findByTermId(safeTermId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "语境词条不存在"));
    }

    private String buildPromptContext(String videoType,
                                      String scene,
                                      List<String> keywords,
                                      List<String> slangTerms,
                                      List<String> titlePatterns,
                                      List<String> audienceConcerns,
                                      List<String> tabooTerms) {
        if (keywords.isEmpty()
                && slangTerms.isEmpty()
                && titlePatterns.isEmpty()
                && audienceConcerns.isEmpty()
                && tabooTerms.isEmpty()) {
            return "当前视频类型【" + videoType + "】暂无已沉淀语境。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("当前视频类型：").append(videoType).append("\n");
        builder.append("使用场景：").append(sceneLabel(scene)).append("\n");
        appendSection(builder, "适合使用的关键词", keywords);
        appendSection(builder, "适合使用的梗/黑话", slangTerms);
        appendSection(builder, "历史有效标题套路", titlePatterns);
        appendSection(builder, "观众常见关注点", audienceConcerns);
        appendSection(builder, "慎用或避免表达", tabooTerms);
        return TextUtil.abbreviateWithSuffix(
                builder.toString().trim(),
                PROMPT_CONTEXT_MAX_LENGTH,
                "\n[语境库内容过长，已截断用于本次分析]"
        );
    }

    private void appendSection(StringBuilder builder, String title, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        builder.append(title).append("：\n");
        for (String value : values) {
            builder.append("- ").append(value).append("\n");
        }
    }

    private List<String> filterTerms(List<CreatorContextTermResponse> terms, String... termTypes) {
        List<String> values = new ArrayList<>();
        for (CreatorContextTermResponse term : terms) {
            if (values.size() >= PROMPT_SECTION_LIMIT) {
                break;
            }
            for (String termType : termTypes) {
                if (termType.equals(term.termType())) {
                    values.add(formatPromptTerm(term));
                    break;
                }
            }
        }
        return values;
    }

    private String formatPromptTerm(CreatorContextTermResponse term) {
        if (TextUtil.isBlank(term.evidenceText())) {
            return term.term();
        }
        return term.term() + "（依据：" + TextUtil.abbreviate(term.evidenceText().trim(), 80) + "）";
    }

    private int initialWeight(String termType, String polarity, String sourceType) {
        int weight = switch (sourceType) {
            case "USER_SAVE" -> 70;
            case "AI_ACCEPTED" -> 62;
            case "VIDEO_SUCCESS" -> 58;
            case "COMMENT_EXTRACTED" -> 42;
            case "USER_REJECTED" -> 24;
            default -> 50;
        };
        if ("TABOO".equals(termType) || "NEGATIVE".equals(polarity)) {
            weight += 6;
        }
        return Math.min(weight, 100);
    }

    private String normalizeUserId(String userId) {
        return TextUtil.trimToDefault(userId, DEFAULT_USER_ID);
    }

    private String normalizeVideoType(String videoType) {
        return TextUtil.trimToDefault(videoType, DEFAULT_VIDEO_TYPE);
    }

    private String normalizeTermDisplay(String term) {
        return TextUtil.collapseWhitespace(term);
    }

    private String normalizeTermIdentity(String term) {
        return TextUtil.collapseWhitespace(term).toLowerCase(Locale.ROOT);
    }

    private String normalizeTermType(String termType) {
        return TextUtil.trimToDefault(termType, DEFAULT_TERM_TYPE).toUpperCase(Locale.ROOT);
    }

    private String normalizePolarity(String polarity, String termType) {
        if ("TABOO".equals(termType)) {
            return "NEGATIVE";
        }
        return TextUtil.trimToDefault(polarity, DEFAULT_POLARITY).toUpperCase(Locale.ROOT);
    }

    private String normalizeSourceType(String sourceType) {
        return TextUtil.trimToDefault(sourceType, DEFAULT_SOURCE_TYPE).toUpperCase(Locale.ROOT);
    }

    private String normalizeScene(String scene) {
        return TextUtil.trimToDefault(scene, SCENE_PRE_PUBLISH).toUpperCase(Locale.ROOT);
    }

    private String normalizeTermId(String termId) {
        if (TextUtil.isBlank(termId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "语境词条ID不能为空");
        }
        return termId.trim();
    }

    private String sceneLabel(String scene) {
        if (SCENE_PRE_PUBLISH.equals(scene)) {
            return "发布前优化";
        }
        if ("FEEDBACK_ANALYZE".equals(scene)) {
            return "评论弹幕分析";
        }
        if ("REPORT".equals(scene)) {
            return "创作复盘";
        }
        return scene;
    }

    private CreatorContextTermResponse toResponse(CreatorContextTermRecord record) {
        return new CreatorContextTermResponse(
                record.getId(),
                record.getTermId(),
                record.getUserId(),
                record.getVideoType(),
                record.getTerm(),
                record.getTermType(),
                record.getPolarity(),
                record.getSourceType(),
                record.getSourceTaskId(),
                record.getEvidenceText(),
                record.getWeight(),
                record.getUsageCount(),
                record.getAcceptCount(),
                record.getRejectCount(),
                record.getEnabled(),
                record.getCreateTime(),
                record.getUpdateTime()
        );
    }
}
