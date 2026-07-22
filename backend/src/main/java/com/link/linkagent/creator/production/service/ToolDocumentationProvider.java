package com.link.linkagent.creator.production.service;

import com.link.linkagent.creator.production.model.ToolCatalogRecord;
import com.link.linkagent.creator.production.model.ToolResolutionResponse;

/** 工具资料解析边界，方便测试时替换真实网页抓取和模型调用。 */
public interface ToolDocumentationProvider {

    ToolResolutionResponse resolve(ToolCatalogRecord catalog, String version, String preferredOfficialUrl);
}
