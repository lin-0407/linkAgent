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
 * 「输入 BV → 调离线脚本采集 → 自动清洗导入」一键服务（阶段 5.1b 前端 BV 路径的后端实现）。
 * <p>
 * 为什么不在后端用 Java 直连 B 站、而是 ProcessBuilder 调脚本：
 * - 项目约束禁止后端内置爬虫 / 后台采集，只允许「用户显式触发的单 BV 限量采集」；
 * - 让 {@code scripts/bilibili_reference_fetcher.py} 当唯一抓取内核，榜单（cron）与单 BV（本服务）共用同一份采集逻辑，
 *   既守住合规边界，又能在前端一键完成。
 * 本服务只负责「跑脚本 + 读产物 + 转交」，清洗 / 去重 / 落库仍复用 {@link KnowledgeReferenceVideoService}，保持单一职责。
 * 实现刻意对齐反馈侧 CreatorFeedbackService 的脚本调用套路（同款超时、项目根探测、错误处理），降低理解成本。
 */
@Service
public class KnowledgeReferenceFetchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReferenceFetchService.class);

    /** 脚本总超时：B 站接口卡住时及时放手，避免长期占用后端工作线程。 */
    private static final long SCRIPT_TIMEOUT_SECONDS = 180;

    /** 脚本产物大小上限，防止异常超大文件读爆内存。 */
    private static final long GENERATED_FILE_MAX_SIZE = 20L * 1024 * 1024;

    /** 单 BV 采集的评论 / 弹幕条数上限，限量采集、对平台礼貌。 */
    private static final int FETCH_MAX_COMMENTS = 50;
    private static final int FETCH_MAX_DANMAKU = 300;

    private static final Pattern BVID_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Path REFERENCE_SCRIPT_PATH = Path.of("scripts", "bilibili_reference_fetcher.py");
    private static final Path REFERENCE_EXPORT_PATH = Path.of("export", "bilibili_reference");

    private final KnowledgeReferenceVideoService knowledgeReferenceVideoService;
    private final ObjectMapper objectMapper;

    public KnowledgeReferenceFetchService(KnowledgeReferenceVideoService knowledgeReferenceVideoService,
                                          ObjectMapper objectMapper) {
        this.knowledgeReferenceVideoService = knowledgeReferenceVideoService;
        this.objectMapper = objectMapper;
    }

    /**
     * 采集单个 BV 并导入案例库：调脚本 bv 模式生成导入 JSON，读出后转交给现有导入链路。
     * 脚本运行不放进数据库事务（外部进程、耗时长），真正的落库事务在 importReferenceVideos 内部。
     */
    public ReferenceVideoImportResponse fetchAndImport(ReferenceVideoFetchImportRequest request) {
        String bvid = extractBvid(request.bvInput());
        Path projectRoot = resolveProjectRoot();
        Path scriptPath = projectRoot.resolve(REFERENCE_SCRIPT_PATH).normalize();
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
     * 向上逐级探测，找到包含采集脚本的项目根；兼容从不同工作目录或打包后启动的情况。
     */
    private Path resolveProjectRoot() {
        for (Path candidate : collectProjectRootCandidates()) {
            Path cursor = candidate;
            while (cursor != null) {
                if (Files.isRegularFile(cursor.resolve(REFERENCE_SCRIPT_PATH))) {
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
        Path outputDir = projectRoot.resolve(REFERENCE_EXPORT_PATH).normalize();
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
