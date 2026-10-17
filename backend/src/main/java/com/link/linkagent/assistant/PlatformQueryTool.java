package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.tool.Tool;
import org.springframework.stereotype.Component;

/** 查询入口再次检查操作性质，即使模型绕过发现步骤也不能通过查询执行写入。 */
@Component
public class PlatformQueryTool implements Tool {
    private final PlatformOperationCatalog catalog;
    private final ObjectMapper json;
    public PlatformQueryTool(PlatformOperationCatalog catalog,ObjectMapper json) { this.catalog=catalog; this.json=json; }
    public String getName() { return "platform_query"; }
    public boolean isAvailable() { return AssistantInvocationContext.current()!=null; }
    public String getDescription() { return "执行操作目录中access=READ的查询。输入JSON：{\"operation\":\"tasks.listTasks\",\"arguments\":{\"limit\":20}}。参数名称及嵌套结构以platform_operations返回为准；任何修改都必须用propose_platform_plan。"; }
    public String execute(String input) {
        try {
            var request=json.readTree(input);
            String result=json.writeValueAsString(catalog.query(request.path("operation").asText(),request.path("arguments")));
            return result.length()>24000?result.substring(0,24000)+"\n【结果已截断，请缩小查询范围；不要把截断内容当完整JSON】":result;
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("查询参数必须是JSON",error); }
    }
}
