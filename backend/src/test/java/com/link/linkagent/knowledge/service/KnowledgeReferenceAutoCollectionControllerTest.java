package com.link.linkagent.knowledge.service;

import com.link.linkagent.knowledge.controller.KnowledgeReferenceAutoCollectionController;
import com.link.linkagent.knowledge.model.ReferenceAutoCollectionRequest;
import com.link.linkagent.knowledge.model.ReferenceAutoCollectionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeReferenceAutoCollectionControllerTest {
    @Test
    void shouldExposeStartEntryAndRejectInvalidSettings() throws Exception {
        var service = mock(KnowledgeReferenceAutoCollectionService.class);
        when(service.start(any(), anyInt(), anyString())).thenReturn(new ReferenceAutoCollectionResponse(
                "run-1", "RUNNING", null, null, "正在采集", new ReferenceAutoCollectionRequest(60.0, 1, 1, List.of()), null));
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeReferenceAutoCollectionController(service)).build();
        mvc.perform(post("/api/knowledge/reference-videos/auto-collection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"minIndex\":60,\"maxImports\":1,\"maxChecks\":1,\"rids\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
        // 单轮入库上限不再限制 1000，页面填更大值也应放行，只校验正整数。
        mvc.perform(post("/api/knowledge/reference-videos/auto-collection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"minIndex\":60,\"maxImports\":10000,\"maxChecks\":10000,\"rids\":[]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/knowledge/reference-videos/auto-collection").contentType(MediaType.APPLICATION_JSON)
                .content("{\"minIndex\":101,\"maxImports\":0,\"rids\":[-1]}"))
                .andExpect(status().isBadRequest());
    }
}
