package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.tool.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 执行记录是助手判断成功的依据，不能用上一轮承诺代替真实结果。 */
@Component
public class PlatformRunContextTool implements Tool {
    private final ObjectProvider<AssistantPlanService> plans;
    private final ObjectMapper json;
    public PlatformRunContextTool(ObjectProvider<AssistantPlanService> plans,ObjectMapper json) { this.plans=plans; this.json=json; }
    public String getName() { return "platform_run_context"; }
    public boolean isAvailable() { return AssistantInvocationContext.current()!=null; }
    public String getDescription() { return "只读查询助手持久化计划与逐步真实结果。输入JSON：{\"planId\":\"\"}列出最近计划摘要，提供planId返回该计划的状态、失败、等待输入和实际结果。完成后先查记录再回答，不把已提交后台任务说成已经生成报告。"; }
    public String execute(String input) {
        try {
            String id=json.readTree(input).path("planId").asText();
            Object value=id.isBlank()?plans.getObject().recent().stream().map(v -> java.util.Map.of("planId",v.planId(),"title",v.title(),"status",v.status(),"message",v.state().message)).toList():plans.getObject().get(id);
            String result=json.writeValueAsString(value);
            return result.length()>30000?result.substring(0,30000)+"\n【记录已截断，请结合原业务页面核对】":result;
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("执行记录查询必须是JSON",error); }
    }
}
