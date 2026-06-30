package com.link.linkagent.common;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Set;

/**
 * 文档文本提取服务 —— 在服务端完成"文件上传 → 纯文本"的转换。
 * <p>
 * 基于 Apache Tika 的自动检测引擎，从创作者上传的常见文档格式中提取纯文本，
 * 作为 LLM 对话的补充背景资料。提取后的文本会注入到 Agent 的上下文窗口中，
 * 因此需要在提取后做截断处理，避免 Prompt 过长导致 LLM Token 超限或成本失控。
 * <p>
 * 设计决策：
 * <ul>
 *   <li><b>三阶段校验链</b>：文件大小 → 扩展名白名单 → Tika 解析，逐层拦截不合规文件，避免无效解析消耗资源。</li>
 *   <li><b>扩展名白名单</b>：只接受 Tika 能可靠解析的文档格式，防止用户上传图片、视频等二进制让 Tika 返回乱码。</li>
 *   <li><b>文本截断</b>：保留文档前段而非尾段，因为文档开头通常是摘要/引言等最重要的背景信息。</li>
 *   <li><b>Tika 实例复用</b>：Tika 对象线程安全，构造时初始化一次并全局复用，避免每次提取都创建新实例。</li>
 * </ul>
 */
@Service
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);

    /** 单文件最大大小：10 MB，避免超大文件耗尽内存或占用 LLM 上下文窗口 */
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024L;

    /** 提取文本最大长度：50000 字符，超出部分截断，避免 Prompt 过长触发 LLM 限制 */
    private static final int MAX_EXTRACTED_CHARS = 50000;

    /** 允许上传的文件扩展名白名单，只接受 Tika 能可靠解析的常见文档格式 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".doc", ".pptx", ".ppt",
            ".txt", ".md", ".markdown",
            ".html", ".htm", ".rtf", ".odt", ".epub"
    );

    private final Tika tika;

    /**
     * 初始化 Tika 实例。Tika 对象是线程安全的，因此作为单例复用而非每次提取都创建新实例，
     * 避免重复加载 Parser 和 Detector 的内部配置（每次创建约 50-100ms 开销）。
     */
    public DocumentExtractionService() {
        this.tika = new Tika();
    }

    /**
     * 从上传文件中提取纯文本 —— 三层校验 + 自动解析 + 截断。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>文件名非空校验</li>
     *   <li>文件大小校验（≤10MB）：在读取字节前拦截超大文件，避免 OOM</li>
     *   <li>扩展名白名单校验：只接受 Tika 能可靠解析的文档格式</li>
     *   <li>Tika 自动识别 MIME 类型并解析为纯文本</li>
     *   <li>截断超过 50000 字符的文本</li>
     * </ol>
     * <p>
     * 异常策略：所有校验失败返回 400（客户端输入问题），
     * IO/Tika 内部错误返回 500（服务端能力问题）。
     *
     * @param file 用户上传的文档文件（由 Spring MultipartResolver 注入）
     * @return 文件名 + 提取的纯文本（已截断到最大长度，保证不超过 Token 预算）
     * @throws ResponseStatusException 校验失败（400）或提取失败（500）
     */
    public ExtractedDocument extract(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不能为空");
        }

        // 文件大小校验：在解析前拦截超大文件，避免 OOM
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "文件 \"" + originalFilename + "\" 超过最大限制 10 MB，请压缩或拆分后重试"
            );
        }

        // 扩展名白名单校验：防止用户上传图片、视频等无法解析的二进制文件
        String lowerFilename = originalFilename.toLowerCase();
        boolean extensionAllowed = ALLOWED_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith);
        if (!extensionAllowed) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "文件 \"" + originalFilename + "\" 格式不支持，请上传 PDF、DOCX、TXT、MD 等文档格式"
            );
        }

        try {
            // Tika 自动检测文件类型（MIME）并调用对应解析器提取纯文本
            String extracted = tika.parseToString(file.getInputStream());
            if (extracted == null || extracted.isBlank()) {
                log.warn("文档提取结果为空: fileName={}, size={}", originalFilename, file.getSize());
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "文件 \"" + originalFilename + "\" 无法提取到文本内容，可能为扫描件或纯图片 PDF"
                );
            }

            // 截断过长的文本，避免 LLM 上下文窗口溢出
            String truncated = truncate(extracted);
            log.info("文档提取成功: fileName={}, size={}, extracted={} chars",
                    originalFilename, file.getSize(), truncated.length());
            return new ExtractedDocument(originalFilename, truncated);

        } catch (IOException e) {
            log.error("文档读取失败: fileName={}", originalFilename, e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "文件 \"" + originalFilename + "\" 读取失败，请确认文件未损坏"
            );
        } catch (TikaException e) {
            log.error("文档解析失败: fileName={}", originalFilename, e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "文件 \"" + originalFilename + "\" 解析失败，Tika 无法识别该文档格式"
            );
        }
    }

    /**
     * 截断文本到最大长度，超出部分丢弃并在末尾附加提示。
     * <p>
     * 保留前 MAX_EXTRACTED_CHARS 个字符而非后段：文档开头通常是标题、摘要、引言等
     * 最重要的背景信息，尾部多为附录、参考文献等次要内容，优先保留前段对 LLM 更有价值。
     * 超过限制时附加截断提示，让创作者知道其原始文档未被完整读取。
     *
     * @param text 原始提取文本
     * @return 截断后的文本（不超过 MAX_EXTRACTED_CHARS + 提示长度）
     */
    private String truncate(String text) {
        if (text.length() <= MAX_EXTRACTED_CHARS) {
            return text;
        }
        return text.substring(0, MAX_EXTRACTED_CHARS) + "\n\n[文档过长，已截断，完整内容请参考原始文件]";
    }

    /**
     * 文档提取结果。
     *
     * @param fileName 原始文件名，用于前端展示
     * @param text     提取后的纯文本，已截断
     */
    public record ExtractedDocument(String fileName, String text) {
    }
}
