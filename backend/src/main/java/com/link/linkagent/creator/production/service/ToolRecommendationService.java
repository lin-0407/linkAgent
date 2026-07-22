package com.link.linkagent.creator.production.service;

import com.link.linkagent.creator.production.mapper.ProductionPlanMapper;
import com.link.linkagent.creator.production.model.PreferredToolRequest;
import com.link.linkagent.creator.production.model.ProductionVideoCategory;
import com.link.linkagent.creator.production.model.ToolCatalogRecord;
import com.link.linkagent.creator.production.model.ToolResolutionResponse;
import com.link.linkagent.creator.production.model.ToolVerificationStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 根据视频类型和用户偏好解析工具目录，所有菜单级知识都委托官方资料学习服务验证。 */
@Service
public class ToolRecommendationService {

    private static final int DEFAULT_TOOL_COUNT = 2;
    private final ProductionPlanMapper mapper;
    private final ToolDocumentationProvider documentationProvider;

    public ToolRecommendationService(ProductionPlanMapper mapper,
                                     ToolDocumentationProvider documentationProvider) {
        this.mapper = mapper;
        this.documentationProvider = documentationProvider;
    }

    public List<ToolResolutionResponse> resolve(ProductionVideoCategory category,
                                                List<PreferredToolRequest> preferredTools) {
        if (preferredTools != null && !preferredTools.isEmpty()) {
            List<ToolResolutionResponse> resolved = new ArrayList<>();
            for (PreferredToolRequest preferred : preferredTools) {
                String normalizedName = normalizeName(preferred.name());
                ToolCatalogRecord catalog = mapper.findToolByNormalizedName(normalizedName).orElse(null);
                if (catalog == null) {
                    resolved.add(new ToolResolutionResponse(
                            null,
                            preferred.name().trim(),
                            preferred.version(),
                            preferred.officialUrl(),
                            ToolVerificationStatus.SOURCE_REQUIRED.name(),
                            List.of(),
                            List.of(),
                            List.of(),
                            "该工具尚未进入可信目录，请先登记官方域名和官方资料"
                    ));
                    continue;
                }
                resolved.add(documentationProvider.resolve(catalog, preferred.version(), preferred.officialUrl()));
            }
            return List.copyOf(resolved);
        }

        return mapper.listEnabledTools().stream()
                .filter(tool -> tool.supportedCategories() != null
                        && tool.supportedCategories().contains(category.name()))
                .limit(DEFAULT_TOOL_COUNT)
                .map(tool -> documentationProvider.resolve(tool, null, null))
                .toList();
    }

    static String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "");
    }
}
