package com.link.linkagent.creator.creation.controller;

import com.link.linkagent.creator.creation.service.CreationWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CreationWorkspaceControllerTest {
    @Test
    void selectionMustRejectMissingAndNegativeRevisionWithoutCallingBusinessService() throws Exception {
        var service = mock(CreationWorkspaceService.class);
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var mvc = MockMvcBuilders.standaloneSetup(new CreationWorkspaceController(service)).setValidator(validator).build();
            for (String body : new String[]{"{}", "{\"expectedWorkspaceRevision\":null}", "{\"expectedWorkspaceRevision\":-1}"}) {
                mvc.perform(post("/api/creator/tasks/task-1/creation/versions/version-1:select")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            }
            verifyNoInteractions(service);
        }
    }
}
