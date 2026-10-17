package com.link.linkagent.assistant;

import com.link.linkagent.tool.Tool;
import org.springframework.stereotype.Component;

/** 平台用法有独立工具入口，避免助手用通用产品经验猜测当前平台。 */
@Component
public class PlatformGuideTool implements Tool {
    private final PlatformAssistantContextService context;
    public PlatformGuideTool(PlatformAssistantContextService context) { this.context=context; }
    public String getName() { return "platform_guide"; }
    public String getDescription() { return "只读查询 LinkAgent 页面、创作流程、确认门禁和当前支持的操作；输入任意查询文字。"; }
    public String execute(String input) { return context.guide(); }
}
