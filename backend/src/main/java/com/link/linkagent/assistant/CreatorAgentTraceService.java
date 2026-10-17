package com.link.linkagent.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.dto.AgentChatResponse;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.function.Supplier;

/** 业务取证轨迹独立于聊天记忆，刷新后也能查看每个计划步骤和 Worker 的实际结果。 */
@Service
public class CreatorAgentTraceService {
    private final AssistantMapper mapper;
    private final ObjectMapper json;
    public CreatorAgentTraceService(AssistantMapper mapper,ObjectMapper json) { this.mapper=mapper; this.json=json; }
    public AgentChatResponse capture(String taskId,String scene,String mode,Supplier<AgentChatResponse> work) {
        String id=UUID.randomUUID().toString();
        mapper.insertTrace(id,taskId,scene,mode);
        try (var ignored=AssistantInvocationContext.open(new AssistantInvocationContext.State(taskId,false))) {
            var response=work.get();
            if (!mode.equals(response.executionMode().name())) throw new IllegalStateException("实际执行模式与业务要求不一致");
            mapper.finishTrace(id,response.stopReason()==null ? "SUCCEEDED" : "FAILED",json.writeValueAsString(response),response.stopReason());
            if (response.stopReason()!=null) throw new IllegalStateException("业务 Agent 取证未完成："+response.stopReason());
            return response;
        } catch (JsonProcessingException error) {
            mapper.finishTrace(id,"FAILED",null,"执行轨迹序列化失败");
            throw new IllegalStateException("执行轨迹保存失败",error);
        } catch (RuntimeException error) {
            mapper.finishTrace(id,"FAILED",null,"业务 Agent 执行失败，请查看关联步骤");
            throw error;
        }
    }
}
