package com.link.linkagent.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.knowledge.model.ReferenceVideoFetchImportRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoImportRequest;
import com.link.linkagent.knowledge.model.ReferenceVideoImportResponse;
import com.link.linkagent.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「输入 BV → 调离线脚本采集 → 自动清洗导入」一键服务 — Pipeline 的入口触发器。
 * <p>
 * <b>Pipeline 角色</b>：
 * 这是案例库导入链路的<b>前端 BV 路径入口</b>——用户在页面上输入一个 BV 号，后端调 Python 脚本完成
 * 「采集 → 生成导入 JSON → 清洗 → 落库 → 生成中块 → 质量打分」全流程。
 * 本服务只负责「跑脚本 + 读产物 + 转交」，清洗 / 去重 / 落库仍复用 {@link KnowledgeReferenceVideoService}，保持单一职责。
 * <p>
 * <b>为什么不在后端用 Java 直连 B 站、而是 ProcessBuilder 调脚本</b>：
 * <ol>
 *   <li>项目约束禁止后端内置爬虫 / 后台采集，只允许「用户显式触发的单 BV 限量采集」</li>
 *   <li>让 {@code scripts/bilibili_reference_fetcher.py} 当唯一抓取内核，榜单（cron）与单 BV（本服务）共用同一份采集逻辑，
 *       既守住合规边界，又能在前端一键完成</li>
 *   <li>Python 生态有成熟的 B 站 API 库（bilibili-api），用脚本比 Java 重新实现更高效、更易维护</li>
 * </ol>
 * <p>
 * 实现刻意对齐反馈侧 CreatorFeedbackService 的脚本调用套路（同款超时、项目根探测、错误处理），降低理解成本。
 */
