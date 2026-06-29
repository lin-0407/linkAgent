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
 * 文档文本提取服务。
 * 基于 Apache Tika，从 PDF / DOCX / TXT / MD / PPTX 等常见格式中提取纯文本，
 * 用于创作者上传补充背景资料时自动解析文档内容。
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

    public DocumentExtractionService() {
        // Tika 实例线程安全且可复用，构造时初始化一次即可
        this.tika = new Tika();
    }

    /**
     * 从上传的文件中提取纯文本。
     * 先校验文件大小和扩展名，再通过 Tika 自动检测 MIME 类型并提取文本。
     *
     * @param file 用户上传的文档文件
     * @return 文件名 + 提取的纯文本（已截断到最大长度）
     * @throws ResponseStatusException 文件不符合要求或提取失败时抛出
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
     * 截断文本到最大长度，并在末尾添加截断提示。
     * 保留前面的内容而非后面的，因为文档开头通常是最重要的背景信息。
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
