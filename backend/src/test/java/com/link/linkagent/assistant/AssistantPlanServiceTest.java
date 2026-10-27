package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.*;
import com.link.linkagent.assistant.AssistantPlanModels.*;
import com.link.linkagent.assistant.AssistantModels.TaskContext;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssistantPlanServiceTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final Map<String,Row> rows=new ConcurrentHashMap<>();
    private AssistantPlanMapper mapper;
    private PlatformOperationCatalog catalog;
    private PlatformAssistantContextService context;
    private AssistantPlanService service;
    @BeforeEach void setup() {
        mapper=mock(AssistantPlanMapper.class); catalog=mock(PlatformOperationCatalog.class); context=mock(PlatformAssistantContextService.class);
        when(mapper.insert(any())).thenAnswer(call -> { Row row=call.getArgument(0); rows.put(row.planId(),row); return 1; });
        when(mapper.find(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(mapper.recent()).thenAnswer(call -> new ArrayList<>(rows.values()));
        when(mapper.waiting()).thenAnswer(call -> rows.values().stream().filter(r -> r.status().equals("WAITING")).toList());
        when(mapper.update(anyString(),anyInt(),anyString(),anyString())).thenAnswer(call -> {
            AtomicInteger changed=new AtomicInteger();
            rows.computeIfPresent(call.getArgument(0),(id,row) -> {
                if(row.revision()!=(int)call.getArgument(1)) return row;
                changed.incrementAndGet(); return new Row(row.planId(),row.taskId(),row.title(),call.getArgument(2),row.definitionJson(),call.getArgument(3),row.revision()+1,row.digest(),row.createTime());
            }); return changed.get();
        });
        when(catalog.entry(anyString())).thenAnswer(call -> new PlatformOperationCatalog.Entry(call.getArgument(0),"任务","操作","","","WRITE"));
        when(catalog.describe(anyString())).thenAnswer(call -> new PlatformOperationCatalog.Operation(call.getArgument(0),"任务","操作","WRITE","NONE",json.readTree("{\"type\":\"object\",\"properties\":{}}"),"保存业务结果"));
        when(catalog.sanitize(any())).thenAnswer(call -> ((JsonNode)call.getArgument(0)).deepCopy());
        when(context.actionSource(anyString())).thenReturn("固定来源");
        when(context.inspect(anyString(),anyString())).thenReturn(new TaskContext("/creator","task-1","教学任务","DRAFT",null,Map.of(),List.of()));
        when(catalog.invoke(anyString(),any(),anyString(),anyList())).thenReturn(json.createObjectNode().put("taskId","created-task"));
        when(catalog.query(anyString(),any())).thenReturn(json.createObjectNode().put("taskId","created-task"));
        service=new AssistantPlanService(mapper,catalog,context,json);
    }
    @AfterEach void close() { service.close(); }
    private Step step(String id,String operation,String arguments) throws Exception { return new Step(id,operation,json.readTree(arguments),"保存具体结果"); }
    private View prepare(Step... steps) { return service.prepare(new Prepare(null,"创建教学任务并继续处理",List.of(steps))); }
    private void confirm(View plan) { service.confirm(plan.planId(),new Approval(true,plan.digest())); }
    private View await(String id,String status) throws Exception {
        for(int i=0;i<250;i++) { View view=service.get(id); if(view.status().equals(status)) return view; Thread.sleep(20); }
        throw new AssertionError("实际状态："+service.get(id).status()+" / "+service.get(id).state().message);
    }
    @Test void preparingMustNotExecuteAndEightConcurrentConfirmationsMustExecuteOnce() throws Exception {
        View plan=prepare(step("create","tasks.createTask","{\"request\":{\"titleDraft\":\"Java并发\"}}"));
        verify(catalog,never()).invoke(anyString(),any(),anyString(),anyList());
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1); var futures=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(() -> { start.await(); confirm(plan); return null; }));
            start.countDown(); for(var future:futures) future.get(5,TimeUnit.SECONDS);
        }
        View done=await(plan.planId(),"SUCCEEDED");
        assertThat(done.state().steps.getFirst().verification.path("kind").asText()).isEqualTo("READ_BACK");
        verify(catalog,times(1)).invoke(eq("tasks.createTask"),any(),anyString(),anyList());
    }
    @Test void laterStepsMustUseActualCreatedIdAndMustNotInventFutureResults() throws Exception {
        View plan=prepare(step("create","tasks.createTask","{\"request\":{\"titleDraft\":\"Java并发\"}}"),
                step("edit","tasks.updateTask","{\"taskId\":{\"$ref\":\"create:/taskId\"},\"request\":{\"taskName\":\"更新后的名称\"}}"));
        confirm(plan); await(plan.planId(),"SUCCEEDED");
        verify(catalog).invoke(eq("tasks.updateTask"),argThat(args -> args.path("taskId").asText().equals("created-task")),anyString(),anyList());
        assertThatThrownBy(() -> prepare(step("bad","tasks.updateTask","{\"taskId\":{\"$ref\":\"later:/taskId\"}}"))).hasMessageContaining("前面");
    }
    @Test void changedOrUnconfirmedPlanAndChangedSourceMustNotExecute() throws Exception {
        View plan=prepare(step("edit","tasks.updateTask","{\"taskId\":\"task-1\",\"request\":{\"taskName\":\"新名称\"}}"));
        assertThatThrownBy(() -> service.confirm(plan.planId(),new Approval(false,plan.digest()))).hasMessageContaining("确认");
        assertThatThrownBy(() -> service.confirm(plan.planId(),new Approval(true,"wrong"))).hasMessageContaining("确认");
        when(context.actionSource("task-1")).thenReturn("别人刚修改的来源");
        assertThatThrownBy(() -> confirm(plan)).hasMessageContaining("资料已变化");
        verify(catalog,never()).invoke(anyString(),any(),anyString(),anyList());
    }
    @Test void userInputMustPauseAndCannotChangeAnyAlreadyApprovedParameter() throws Exception {
        View plan=prepare(step("edit","tasks.updateTask","{\"taskId\":\"task-1\",\"request\":{\"taskName\":{\"$input\":\"新任务名称\"}}}"));
        confirm(plan); View waiting=await(plan.planId(),"WAIT_INPUT");
        assertThat(waiting.state().steps.getFirst().inputs).extracting(InputField::path).containsExactly("/request/taskName");
        assertThatThrownBy(() -> service.inputs(plan.planId(),new Inputs(true,plan.digest(),json.readTree("{\"taskId\":\"other\"}")),List.of())).hasMessageContaining("不能修改");
        service.inputs(plan.planId(),new Inputs(true,plan.digest(),json.readTree("{\"/request/taskName\":\"已明确填写的名称\"}")),List.of());
        await(plan.planId(),"SUCCEEDED");
        verify(catalog).invoke(eq("tasks.updateTask"),argThat(args -> args.at("/request/taskName").asText().equals("已明确填写的名称") && args.path("taskId").asText().equals("task-1")),anyString(),anyList());
    }
    @Test void generatedOptionMustWaitForUserChoiceInsteadOfAutoConfirmingFirstResult() throws Exception {
        assertThatThrownBy(() -> prepare(step("generate","creative.generateCreativeOptions","{\"taskId\":\"task-1\"}"),
                step("confirm","creative.confirmOption","{\"taskId\":\"task-1\",\"optionId\":{\"$ref\":\"generate:/options/0/optionId\"}}")))
                .hasMessageContaining("等待用户选择");
    }
    @Test void paidFailureMustKeepEarlierResultAndMustNeverAutomaticallyRetry() throws Exception {
        when(catalog.invoke(eq("review.analyze"),any(),anyString(),anyList())).thenThrow(new IllegalStateException("供应商暂时失败"));
        View plan=prepare(step("create","tasks.createTask","{\"request\":{\"titleDraft\":\"Java并发\"}}"),step("review","review.analyze","{\"taskId\":{\"$ref\":\"create:/taskId\"}}"));
        confirm(plan); View failed=await(plan.planId(),"FAILED");
        assertThat(failed.state().steps.getFirst().status).isEqualTo("SUCCEEDED");
        service.resume(plan.planId()); confirm(plan);
        verify(catalog,times(1)).invoke(eq("review.analyze"),any(),anyString(),anyList());
    }
    @Test void asyncProcessingMustPollSameJobAndContinueWithoutResubmitting() throws Exception {
        when(catalog.invoke(eq("processing.createJob"),any(),anyString(),anyList())).thenReturn(json.readTree("{\"jobId\":\"job-1\",\"taskId\":\"task-1\",\"versionId\":\"video-1\",\"status\":\"QUEUED\"}"));
        when(catalog.query(eq("processing.getCurrentJob"),any())).thenReturn(json.readTree("{\"jobId\":\"job-1\",\"taskId\":\"task-1\",\"versionId\":\"video-1\",\"status\":\"COMPLETED\"}"));
        View plan=prepare(step("processing","processing.createJob","{\"taskId\":\"task-1\",\"versionId\":\"video-1\"}"),step("read","tasks.getTask","{\"taskId\":\"task-1\"}"));
        confirm(plan); await(plan.planId(),"WAITING"); service.pollWaiting(); await(plan.planId(),"SUCCEEDED");
        verify(catalog,times(1)).invoke(eq("processing.createJob"),any(),anyString(),anyList());
    }
    @Test void interruptedUnknownWriteMustRefuseResumeButCompletedCheckpointCanContinue() throws Exception {
        View plan=prepare(step("first","tasks.createTask","{\"request\":{\"titleDraft\":\"Java并发\"}}"));
        Row old=rows.get(plan.planId()); State state=plan.state(); state.steps.getFirst().status="RUNNING";
        rows.put(old.planId(),new Row(old.planId(),null,old.title(),"INTERRUPTED",old.definitionJson(),json.writeValueAsString(state),1,old.digest(),old.createTime()));
        assertThatThrownBy(() -> service.resume(plan.planId())).hasMessageContaining("不会自动重发");
        state.steps.getFirst().status="SUCCEEDED";
        rows.put(old.planId(),new Row(old.planId(),null,old.title(),"INTERRUPTED",old.definitionJson(),json.writeValueAsString(state),2,old.digest(),old.createTime()));
        service.resume(plan.planId()); await(plan.planId(),"SUCCEEDED");
        verify(catalog,never()).invoke(anyString(),any(),anyString(),anyList());
    }
    @Test void dedicatedSecretInputMustReachConfigurationOnlyAndNeverPersistEvenOnFailure() throws Exception {
        service.close();
        try(var app=new org.springframework.context.support.GenericApplicationContext(); var validation=Validation.buildDefaultValidatorFactory()) {
            var controller=mock(com.link.linkagent.llm.config.controller.UserLlmConfigController.class);
            app.registerBean(com.link.linkagent.llm.config.controller.UserLlmConfigController.class,() -> controller); app.refresh();
            var actualCatalog=new PlatformOperationCatalog(json,validation.getValidator(),app);
            service=new AssistantPlanService(mapper,actualCatalog,context,json);
            when(controller.saveConfig(isNull(),any())).thenReturn(json.convertValue(Map.of("configId","cfg","provider","CUSTOM","llmApiKeyMasked","****"),com.link.linkagent.llm.config.model.UserLlmConfigResponse.class));
            View plan=prepare(step("config","models.saveConfig","{\"request\":{\"provider\":\"CUSTOM\",\"llmApiKey\":{\"$input\":\"新的API密钥\",\"secret\":true}}}"));
            confirm(plan); View waiting=await(plan.planId(),"WAIT_INPUT");
            assertThat(waiting.state().steps.getFirst().inputs.getFirst().secret()).isTrue();
            service.inputs(plan.planId(),new Inputs(true,plan.digest(),json.readTree("{\"/request/llmApiKey\":\"fake-isolated-secret-123\"}")),List.of());
            await(plan.planId(),"SUCCEEDED");
            verify(controller).saveConfig(isNull(),argThat(value -> value.llmApiKey().equals("fake-isolated-secret-123")));
            assertThat(json.writeValueAsString(service.recent())).doesNotContain("fake-isolated-secret-123");
            assertThat(rows.values()).allMatch(value -> !value.stateJson().contains("fake-isolated-secret-123") && !value.definitionJson().contains("fake-isolated-secret-123"));
            when(controller.saveConfig(isNull(),any())).thenThrow(new IllegalStateException("供应商错误里出现 fake-isolated-secret-456"));
            View failed=prepare(step("config","models.saveConfig","{\"request\":{\"provider\":\"CUSTOM\",\"llmApiKey\":{\"$input\":\"新的API密钥\",\"secret\":true}}}"));
            confirm(failed); await(failed.planId(),"WAIT_INPUT");
            service.inputs(failed.planId(),new Inputs(true,failed.digest(),json.readTree("{\"/request/llmApiKey\":\"fake-isolated-secret-456\"}")),List.of());
            await(failed.planId(),"FAILED");
            assertThat(json.writeValueAsString(service.recent())).doesNotContain("fake-isolated-secret-456");
        }
    }
    @Test void stopDuringBusinessCallMustPreserveResultAndSkipRemainingSteps() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(catalog.invoke(eq("tasks.createTask"),any(),anyString(),anyList())).thenAnswer(call -> { entered.countDown(); release.await(5,TimeUnit.SECONDS); return json.createObjectNode().put("taskId","created-task"); });
        View plan=prepare(step("create","tasks.createTask","{\"request\":{\"titleDraft\":\"Java并发\"}}"),step("later","review.analyze","{\"taskId\":{\"$ref\":\"create:/taskId\"}}"));
        confirm(plan); assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        service.cancel(plan.planId()); release.countDown();
        View stopped=await(plan.planId(),"CANCELLED");
        assertThat(stopped.state().steps.getFirst().status).isEqualTo("SUCCEEDED");
        verify(catalog,never()).invoke(eq("review.analyze"),any(),anyString(),anyList());
    }
}
