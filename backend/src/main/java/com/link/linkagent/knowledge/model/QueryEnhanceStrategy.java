package com.link.linkagent.knowledge.model;

/**
 * 案例库检索的查询增强策略（5.2b）。
 * <p>
 * 用于在 dense 检索前对用户原始 query 做不同方式的扩展，统一收敛为「原始 query → 1~N 条检索文本」：
 * <ul>
 *   <li>{@link #NONE}：不增强，直接用原始 query（等价 5.2a 行为，作对照 / 显式关闭）；</li>
 *   <li>{@link #REWRITE}：LLM 改写规范化为 1 条更利于向量检索的查询（默认策略，单路最稳）；</li>
 *   <li>{@link #HYDE}：LLM 生成 1 段假设的优质案例描述，以「假设文档」的语义去检索；</li>
 *   <li>{@link #MULTI_QUERY}：LLM 扩成多条不同角度查询，多路召回后去重合并。</li>
 * </ul>
 * 仅作用于向量检索路径；SQL 兜底始终用原始 query（见 docs/develop/阶段5.2-高级检索链路 §11.1）。
 */
public enum QueryEnhanceStrategy {
    NONE,
    REWRITE,
    HYDE,
    MULTI_QUERY
}
