package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.tool.Tool;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Component;

/** 只开放确定的业务查询范围，工具不得透传 SQL 或表名。 */
@Component
public class CreatorTaskContextTool implements Tool {
    private final PlatformAssistantContextService context;
    private final ObjectMapper json;
    private final Validator validator;
    public CreatorTaskContextTool(PlatformAssistantContextService context,ObjectMapper json,Validator validator) {
        this.context=context; this.json=json; this.validator=validator;
    }
    public record Query(@NotBlank @Size(max=64) String taskId,
                        @NotBlank @Pattern(regexp="summary|materials|feedback|review") String section) { }
    public String getName() { return "creator_task_context"; }
    public boolean isAvailable() {
        var state=AssistantInvocationContext.current();
        return state!=null && state.taskId()!=null && !state.taskId().isBlank();
    }
    public String getDescription() { return "只读查询当前任务状态、门禁或详细资料。输入JSON：{\"taskId\":\"当前任务ID\",\"section\":\"summary|materials|feedback|review\"}。"; }
    public String execute(String input) {
        try {
            Query query=json.readValue(input,Query.class);
            if (!validator.validate(query).isEmpty()) throw new IllegalArgumentException("查询参数不完整或范围无效");
            AssistantInvocationContext.requireTask(query.taskId(),false);
            return context.write(context.details(query.taskId(),query.section()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("查询参数必须是合法 JSON",error);
        }
    }
}
