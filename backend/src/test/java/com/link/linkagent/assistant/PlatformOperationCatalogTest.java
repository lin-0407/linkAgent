package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.*;
import com.link.linkagent.creator.task.controller.CreatorTaskController;
import com.link.linkagent.creator.task.service.CreatorTaskService;
import com.link.linkagent.creator.task.model.*;
import com.link.linkagent.creator.interactive.controller.CreatorInteractiveController;
import com.link.linkagent.creator.interactive.service.CreatorInteractiveService;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.springframework.context.support.GenericApplicationContext;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlatformOperationCatalogTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private GenericApplicationContext app;
    private ValidatorFactory validation;
    private PlatformOperationCatalog catalog;
    private CreatorTaskService tasks;
    @BeforeEach void setup() throws Exception {
        app=new GenericApplicationContext(); validation=Validation.buildDefaultValidatorFactory();
        tasks=mock(CreatorTaskService.class);
        app.registerBean(CreatorTaskController.class,() -> new CreatorTaskController(tasks));
        app.registerBean(CreatorInteractiveController.class,() -> new CreatorInteractiveController(mock(CreatorInteractiveService.class)));
        app.refresh(); catalog=new PlatformOperationCatalog(json,validation.getValidator(),app);
    }
    @AfterEach void close() { app.close(); validation.close(); }
    @Test void everyRegisteredBusinessOperationMustResolveRealMethodAndDescribeParameters() {
        assertThat(catalog.index()).hasSizeGreaterThan(100);
        for (var entry:catalog.index()) assertThat(catalog.describe(entry.get("id")).parameters().path("type").asText()).isEqualTo("object");
        assertThat(catalog.describe("creative.createInteractiveTask").parameters().at("/properties/request/properties/idea/minLength").asInt()).isEqualTo(10);
        assertThat(catalog.describe("blueprint.create").parameters().at("/properties/request/properties/videoCategory/enum").size()).isPositive();
        assertThat(catalog.describe("creative.uploadContextDocuments").interaction()).isEqualTo("FILES");
        assertThat(catalog.describe("models.saveConfig").parameters().at("/properties/request/properties/llmApiKey/writeOnly").asBoolean()).isTrue();
        assertThat(catalog.index()).noneMatch(e -> e.get("id").contains("signParts") || e.get("id").contains("Approval"));
    }
    @Test void readEntryMustRejectEveryWriteWithoutCallingBusiness() {
        assertThatThrownBy(() -> catalog.query("tasks.deleteTask",json.readTree("{\"taskId\":\"t\"}"))).hasMessageContaining("确认计划");
        assertThatThrownBy(() -> catalog.entry("arbitrary.http")).hasMessageContaining("目录");
        verifyNoInteractions(tasks);
    }
    @Test void validationMustApplyExistingDtoAndHttpParameterRulesBeforeInvocation() throws Exception {
        assertThatThrownBy(() -> catalog.validate("tasks.createTask",json.readTree("{}"),false)).hasMessageContaining("缺少请求参数");
        assertThatThrownBy(() -> catalog.validate("tasks.createTask",json.readTree("{\"request\":{\"taskName\":\"空任务\"}}"),false)).isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> catalog.validate("creative.createInteractiveTask",json.readTree("{\"request\":{\"idea\":\"太短\"}}"),false)).isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> catalog.validate("tasks.getTask",json.readTree("{\"taskId\":\"t\",\"sql\":\"delete\"}"),false)).hasMessageContaining("不接受参数");
        verifyNoInteractions(tasks);
    }
    @Test void typedInvocationAndDefaultsMustReachSameControllerServiceAsPage() throws Exception {
        var result=new CreatorTaskResponse(1L,"created","default","并发教学","知识", "DRAFT",false,null,null,List.of());
        when(tasks.createTask(any())).thenReturn(result); when(tasks.getTask("created")).thenReturn(result);
        JsonNode actual=catalog.invoke("tasks.createTask",json.readTree("{\"request\":{\"taskName\":\"并发教学\",\"titleDraft\":\"Java线程安全\"}}"),"key",List.of());
        assertThat(actual.path("taskId").asText()).isEqualTo("created");
        assertThat(catalog.query("tasks.getTask",json.readTree("{\"taskId\":\"created\"}")).path("taskName").asText()).isEqualTo("并发教学");
        verify(tasks).createTask(argThat(r -> r.titleDraft().equals("Java线程安全")));
    }
    @Test void secretMaterialAndPrivateLinksMustNotEnterPlanOrModelResults() throws Exception {
        assertThatThrownBy(() -> catalog.validate("models.saveConfig",json.readTree("{\"request\":{\"provider\":\"CUSTOM\",\"llmApiKey\":\"fake-test-secret\"}}"),true)).hasMessageContaining("专用输入框");
        assertThatCode(() -> catalog.validate("models.saveConfig",json.readTree("{\"request\":{\"provider\":\"CUSTOM\",\"llmApiKey\":{\"$input\":\"新的API密钥\",\"secret\":true}}}"),true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> catalog.validate("models.saveConfig",json.readTree("{\"request\":{\"llmApiKey\":{\"$input\":\"密钥\",\"secret\":true,\"value\":\"fake-test-secret\"}}}"),true)).hasMessageContaining("不能附带原值");
        JsonNode sanitized=catalog.sanitize(json.readTree("{\"taskId\":\"t\",\"llmApiKeyEnc\":\"fake\",\"assets\":[{\"readUrl\":\"https://example.test?X-Amz-Signature=fake\"}]}"));
        assertThat(sanitized.toString()).doesNotContain("fake").contains("私有媒体");
    }
    @Test void renamingFromAssistantMustPreserveUnmentionedOriginalMaterials() throws Exception {
        var material=json.convertValue(Map.of("id",1,"materialType","MANUSCRIPT","content","不能被改名操作清空的原稿"),CreatorMaterialResponse.class);
        var original=new CreatorTaskResponse(1L,"task-1","default","旧名称","知识", "DRAFT",false,null,null,List.of(material));
        when(tasks.getTask("task-1")).thenReturn(original); when(tasks.updateTask(eq("task-1"),any())).thenReturn(original);
        catalog.invoke("tasks.updateTask",json.readTree("{\"taskId\":\"task-1\",\"request\":{\"taskName\":\"新名称\"}}"),"key",List.of());
        verify(tasks).updateTask(eq("task-1"),argThat(value -> value.taskName().equals("新名称")
                && value.manuscript().equals("不能被改名操作清空的原稿") && value.videoType().equals("知识")));
    }
}
