package com.link.linkagent.dto;

/** API 根地址返回的服务说明。 */
public record ApiInfoResponse(
        String name,
        String status,
        String statusUrl
) {
}
