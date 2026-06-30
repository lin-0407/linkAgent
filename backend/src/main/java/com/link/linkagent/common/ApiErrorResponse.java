package com.link.linkagent.common;

/**
 * 统一 API 错误响应 DTO —— 整个项目中所有异常的唯一对外数据结构。
 * <p>
 * 为什么使用 record 而非普通 class：
 * <ul>
 *   <li>错误响应是不可变数据（字段不需要修改），record 语义上更准确</li>
 *   <li>自动生成 equals/hashCode/toString，方便测试断言和日志打印</li>
 *   <li>Spring 的 Jackson 序列化原生支持 record（通过 Jackson 2.12+），无需额外配置</li>
 * </ul>
 * <p>
 * 由 {@link GlobalExceptionHandler} 统一构造，所有 Controller 层抛出的异常最终都映射为
 * 此结构的 JSON 响应。前端约定：始终读取 message 字段展示给用户，
 * 根据 status 字段判断是用户输入问题（4xx）还是服务端问题（5xx）以决定重试策略。
 * path 字段用于前端开发阶段快速定位出错的 API 端点。
 *
 * @param status  HTTP 状态码（如 400、404、500），前端据此决定交互策略
 * @param message 面向用户的中文错误描述，前端直接展示，不包含技术堆栈详情
 * @param path    出错的请求路径（如 /api/creator/tasks），辅助前端/后端联合排查
 */
public record ApiErrorResponse(
        int status,
        String message,
        String path
) {
}
