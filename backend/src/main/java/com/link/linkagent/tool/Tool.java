package com.link.linkagent.tool;

/**
 * Agent 可调用的工具契约。
 * <p>
 * 实现类标注 @Component 即可被 ToolRegistry 自动发现。
 */
public interface Tool {

    String getName();

    String getDescription();

    String execute(String input);
}
