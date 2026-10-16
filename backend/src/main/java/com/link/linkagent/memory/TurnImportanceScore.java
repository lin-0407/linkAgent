package com.link.linkagent.memory;

/**
 * 单轮对话重要性打分的模型输出契约（设计文档 D17.2）。
 *
 * @param importance 1-100 的整数分：80 以上表示「丢了会明显影响后续协作」的稳定偏好、身份或项目约束；
 *                   闲聊寒暄 1-10；把握不准 40
 * @param reason     一句话说明打分理由，只进日志，不入库
 */
public record TurnImportanceScore(Integer importance, String reason) {
}
