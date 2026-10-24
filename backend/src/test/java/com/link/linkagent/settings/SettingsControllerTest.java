package com.link.linkagent.settings;

import com.link.linkagent.settings.dto.BillingSettingsResponse;
import com.link.linkagent.settings.dto.PriceRangeResponse;
import com.link.linkagent.settings.dto.RuntimeToggleResponse;
import com.link.linkagent.settings.dto.SettingsStatusResponse;
import com.link.linkagent.settings.service.RuntimeSettingService;
import com.link.linkagent.settings.service.SettingsConnectivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SettingsControllerTest {

    private RuntimeSettingService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RuntimeSettingService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new SettingsController(service, mock(SettingsConnectivityService.class))).build();
    }

    @Test
    void statusExposesProductionTogglesAndBillingWithoutSecrets() throws Exception {
        when(service.status()).thenReturn(new SettingsStatusResponse(List.of(), List.of(), List.of(),
                List.of(new RuntimeToggleResponse("creator.creation.voice.enabled", "配音生成（TTS）", false,
                        "只对 TTS 场景生成配音", false, "")),
                new BillingSettingsResponse(new BigDecimal("6.684864"), "LIVE",
                        "2026-10-24T11:40:00Z", false, "公开参考汇率",
                        List.of(new PriceRangeResponse("voice", "配音 TTS", "每 1000 字符",
                                new BigDecimal("0.1872"), new BigDecimal("0.2808"), "估算")))));

        mvc.perform(get("/api/settings/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productionToggles[0].key").value("creator.creation.voice.enabled"))
                .andExpect(jsonPath("$.productionToggles[0].locked").value(false))
                .andExpect(jsonPath("$.billing.usdToCny").value(6.684864))
                .andExpect(jsonPath("$.billing.source").value("LIVE"))
                .andExpect(jsonPath("$.billing.ranges[0].lowCny").value(0.1872))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("apiKey"))));
    }

    @Test
    void billingRefreshReturnsTheSameShapeAsStatusBilling() throws Exception {
        var billing = new BillingSettingsResponse(new BigDecimal("6.7"), "LIVE",
                "2026-10-24T11:40:00Z", false, "公开参考汇率", List.of());
        when(service.refreshBilling()).thenReturn(billing);

        mvc.perform(post("/api/settings/billing/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usdToCny").value(6.7))
                .andExpect(jsonPath("$.source").value("LIVE"));
    }
}
