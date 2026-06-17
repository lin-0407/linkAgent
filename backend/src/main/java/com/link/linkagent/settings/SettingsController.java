package com.link.linkagent.settings;

import com.link.linkagent.settings.dto.ConnectivityCheckResponse;
import com.link.linkagent.settings.dto.SettingsStatusResponse;
import com.link.linkagent.settings.dto.UpdateRuntimeToggleRequest;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.settings.service.SettingsConnectivityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设置面板接口。
 * 只开放白名单内运行期开关的写入，启动期配置只读展示，避免误导用户以为运行期能重装配 Bean。
 */
@Validated
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final RuntimeSettingService runtimeSettingService;
    private final SettingsConnectivityService settingsConnectivityService;

    public SettingsController(RuntimeSettingService runtimeSettingService,
                              SettingsConnectivityService settingsConnectivityService) {
        this.runtimeSettingService = runtimeSettingService;
        this.settingsConnectivityService = settingsConnectivityService;
    }

    @GetMapping("/status")
    public SettingsStatusResponse status() {
        return runtimeSettingService.status();
    }

    @PutMapping("/toggles/{settingKey}")
    public void updateToggle(
            @PathVariable
            @NotBlank(message = "设置 key 不能为空")
            @Size(max = 128, message = "设置 key 长度不能超过128个字符")
            String settingKey,
            @RequestBody @Valid UpdateRuntimeToggleRequest request) {
        runtimeSettingService.updateToggle(settingKey, request.enabled());
    }

    @PostMapping("/connectivity/check")
    public ConnectivityCheckResponse checkConnectivity() {
        return settingsConnectivityService.check();
    }
}
