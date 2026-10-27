package com.link.linkagent.creator.creation.controller;

import com.link.linkagent.creator.creation.service.CreationRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CreationRuntimeControllerTest {
    @Test
    void budgetEndpointsMustRejectMissingInvalidAndOverpreciseMoney() throws Exception {
        var service = mock(CreationRuntimeService.class);
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var mvc = MockMvcBuilders.standaloneSetup(new CreationRuntimeController(service)).setValidator(validator).build();
            for (String body : new String[]{"{}", "{\"expectedWorkspaceRevision\":-2,\"budgetLimitCny\":20}",
                    "{\"expectedWorkspaceRevision\":-1,\"budgetLimitCny\":0}",
                    "{\"expectedWorkspaceRevision\":-1,\"budgetLimitCny\":0.0000001}",
                    "{\"expectedWorkspaceRevision\":-1,\"budgetLimitCny\":1000000000000}"}) {
                mvc.perform(post("/api/creator/tasks/task-1/creation").header("Idempotency-Key", "budget")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            }
            mvc.perform(post("/api/creator/tasks/task-1/creation").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedWorkspaceRevision\":-1,\"budgetLimitCny\":20}")).andExpect(status().isBadRequest());
            mvc.perform(patch("/api/creator/tasks/task-1/creation/budget").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedWorkspaceRevision\":-1,\"budgetLimitCny\":20}")).andExpect(status().isBadRequest());
            verifyNoInteractions(service);
        }
    }

    @Test
    void queriesMustUseNoStoreAndNoPublicStartEndpointExists() throws Exception {
        var service = mock(CreationRuntimeService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CreationRuntimeController(service)).build();
        mvc.perform(get("/api/creator/tasks/task-1/creation/runtime")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/creator/tasks/task-1/creation/runs/run-1")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/creator/tasks/task-1/creation/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"FULL\"}")).andExpect(status().isNotFound());
        verify(service).snapshot("task-1", "default");
        verify(service).getRun("task-1", "default", "run-1");
        verifyNoMoreInteractions(service);
    }
}
