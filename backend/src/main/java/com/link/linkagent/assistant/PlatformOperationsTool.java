package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.tool.Tool;
import org.springframework.stereotype.Component;
import java.util.Map;

/** 常驻入口只返回简短目录，选中具体操作后再读取参数，避免把全平台参数塞入每轮提示词。 */
@Component
public class PlatformOperationsTool implements Tool {
    private final PlatformOperationCatalog catalog;
    private final ObjectMapper json;
    public PlatformOperationsTool(PlatformOperationCatalog catalog,ObjectMapper json) { this.catalog=catalog; this.json=json; }
    public String getName() { return "platform_operations"; }
    public boolean isAvailable() { return AssistantInvocationContext.current()!=null; }
    public String getDescription() { return "发现当前部署的真实平台操作。输入JSON：{\"query\":\"\"}返回全部简短目录；query填操作ID、中文分组或关键词返回最多8项详细参数。先查目录，不能凭记忆声称平台没有工具。"; }
    public String execute(String input) {
        try {
            String query=json.readTree(input).path("query").asText();
            return json.writeValueAsString(query.isBlank()?Map.of("operations",catalog.index(),"count",catalog.index().size()):catalog.discover(query));
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("目录查询参数必须是JSON",error); }
    }
}
