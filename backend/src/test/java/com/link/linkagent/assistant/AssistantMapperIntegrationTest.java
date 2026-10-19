package com.link.linkagent.assistant;

import com.link.linkagent.assistant.AssistantModels.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 使用独立 MySQL 容器核对真实 Schema、记录映射和抢占，结束时由 Testcontainers 清理。 */
@Testcontainers
class AssistantMapperIntegrationTest {
    @Container static final GenericContainer<?> MYSQL=new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD","assistant-isolated-test")
            .withEnv("MYSQL_DATABASE","link_agent")
            .withCopyFileToContainer(MountableFile.forClasspathResource("sql/init.sql"),"/docker-entrypoint-initdb.d/01-init.sql")
            .withExposedPorts(3306).waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    private static AssistantMapper mapper;
    private static AssistantPlanMapper plans;
    private static DriverManagerDataSource dataSource;

    @BeforeAll static void setup() throws Exception {
        dataSource=new DriverManagerDataSource("jdbc:mysql://"+MYSQL.getHost()+":"+MYSQL.getMappedPort(3306)
                +"/link_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","assistant-isolated-test");
        var configuration=new Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AssistantMapper.class);
        configuration.addMapper(AssistantPlanMapper.class);
        configuration.addMapper(com.link.linkagent.creator.task.mapper.CreatorTaskMapper.class);
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(dataSource); factory.setConfiguration(configuration);
        factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(AssistantMapper.class);
        plans=new SqlSessionTemplate(factory.getObject()).getMapper(AssistantPlanMapper.class);
    }

    @Test void actualSqlMustRoundTripChineseProposalAndClaimOnlyOnce() throws Exception {
        var now=LocalDateTime.now(); String id=UUID.randomUUID().toString();
        var value=new ActionProposal(id,"task-sql","项目介绍",ActionType.GENERATE_REVIEW,"关注误解", "调用模型并保存复盘",
                "a".repeat(64),"PENDING",null,now,now.plusMinutes(15));
        assertThat(mapper.insertAction(value)).isEqualTo(1);
        assertThat(mapper.findAction(id).orElseThrow().actionType()).isEqualTo(ActionType.GENERATE_REVIEW);
        assertThat(mapper.listActions("task-sql")).extracting(ActionProposal::taskName).contains("项目介绍");
        var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(8)) {
            var futures=new java.util.ArrayList<Future<Integer>>();
            for (int i=0;i<8;i++) futures.add(pool.submit(() -> { start.await(); return mapper.claimAction(id,now); }));
            start.countDown(); int won=0;
            for (var future:futures) won+=future.get(10,TimeUnit.SECONDS);
            assertThat(won).isEqualTo(1);
        }
        assertThat(mapper.finishAction(id,"SUCCEEDED","报告已保存")).isEqualTo(1);
        assertThat(mapper.claimAction(id,now)).isZero();
        assertThat(mapper.findAction(id).orElseThrow().resultMessage()).isEqualTo("报告已保存");
    }

    @Test void expiredProposalAndCompletedTraceMustNotBeReclaimedOrOverwritten() {
        var now=LocalDateTime.now(); String id=UUID.randomUUID().toString();
        mapper.insertAction(new ActionProposal(id,"expired","任务",ActionType.ANALYZE_FEEDBACK,"","影响说明",
                "b".repeat(64),"PENDING",null,now.minusMinutes(30),now.minusMinutes(15)));
        assertThat(mapper.claimAction(id,now)).isZero();
        String runId=UUID.randomUUID().toString();
        mapper.insertTrace(runId,"trace-task","发布前方案取证","PLAN_EXECUTE");
        assertThat(mapper.finishTrace(runId,"SUCCEEDED","{\"steps\":[]}",null)).isEqualTo(1);
        assertThat(mapper.finishTrace(runId,"FAILED",null,"晚到失败")).isZero();
        var trace=mapper.listTraces("trace-task").getFirst();
        assertThat(trace.executionMode()).isEqualTo("PLAN_EXECUTE");
        assertThat(trace.status()).isEqualTo("SUCCEEDED");
        assertThat(trace.traceJson()).contains("steps");
    }

    @Test void durablePlanMustRoundTripCheckpointsAndOnlyOneConcurrentConfirmationCanClaim() throws Exception {
        String id=UUID.randomUUID().toString();
        var row=new AssistantPlanModels.Row(id,null,"创建任务并准备方案","PENDING",
                "{\"steps\":[{\"id\":\"create\"}]}","{\"steps\":[]}",0,"c".repeat(64),LocalDateTime.now());
        assertThat(plans.insert(row)).isEqualTo(1);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new java.util.ArrayList<Future<Integer>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(() -> { start.await(); return plans.update(id,0,"RUNNING","{\"steps\":[{\"status\":\"RUNNING\"}]}"); }));
            start.countDown(); int won=0;
            for(var future:futures) won+=future.get(10,TimeUnit.SECONDS);
            assertThat(won).isEqualTo(1);
        }
        var persisted=plans.find(id).orElseThrow();
        assertThat(persisted.title()).isEqualTo("创建任务并准备方案");
        assertThat(persisted.taskId()).isNull();
        assertThat(persisted.revision()).isEqualTo(1);
        assertThat(plans.update(id,0,"SUCCEEDED","{}")).isZero();
        assertThat(plans.update(id,1,"WAITING","{\"steps\":[{\"result\":{\"taskId\":\"真实任务\"}}]}")).isEqualTo(1);
        assertThat(plans.waiting()).anyMatch(value -> value.planId().equals(id) && value.stateJson().contains("真实任务"));
        plans.interruptPreviousRuns();
        assertThat(plans.find(id).orElseThrow().status()).isEqualTo("WAITING");
        assertThat(plans.recent()).anyMatch(value -> value.planId().equals(id));
    }

    @Test void globalAssistantToolAndHttpConfirmationMustCreateAndUpdateActualTaskThenImportFile() throws Exception {
        var configuration=new Configuration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(com.link.linkagent.creator.task.mapper.CreatorTaskMapper.class);
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(dataSource); factory.setConfiguration(configuration); factory.afterPropertiesSet();
        var tasks=new SqlSessionTemplate(factory.getObject()).getMapper(com.link.linkagent.creator.task.mapper.CreatorTaskMapper.class);
        var proxy=new org.springframework.aop.framework.ProxyFactory(new com.link.linkagent.creator.task.service.CreatorTaskService(tasks));
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var taskService=(com.link.linkagent.creator.task.service.CreatorTaskService)proxy.getProxy();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        try(var app=new org.springframework.context.support.GenericApplicationContext();
            var validation=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            app.registerBean(com.link.linkagent.creator.task.controller.CreatorTaskController.class,
                    () -> new com.link.linkagent.creator.task.controller.CreatorTaskController(taskService));
            app.refresh(); var catalog=new PlatformOperationCatalog(json,validation.getValidator(),app);
            var context=org.mockito.Mockito.mock(PlatformAssistantContextService.class);
            org.mockito.Mockito.when(context.actionSource(org.mockito.ArgumentMatchers.anyString())).thenReturn("测试来源");
            org.mockito.Mockito.when(context.inspect(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(new AssistantModels.TaskContext("/creator","当前任务","隔离教学任务","DRAFT",null,java.util.Map.of(),java.util.List.of()));
            var service=new AssistantPlanService(plans,catalog,context,json);
            try {
                var provider=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
                org.mockito.Mockito.when(provider.getObject()).thenReturn(service);
                var tool=new ProposePlatformPlanTool(provider,json,validation.getValidator());
                var registry=new com.link.linkagent.tool.ToolRegistry(java.util.List.of(tool)); registry.init();
                var executor=new com.link.linkagent.tool.ToolExecutor(registry,new com.link.linkagent.tool.ToolExecutionProperties(20,0));
                String request="""
                    {"title":"新建任务并修改标题","steps":[
                      {"id":"create","operation":"tasks.createTask","arguments":{"request":{"taskName":"隔离创建","titleDraft":"原始标题"}}},
                      {"id":"edit","operation":"tasks.updateTask","arguments":{"taskId":{"$ref":"create:/taskId"},"request":{"taskName":"隔离更新","titleDraft":"更新标题"}}}
                    ]}
                    """;
                String planId;
                try(var scope=AssistantInvocationContext.open(new AssistantInvocationContext.State(null,true))) {
                    var observation=executor.execute(new com.link.linkagent.core.ToolCall(tool.getName(),request));
                    assertThat(observation.result()).doesNotStartWith("Error:");
                    planId=json.readTree(observation.result()).path("planId").asText();
                }
                var pending=service.get(planId); assertThat(pending.status()).isEqualTo("PENDING");
                var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new AssistantPlanController(service,catalog,json)).build();
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/assistant/plans/"+planId+"/confirm")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AssistantPlanModels.Approval(true,pending.digest()))))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
                var complete=awaitPlan(service,planId,"SUCCEEDED");
                String taskId=complete.state().steps.getFirst().result.path("taskId").asText();
                assertThat(taskService.getTask(taskId).taskName()).isEqualTo("隔离更新");
                assertThat(taskService.getTask(taskId).materials()).anyMatch(m -> m.content().equals("更新标题"));
                var importPlan=service.prepare(new AssistantPlanModels.Prepare(taskId,"导入用户选择的文稿",java.util.List.of(
                        new AssistantPlanModels.Step("file","tasks.importMaterial",json.createObjectNode().put("taskId",taskId).put("materialType","MANUSCRIPT"),"保存所选文稿"))));
                service.confirm(importPlan.planId(),new AssistantPlanModels.Approval(true,importPlan.digest()));
                awaitPlan(service,importPlan.planId(),"WAIT_INPUT");
                var file=new org.springframework.mock.web.MockMultipartFile("files","manuscript.txt","text/plain","真实文件导入内容".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                service.inputs(importPlan.planId(),new AssistantPlanModels.Inputs(true,importPlan.digest(),json.createObjectNode()),java.util.List.of(file));
                awaitPlan(service,importPlan.planId(),"SUCCEEDED");
                assertThat(taskService.getTask(taskId).materials()).anyMatch(m -> m.content().equals("真实文件导入内容"));
                // 原业务仍禁止确认发布后的材料修改，助手不能绕过这个门禁。
                tasks.updateTaskStatus(taskId,"PUBLISHED");
                var blocked=service.prepare(new AssistantPlanModels.Prepare(taskId,"尝试修改已发布任务",java.util.List.of(
                        new AssistantPlanModels.Step("edit","tasks.updateTask",json.readTree("{\"taskId\":\""+taskId+"\",\"request\":{\"titleDraft\":\"不应写入\"}}"),"检查原业务门禁"))));
                service.confirm(blocked.planId(),new AssistantPlanModels.Approval(true,blocked.digest()));
                awaitPlan(service,blocked.planId(),"FAILED");
                assertThat(taskService.getTask(taskId).materials()).noneMatch(m -> m.content().equals("不应写入"));
            } finally { service.close(); }
        }
    }

    private AssistantPlanModels.View awaitPlan(AssistantPlanService service,String id,String expected) throws Exception {
        for(int i=0;i<250;i++) { var value=service.get(id); if(value.status().equals(expected)) return value; Thread.sleep(20); }
        throw new AssertionError("实际状态："+service.get(id).status()+" / "+service.get(id).state().message);
    }
}
