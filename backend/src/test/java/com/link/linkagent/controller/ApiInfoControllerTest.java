package com.link.linkagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiInfoControllerTest {

    @Test
    void shouldDescribeApiWithOrWithoutTrailingSlash() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ApiInfoController()).build();

        for (String path : new String[]{"/api", "/api/"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("LinkAgent API"))
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.statusUrl").value("/api/settings/status"));
        }
    }
}
