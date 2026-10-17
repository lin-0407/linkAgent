package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.assistant.AssistantPlanModels.Prepare;
import com.link.linkagent.tool.Tool;
import jakarta.validation.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 模型只能提出固定计划；确认、补充输入和恢复执行均由用户界面独立提交。 */
@Component
public class ProposePlatformPlanTool implements Tool {
    private final ObjectProvider<AssistantPlanService> plans;
    private final ObjectMapper json;
    private final Validator validator;
    public ProposePlatformPlanTool(ObjectProvider<AssistantPlanService> plans,ObjectMapper json,Validator validator) { this.plans=plans; this.json=json; this.validator=validator; }
    public String getName() { return "propose_platform_plan"; }
    public boolean isAvailable() { var state=AssistantInvocationContext.current(); return state!=null && state.allowProposals(); }
    public String getDescription() { return "用户要求代操作时创建待确认计划，不执行业务。输入JSON：{\"taskId\":null,\"title\":\"具体目标\",\"steps\":[{\"id\":\"create\",\"operation\":\"creative.createInteractiveTask\",\"arguments\":{\"request\":{\"idea\":\"用户提供的创作想法至少十个字\"}},\"note\":\"将保存任务\"}]}。最多20步；后续参数可写{\"$ref\":\"create:/taskId\"}精确引用前序结果；待用户补充写{\"$input\":\"受众说明\"}，密钥再加secret:true，禁止提交明文密钥。操作及参数必须先查目录。文件步骤自动暂停供用户选择。用户确认整份具体计划后连续执行，参数变化需重新确认。"; }
    public String execute(String input) {
        try {
            Prepare request=json.readValue(input,Prepare.class);
            var failures=validator.validate(request);
            if(!failures.isEmpty()) throw new ConstraintViolationException(failures);
            var plan=plans.getObject().prepare(request);
            return json.writeValueAsString(java.util.Map.of("planId",plan.planId(),"title",plan.title(),"status",plan.status(),
                    "message","待确认计划已显示在助手面板；尚未执行。请用户检查具体参数、数据影响和模型费用，再点击确认整份计划"));
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("计划参数必须是合法JSON",error); }
    }
}
