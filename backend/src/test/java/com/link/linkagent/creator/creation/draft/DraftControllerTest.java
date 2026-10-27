package com.link.linkagent.creator.creation.draft;

import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DraftControllerTest {
    private final DraftService service=mock(DraftService.class);
    private final MockMvc mvc=MockMvcBuilders.standaloneSetup(new DraftController(service)).build();
    private static final String ROOT="/api/creator/tasks/task-1/creation/drafts";
    private static final String INPUT="{\"expectedWorkspaceRevision\":0,\"baseDraftId\":null,\"instruction\":\"\",\"budgetLimitCny\":20,\"targetDurationMs\":60000,\"assetIds\":[]}";
    @Test void firstDraftAcceptsAnIncompleteCreativeBriefButRequiresAnExplicitBudgetAndRequestKey()throws Exception {
        mvc.perform(post(ROOT).header("Idempotency-Key","draft-key").contentType(MediaType.APPLICATION_JSON).content(INPUT))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).start(eq("task-1"),eq("default"),eq("draft-key"),any(DraftModels.Start.class));
        mvc.perform(post(ROOT).contentType(MediaType.APPLICATION_JSON).content(INPUT)).andExpect(status().isBadRequest());
        mvc.perform(post(ROOT).header("Idempotency-Key","no-budget").contentType(MediaType.APPLICATION_JSON).content(INPUT.replace("\"budgetLimitCny\":20,",""))).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }
    @Test void progressIsReadOnlyAndCancellationUsesTheExactClientRoute()throws Exception {
        mvc.perform(get(ROOT)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).workspace("task-1","default");
        mvc.perform(post(ROOT+"/draft-1:cancel")).andExpect(status().isOk());
        verify(service).cancel("task-1","default","draft-1");
    }
    @Test void aShortIdeaIsEnoughWhileBlankAndOversizedInputsAreRejected() {
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            org.assertj.core.api.Assertions.assertThat(validator.validate(new com.link.linkagent.creator.interactive.model.InteractiveTaskCreateRequest(null,"月亮维修员",null))).isEmpty();
            org.assertj.core.api.Assertions.assertThat(validator.validate(new com.link.linkagent.creator.interactive.model.InteractiveTaskCreateRequest(null," ",null))).isNotEmpty();
            org.assertj.core.api.Assertions.assertThat(validator.validate(new com.link.linkagent.creator.interactive.model.InteractiveTaskCreateRequest(null,"字".repeat(3001),null))).isNotEmpty();
        }
    }
}
