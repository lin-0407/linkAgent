package com.link.linkagent.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantControllerTest {
    @Test void malformedActionAndUnconfirmedRequestMustBeRejectedBeforeCallingBusiness() throws Exception {
        var actions=mock(AssistantActionService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AssistantController(
                mock(PlatformAssistantContextService.class),actions,mock(AssistantMapper.class))).build();
        mvc.perform(post("/api/assistant/actions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":\"task-1\",\"actionType\":\"DELETE_MEDIA\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/assistant/actions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":\"\",\"actionType\":\"GENERATE_REVIEW\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/assistant/actions/p-1/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"taskId\":\"task-1\",\"confirmed\":false}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(actions);
    }
}
