package com.link.linkagent.creator.bilibili.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoSyncPayload;
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
import java.util.regex.Pattern;

/**
 * B站公开视频同步 Provider。
 * <p>
 * 本类只负责执行项目内固定脚本并解析结果，不承担数据库写入。这样外部网络调用不会占用数据库事务，
 * 同时延续项目已有的无 Cookie、用户显式触发采集边界。
 */
@Service
public class BilibiliVideoSyncProvider {

    private static final Logger log = LoggerFactory.getLogger(BilibiliVideoSyncProvider.class);

    /** 账号同步最多等待三分钟，超时后终止子进程，防止接口线程无限占用。 */
    private static final long SCRIPT_TIMEOUT_SECONDS = 180;
    /** 同步脚本最多输出 5MB；公开视频元数据远小于该值，超出通常意味着异常响应。 */
    private static final long SCRIPT_OUTPUT_MAX_SIZE = 5L * 1024 * 1024;
    /** 第一版同步最近 100 条视频，已绑定 BV 另行定向校验，不会因视频较旧而漏掉。 */
    private static final int MAX_RECENT_VIDEOS = 100;
    private static final String SYNC_SCRIPT_PATH = "scripts/bilibili_creator_videos_fetcher.py";
    private static final Pattern BVID_PATTERN = Pattern.compile("^BV[0-9A-Za-z]{10}$");

    private final ObjectMapper objectMapper;

    public BilibiliVideoSyncProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 拉取账号最近公开视频，并对当前用户所有任务 BV 做定向归属校验。
     *
     * @param bilibiliUid 已绑定 B站 UID
     * @param targetBvids 需要定向校验和刷新指标的 BV 列表
     * @return 脚本解析后的同步数据
     */
    public BilibiliVideoSyncPayload fetch(String bilibiliUid, List<String> targetBvids) {
        Path projectRoot = resolveProjectRoot();
        Path scriptPath = resolveRelativePath(projectRoot, SYNC_SCRIPT_PATH);
        List<String> command = buildCommand(scriptPath, bilibiliUid, targetBvids);

        Path outputFile = null;
        Path errorFile = null;
        try {
            // 输出到临时文件可以避免较大 JSON 填满进程管道后，让 waitFor 与脚本互相等待。
            outputFile = Files.createTempFile("linkagent-bilibili-sync-", ".json");
            errorFile = Files.createTempFile("linkagent-bilibili-sync-", ".log");

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(projectRoot.toFile());
            processBuilder.redirectOutput(outputFile.toFile());
            processBuilder.redirectError(errorFile.toFile());

            Process process = processBuilder.start();
            boolean finished = process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "B站公开视频同步超时");
            }
            if (process.exitValue() != 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "B站公开视频同步失败：" + readDiagnostic(errorFile, outputFile)
                );
            }

            BilibiliVideoSyncPayload payload = parsePayload(readOutput(outputFile));
            if (!bilibiliUid.equals(payload.bilibiliUid())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "同步脚本返回的B站UID与绑定账号不一致");
            }
            log.info("B站公开视频同步脚本完成：bilibiliUid={}, videoCount={}, verificationCount={}",
                    bilibiliUid, payload.videos().size(), payload.verificationResults().size());
            return payload;
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "执行B站公开视频同步脚本失败，请确认本机python命令可用"
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "B站公开视频同步被中断");
        } finally {
            deleteTemporaryFile(outputFile);
            deleteTemporaryFile(errorFile);
        }
    }

    private List<String> buildCommand(Path scriptPath, String bilibiliUid, List<String> targetBvids) {
        List<String> command = new ArrayList<>(List.of(
                "python",
                scriptPath.toString(),
                "--uid",
                bilibiliUid,
                "--max-videos",
                String.valueOf(MAX_RECENT_VIDEOS)
        ));
        if (targetBvids == null) {
            return command;
        }
        targetBvids.stream()
                .filter(value -> value != null && BVID_PATTERN.matcher(value).matches())
                .distinct()
                .forEach(bvid -> {
                    command.add("--target-bvid");
                    command.add(bvid);
                });
        return command;
    }

    private Path resolveProjectRoot() {
        for (Path candidate : collectProjectRootCandidates()) {
            Path cursor = candidate;
            while (cursor != null) {
                if (Files.isRegularFile(resolveRelativePath(cursor, SYNC_SCRIPT_PATH))) {
                    return cursor;
                }
                cursor = cursor.getParent();
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "没有找到项目内B站公开视频同步脚本");
    }

    private Set<Path> collectProjectRootCandidates() {
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(Path.of("").toAbsolutePath().normalize());
        try {
            Path codeLocation = Path.of(
                    BilibiliVideoSyncProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            );
            candidates.add(codeLocation.toAbsolutePath().normalize());
        } catch (Exception exception) {
            // classpath 位置只是 Docker/JAR 场景的兜底线索，失败时继续使用 user.dir 探测。
        }
        return candidates;
    }

    static Path resolveRelativePath(Path basePath, String relativePath) {
        return basePath.resolve(relativePath).normalize();
    }

    private String readOutput(Path outputFile) throws IOException {
        if (!Files.isRegularFile(outputFile) || Files.size(outputFile) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站公开视频同步脚本没有返回数据");
        }
        if (Files.size(outputFile) > SCRIPT_OUTPUT_MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站公开视频同步脚本返回数据超过5MB");
        }
        return Files.readString(outputFile, StandardCharsets.UTF_8);
    }

    BilibiliVideoSyncPayload parsePayload(String jsonText) {
        try {
            return objectMapper.readValue(jsonText, BilibiliVideoSyncPayload.class);
        } catch (JsonProcessingException exception) {
            log.warn("B站公开视频同步脚本产物解析失败", exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "B站公开视频同步脚本返回格式不正确");
        }
    }

    private String readDiagnostic(Path errorFile, Path outputFile) {
        String diagnostic = readSmallFile(errorFile);
        if (TextUtil.isBlank(diagnostic)) {
            diagnostic = readSmallFile(outputFile);
        }
        if (TextUtil.isBlank(diagnostic)) {
            return "脚本没有返回错误详情";
        }
        return TextUtil.abbreviate(TextUtil.collapseWhitespace(diagnostic), 500);
    }

    private String readSmallFile(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return null;
        }
    }

    private void deleteTemporaryFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.debug("删除B站同步临时文件失败：{}", path, exception);
        }
    }
}
