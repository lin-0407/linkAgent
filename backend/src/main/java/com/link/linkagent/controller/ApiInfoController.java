package com.link.linkagent.controller;

import com.link.linkagent.dto.ApiInfoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供 API 根地址说明，避免直接访问 /api 时被误判为服务异常。 */
@RestController
@RequestMapping("/api")
public class ApiInfoController {

    @GetMapping({"", "/"})
    public ApiInfoResponse info() {
        return new ApiInfoResponse("LinkAgent API", "UP", "/api/settings/status");
    }
}
