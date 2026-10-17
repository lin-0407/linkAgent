package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.assistant.AssistantModels.PrepareAction;
import com.link.linkagent.tool.Tool;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 延迟取得业务服务，避免工具注册与依赖 Agent 的业务生成之间形成 Bean 循环。 */
@Component
public class ProposeCreatorActionTool implements Tool {
    private final ObjectProvider<AssistantActionService> actions;
    private final ObjectMapper json;
    private final Validator validator;
    public ProposeCreatorActionTool(ObjectProvider<AssistantActionService> actions,ObjectMapper json,Validator validator) {
        this.actions=actions; this.json=json; this.validator=validator;
    }
    public String getName() { return "propose_creator_action"; }
    public boolean isAvailable() {
        var state=AssistantInvocationContext.current();
        return state!=null && state.allowProposals() && state.taskId()!=null && !state.taskId().isBlank();
    }
    public String getDescription() { return "仅在用户要求操作时创建待确认卡，不执行业务。输入JSON：{\"taskId\":\"当前任务ID\",\"actionType\":\"ANALYZE_FEEDBACK|GENERATE_REVIEW\",\"guidance\":\"用户本次要求\"}。用户点击确认后才执行。"; }
    public String execute(String input) {
        try {
            PrepareAction request=json.readValue(input,PrepareAction.class);
            if (!validator.validate(request).isEmpty()) throw new IllegalArgumentException("操作参数不完整或指导超过2000字");
            AssistantInvocationContext.requireTask(request.taskId(),true);
            return json.writeValueAsString(actions.getObject().prepare(request));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("操作参数必须是合法 JSON",error);
        }
    }
}
