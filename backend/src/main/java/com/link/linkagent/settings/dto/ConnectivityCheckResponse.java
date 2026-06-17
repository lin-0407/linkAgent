package com.link.linkagent.settings.dto;

import java.util.List;

/**
 * 基础设施连通性检测响应。
 */
public record ConnectivityCheckResponse(
        List<ConnectivityItemResponse> items
) {
}