@Service
public class KnowledgeReferenceFetchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceFetchService.class);

    /**
     * 脚本总超时（秒）。180 秒 = 3 分钟：B 站单 BV API 通常 5~15 秒完成，3 分钟已是 generous 的上限。
     * 超时后 destroyForcibly 强杀进程，避免长期占用后端工作线程。
     */
    private static final long SCRIPT_TIMEOUT_SECONDS = 180;

    /** 脚本产物大小上限（20MB），防止异常超大 JSON（如脚本出错输出日志而非 JSON）读爆内存。 */
    private static final long GENERATED_FILE_MAX_SIZE = 20L * 1024 * 1024;

    /**
     * 单 BV 采集的评论 / 弹幕条数上限。
     * 限量采集是对平台礼貌：50 条评论 + 300 条弹幕对案例分析足够，
     * 更多数据应走离线脚本批量采集路径而非实时 API。
     */
    private static final int FETCH_MAX_COMMENTS = 50;
    private static final int FETCH_MAX_DANMAKU = 300;

    /** BV 号正则：BV 开头 + 10 位字母数字 */
    private static final Pattern BVID_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");
    /** 采集脚本相对路径 */
    private static final String REFERENCE_SCRIPT_PATH = "scripts/bilibili_reference_fetcher.py";
    /** 脚本产物输出目录 */
    private static final String REFERENCE_EXPORT_PATH = "export/bilibili_reference";

    private final KnowledgeReferenceVideoService knowledgeReferenceVideoService;
    private final ObjectMapper objectMapper;

    public KnowledgeReferenceFetchService(KnowledgeReferenceVideoService knowledgeReferenceVideoService,
                                          ObjectMapper objectMapper) {
        this.knowledgeReferenceVideoService = knowledgeReferenceVideoService;
        this.objectMapper = objectMapper;
    }

    /**
     * 采集单个 BV 并导入案例库：调脚本 bv 模式生成导入 JSON，读出后转交给现有导入链路。
     * <p>
     * <b>执行流程</b>：提取 BV → 定位项目根和脚本 → 跑 Python 脚本（3 分钟超时）→ 读产物 JSON → 解析 → 转交导入。
     * 脚本运行不放进数据库事务（外部进程、耗时长，事务跨进程无意义），真正的落库事务在
     * {@link KnowledgeReferenceVideoService#importReferenceVideos} 内部管理。
     *
     * @param request 采集导入请求，含 bvInput（可能是纯 BV 号或含 BV 的 URL）和可选的 tier/category
     * @return 导入结果，含导入/跳过计数
     */
    public ReferenceVideoImportResponse fetchAndImport(ReferenceVideoFetchImportRequest request) {
        String bvid = extractBvid(request.bvInput());
        Path projectRoot = resolveProjectRoot();
        Path scriptPath = resolveRelativePath(projectRoot, REFERENCE_SCRIPT_PATH);
        Path outputDir = resolveOutputDir(projectRoot);

        runReferenceScript(projectRoot, scriptPath, outputDir, bvid, request);

        Path jsonPath = outputDir.resolve(bvid + "_reference.json").normalize();
        String jsonText = readGeneratedText(jsonPath);
        ReferenceVideoImportRequest importRequest = parseImportRequest(jsonText);
        return knowledgeReferenceVideoService.importReferenceVideos(importRequest);
    }

    private String extractBvid(String value) {
        if (TextUtil.isBlank(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有识别到有效 BV 号");
        }
        Matcher matcher = BVID_PATTERN.matcher(value);
        if (!matcher.find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有识别到有效 BV 号");
        }
        return matcher.group();
    }

    /**
     * 向上逐级探测，找到包含采集脚本的项目根目录。
     * <p>
     * <b>探测策略</b>：
     * 从两个候选起点（user.dir 和 classpath 位置）分别向上遍历，直到找到包含
     * {@code scripts/bilibili_reference_fetcher.py} 的目录。
     * 这样做兼容从不同工作目录启动（IDE、jar 包、Docker）的情况——Python 脚本不在 classpath 内，
     * 必须通过文件系统路径找到。
     *
     * @return 包含采集脚本的项目根目录
     * @throws ResponseStatusException 如果任何路径都找不到采集脚本
     */
    private Path resolveProjectRoot() {
        for (Path candidate : collectProjectRootCandidates()) {
            Path cursor = candidate;
            while (cursor != null) {
                if (Files.isRegularFile(resolveRelativePath(cursor, REFERENCE_SCRIPT_PATH))) {
                    return cursor;
                }
                cursor = cursor.getParent();
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "没有找到项目内案例库采集脚本");
    }

    private Set<Path> collectProjectRootCandidates() {
        Set<Path> candidates = new LinkedHashSet<>();
        addCandidate(candidates, Path.of("").toAbsolutePath());
        try {
            Path codeLocation = Path.of(
                    KnowledgeReferenceFetchService.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            );
            addCandidate(candidates, codeLocation);
        } catch (Exception exception) {
            // classpath 位置只是兜底线索，失败时继续用 user.dir，不让诊断路径异常影响业务请求。
        }
        return candidates;
    }

    private void addCandidate(Set<Path> candidates, Path candidate) {
        if (candidate == null) {
            return;
        }
        candidates.add(candidate.toAbsolutePath().normalize());
    }

    private Path resolveOutputDir(Path projectRoot) {
        Path outputDir = resolveRelativePath(projectRoot, REFERENCE_EXPORT_PATH);
        if (!outputDir.startsWith(projectRoot)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "案例库采集输出目录不在项目根目录下");
        }
        try {
            // 固定写入项目根 export 目录，避免请求参数影响服务端写入位置。
            Files.createDirectories(outputDir);
            return outputDir;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "创建案例库采集输出目录失败");
        }
    }

    /**
     * 在基准路径所属的文件系统内解析相对路径。
     *
     * Spring Boot 可把类加载位置暴露为 JAR 文件系统路径，而旧实现会把默认文件系统的 Path 传入。
     * 直接调用 {@code resolve(Path)} 会要求两者 Provider 相同；固定相对路径字符串由基准路径自行解析，
     * 才能让项目根探测在找不到脚本时继续安全遍历并返回明确业务错误。
     */
    static Path resolveRelativePath(Path basePath, String relativePath) {
        return basePath.resolve(relativePath).normalize();
    }

    /**
     * 执行 Python 采集脚本。
     * <p>
     * <b>命令行构造</b>：使用 {@code python scripts/bilibili_reference_fetcher.py bv <BV号>}
     * 子命令模式，传递 max-comments/max-danmaku/source 等参数，tier/category 按需追加。
     * 工作目录设为项目根，方便脚本内部引用相对路径资源。
     * <p>
     * <b>错误处理</b>：超时强杀、非 0 退出取 stderr、IOException 提示 python 命令不可用、
     * InterruptedException 恢复中断标志后返回 503。
     */
    private void runReferenceScript(Path projectRoot,
                                    Path scriptPath,
                                    Path outputDir,
                                    String bvid,
                                    ReferenceVideoFetchImportRequest request) {
        if (!Files.isRegularFile(scriptPath)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "案例库采集脚本不存在");
        }
        // bv 子命令固定来源 manual_bv；tier / category 可选，用可变列表按需追加。
        List<String> command = new ArrayList<>(List.of(
                "python",
                scriptPath.toString(),
                "bv",
                bvid,
                "--output-dir",
                outputDir.toString(),
                "--source",
                "manual_bv",
                "--max-comments",
                String.valueOf(FETCH_MAX_COMMENTS),
                "--max-danmaku",
                String.valueOf(FETCH_MAX_DANMAKU)
        ));
        String tier = TextUtil.trimToNull(request.tier());
        if (tier != null) {
            command.add("--tier");
            command.add(tier);
        }
        String category = TextUtil.trimToNull(request.category());
        if (category != null) {
            command.add("--category");
            command.add(category);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectRoot.toFile());
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "案例库采集脚本执行超时");
            }
            String scriptOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "案例库采集脚本执行失败：" + normalizeScriptOutput(scriptOutput)
                );
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "执行 Python 脚本失败，请确认本机 python 命令可用");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "案例库采集脚本被中断");
        }
    }

    private String readGeneratedText(Path jsonPath) {
        if (!Files.isRegularFile(jsonPath)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "脚本没有生成可导入的 JSON 文件");
        }
        try {
            if (Files.size(jsonPath) > GENERATED_FILE_MAX_SIZE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "脚本生成的 JSON 文件不能超过20MB");
            }
            String text = Files.readString(jsonPath, StandardCharsets.UTF_8);
            if (TextUtil.isBlank(text)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "脚本生成的 JSON 文件内容为空");
            }
            return text;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取脚本生成的 JSON 文件失败");
        }
    }

    private ReferenceVideoImportRequest parseImportRequest(String jsonText) {
        try {
            return objectMapper.readValue(jsonText, ReferenceVideoImportRequest.class);
        } catch (JsonProcessingException exception) {
            log.warn("案例库采集脚本产物解析失败", exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "脚本生成的 JSON 结构无法解析为导入请求");
        }
    }

    private String normalizeScriptOutput(String scriptOutput) {
        if (TextUtil.isBlank(scriptOutput)) {
            return "脚本没有返回错误详情";
        }
        String normalized = scriptOutput.replaceAll("\\s+", " ").trim();
        return TextUtil.abbreviateWithSuffix(normalized, 500, "...");
    }
}
