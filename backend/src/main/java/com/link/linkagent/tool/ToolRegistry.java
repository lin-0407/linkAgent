package com.link.linkagent.tool;

import com.link.linkagent.tool.mcp.SpringAiToolCallbackAdapter;
import com.link.linkagent.util.TextUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心 —— 统一管理 Agent 可调用的所有工具的生命周期（注册、校验、查找和枚举）。
 * <p>
 * 在 Agent 架构中的位置：位于工具实现层（{@link Tool} 接口的各种实现，含本地工具和 MCP 适配工具）和
 * 工具执行层（{@link ToolExecutor}）之间。注册中心是工具生态的「单一入口」——Agent 通过它发现可用工具，
 * ToolExecutor 通过它按名称查找工具实例。
 * <p>
 * 核心设计决策：
 * <ul>
 *   <li><b>启动期 Fail-Fast 校验</b>：所有工具注册和校验（重名检测、空名检测）在 Spring Bean 的
 *       {@link PostConstruct} 阶段完成。这样工具生态的问题（如两个开发者无意中定义了同名工具）会在
 *       应用启动时立刻暴露，Agent 不会运行到一半才发现调用失败，避免了生产环境的隐蔽错误。</li>
 *   <li><b>双来源汇聚</b>：本地工具（实现 {@link Tool} 接口并标注 {@link Component} 的 Spring Bean）
 *       和 MCP 工具（通过 {@link ToolCallbackProvider} 从外部 MCP 服务器发现）在注册中心统一管理。
 *       {@link SpringAiToolCallbackAdapter} 作为适配层，将 Spring AI 的 ToolCallback 转换为项目内部的 Tool 接口，
 *       让两类工具共享同一套查找、排序和提示词生成逻辑。</li>
 *   <li><b>稳定排序</b>：工具按名称字母序排序后存入 {@link LinkedHashMap}，保证工具列表在系统提示词、
 *       日志和测试中的顺序完全一致。这对可复现性是关键——LLM 对提示词中的列表顺序敏感，
 *       稳定的排序能让同一输入在多次运行中产出更一致的决策。</li>
 *   <li><b>只读暴露</b>：{@link #getAllTools()} 返回 {@link Collections#unmodifiableCollection}，
 *       防止外部代码在运行时注入或移除工具，保持注册中心在启动后不可变。</li>
 * </ul>
 */
@Component
public class ToolRegistry {

    /** 本地 Spring Bean 工具列表（实现 Tool 接口并标注 @Component 的类，由 Spring 容器自动注入） */
    private final List<Tool> tools;
    /** MCP 工具回调提供者列表（为空时不影响本地工具注册，由 Spring 按需注入） */
    private final List<ToolCallbackProvider> toolCallbackProviders;
    /**
     * 工具名 → 工具实例的映射表。
     * 使用 LinkedHashMap 而非 HashMap：保留稳定的插入顺序，确保 getAllTools() 返回的工具顺序
     * 在多次启动间一致（插入即按名称排序，LinkedHashMap 保证迭代顺序等于插入顺序）。
     */
    private final Map<String, Tool> toolMap = new LinkedHashMap<>();

    /**
     * 无 MCP 场景的构造器（仅本地工具），供单测和简单部署场景使用。
     * <p>
     * 委托给 {@link #ToolRegistry(List, List)} 全参构造器，MCP 列表为空。
     *
     * @param tools 由 Spring 容器自动注入的本地工具列表
     */
    public ToolRegistry(List<Tool> tools) {
        this(tools, List.of());
    }

    /**
     * 全参构造器，由 Spring 容器通过构造器注入完成依赖组装。
     * <p>
     * 注意：构造阶段只保存引用，真正的注册和校验在 {@link #init()} 的 {@link PostConstruct} 阶段进行。
     * 这样设计是因为 Spring 可能在构造器执行时尚未完成所有依赖的注入
     * （尤其是 {@link ToolCallbackProvider} 的实现可能依赖其他 Bean），
     * 等到所有 Bean 就绪后再做校验才是最安全的时机。
     *
     * @param tools                 由 Spring 容器自动注入的本地工具列表（所有实现 Tool 接口的 Bean）
     * @param toolCallbackProviders 由 Spring 容器自动注入的 MCP 回调提供者列表（可能为空）
     */
    @Autowired
    public ToolRegistry(List<Tool> tools, List<ToolCallbackProvider> toolCallbackProviders) {
        this.tools = tools;
        this.toolCallbackProviders = toolCallbackProviders;
    }

    /**
     * 启动期初始化：汇聚本地工具和 MCP 工具，完成校验、排序和注册。
     * <p>
     * 执行流程：
     * <ol>
     *   <li>以本地工具列表为基础（先加入，不代表优先级——最终按名称排序）。</li>
     *   <li>遍历所有 ToolCallbackProvider，将其暴露的 ToolCallback 通过
     *       {@link SpringAiToolCallbackAdapter} 适配为 Tool 接口，追加到列表中。</li>
     *   <li>对全部工具按解析后的名称做稳定排序（字母序），保证系统提示词、日志、测试可复现。</li>
     *   <li>逐一注册到 {@link #toolMap}，遇到重名或空名立即抛出
     *       {@link IllegalStateException} 阻止应用启动。</li>
     * </ol>
     * <p>
     * Fail-Fast 策略：工具重名或名称为空是严重的配置错误——与其让 Agent 在运行到一半时
     * 调用错工具（两个同名工具的语义可能完全不同）或被 LLM 编造不存在的工具名，
     * 不如让应用启动直接失败，开发者能立刻定位问题。
     */
    @PostConstruct
    public void init() {
        // 第一步：汇聚本地工具 + MCP 适配工具到统一列表
        List<Tool> allTools = new ArrayList<>(tools);
        toolCallbackProviders.stream()
                // 每个 provider 可能暴露多个 ToolCallback（一个 MCP 服务器可能注册了多个工具）
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                // 适配层统一接口：MCP 工具和本地工具现在都是 Tool 类型，后续流程无需区分来源
                .map(SpringAiToolCallbackAdapter::new)
                .forEach(allTools::add);

        // 第二步：稳定排序 → 校验 → 注册
        allTools.stream()
                // 工具列表进入系统提示词，稳定排序能让日志、测试和问题复现更容易。
                // 为什么选择字母序而非注册顺序：字母序不依赖类路径扫描的迭代顺序（不同 JVM/OS 可能不同），
                // 是最简单可靠的确定性排序。
                .sorted(Comparator.comparing(tool -> resolveToolName(tool)))
                .forEach(tool -> {
                    String toolName = resolveToolName(tool);
                    // 重名检测：Fail-Fast —— 同名工具说明配置冲突，必须立即暴露
                    if (toolMap.containsKey(toolName)) {
                        throw new IllegalStateException("Duplicate tool name: " + toolName);
                    }
                    toolMap.put(toolName, tool);
                });
    }

    /**
     * 按名称查找工具实例。O(1) 查找，直接命中 HashMap。
     * <p>
     * 返回 null 而非抛出异常：工具不存在对 Agent 而言是常见场景——LLM 可能编造一个不存在的工具名
     * （Hallucination），此时 ToolExecutor 会将其转换为带错误描述的 Observation 喂回 LLM，
     * 让 LLM 看到反馈后自行修正。如果抛异常，会中断整个 ReAct 循环。
     *
     * @param name 工具名称（对应 LLM 决策中的 Action 字段）
     * @return 工具实例；找不到返回 null
     */
    public Tool getTool(String name) {
        return toolMap.get(name);
    }

    /**
     * 获取所有已注册工具（只读视图）。
     * <p>
     * 为什么返回不可变集合：注册中心在 {@link #init()} 后状态即确定，
     * 运行期不应再变更工具列表（动态注册/注销会让 Agent 行为不可预期）。
     * 返回不可变集合是防御性设计——即使有代码尝试 add/remove，也会在运行时立即暴露为异常。
     *
     * @return 所有工具实例的不可变只读集合（按名称字母序排列）
     */
    public Collection<Tool> getAllTools() {
        return Collections.unmodifiableCollection(toolMap.values());
    }

    /**
     * 解析工具的规范名称：获取原始名称 → 校验非空 → trim 首尾空格。
     * <p>
     * 为什么需要 trim：工具开发者可能在 name 的实现中意外返回带首尾空格的值
     * （如从配置文件读取时未 strip）。两个工具名 "weather" 和 " weather" 虽然有空格差异，
     * 但对 LLM 和人类而言指向同一个工具——trim 可以避免「伪重名」导致的误注册。
     * <p>
     * Fail-Fast：空名是明确的实现错误，直接抛异常让开发者修正。
     *
     * @param tool 工具实例
     * @return 规范化（trim 后）的工具名
     * @throws IllegalStateException 如果工具名为空或全空白
     */
    private String resolveToolName(Tool tool) {
        String toolName = tool.getName();
        // 空名检测：工具名是 LLM 调用工具的标识，为空意味着这工具永远无法被匹配到
        if (TextUtil.isBlank(toolName)) {
            throw new IllegalStateException("Tool name must not be blank: " + tool.getClass().getName());
        }
        // trim 去除首尾空白，防止「伪重名」——如 "weather" 和 " weather" 被当作不同工具
        return toolName.trim();
    }
}
