package com.link.linkagent.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantPlanControllerTest {
    @Test void malformedOrUnconfirmedPlanAndInputsMustNeverReachExecutor() throws Exception {
        var service=mock(AssistantPlanService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AssistantPlanController(service,mock(PlatformOperationCatalog.class),new ObjectMapper())).build();
        for(String body:new String[]{"{}","{\"title\":\"目标\",\"steps\":[]}","{\"title\":\"目标\",\"steps\":[{\"id\":\"bad:id\",\"operation\":\"tasks.createTask\",\"arguments\":{}}]}"})
            mvc.perform(post("/api/assistant/plans").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/assistant/plans/p/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":false,\"digest\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/assistant/plans/p/inputs").contentType(MediaType.APPLICATION_JSON).content("{\"confirmed\":true,\"values\":{}}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
