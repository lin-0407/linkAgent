package com.link.linkagent.llm;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 token 计量器 —— 用 DeepSeek 官方 tokenizer.json 在本地算 token 数。
 *
 * <h3>为什么需要它</h3>
 * 记忆压缩阈值（256k）和请求上限（768k）都必须按模型真正消耗的量来算。字符数和 token 数不成比例：
 * 中文实测约 0.55 token/字符，代码和 JSON 约 0.25~0.33 token/字符，用字符数会量出一个含义不同的阈值。
 *
 * <h3>为什么用官方词表</h3>
 * Spring AI classpath 上已有 JTokkit 的 CL100K_BASE 估算器，但它对中文高估 28%~52%
 * （记忆系统重构设计文档 9.3 第七节实测），量出来的阈值含义整体失真。官方词表随 jar 发布，
 * 不走网络，也不产生调用费用。
 *
 * <h3>三个必须显式设置的选项</h3>
 * DJL 默认开启 longest_first 截断，maxLength 缺省取 512——不关掉的话，一段 4000 token 的文本会被量成 512，
 * 压缩阈值永远触发不了。这里显式设置 truncation=do_not_truncate / padding=do_not_pad / addSpecialTokens=false，
 * 计量口径与 Python 侧 count_text()（add_special_tokens=False）逐 token 一致。
 *
 * <h3>原生库与兜底</h3>
 * tokenizers 依赖把各平台原生库打在 jar 内，运行时解压到缓存目录，不需要联网。
 * 计量在每次模型调用前都会执行，属于主链路：如果原生库在当前平台加载失败（例如非 x86_64 架构），
 * 直接抛异常会让整个工作台不可用，所以这里降级为按字符估算并打一次 ERROR 日志——
 * 阈值会有偏差，但对话、发布前优化等业务链路不会因为计量组件不可用而中断。
 */
@Component
public class TokenCounter {

    private static final Logger log = LoggerFactory.getLogger(TokenCounter.class);

    /** 官方词表随 jar 一起发布，避免运行时依赖外网下载。 */
    private static final String TOKENIZER_RESOURCE = "/tokenizer/tokenizer.json";

    /**
     * 加载选项。三个值都必须显式给出，缺省值会截断或补齐，计量结果不可用：
     * truncation/padding 关掉是防截断，addSpecialTokens=false 是与 Python 侧计量口径对齐。
     */
    private static final Map<String, String> TOKENIZER_OPTIONS = Map.of(
            "truncation", "do_not_truncate",
            "padding", "do_not_pad",
            "addSpecialTokens", "false"
    );

    /**
     * 兜底估算的每字符 token 数。中文实测 0.552、代码约 0.25，这里取偏高值：
     * 估算偏大只会让压缩更早触发，偏小则可能把窗口顶穿。
     */
    private static final double FALLBACK_TOKENS_PER_CHAR = 0.6;

    private final Object initLock = new Object();
    private final AtomicBoolean fallbackLogged = new AtomicBoolean();

    private volatile HuggingFaceTokenizer tokenizer;
    private volatile boolean initialized;

    /**
     * 计算一段文本的 token 数。空文本返回 0。
     *
     * @param text 待计量文本，允许为 null
     * @return 该文本在 DeepSeek 模型侧的 token 数（不含对话模板开销）
     */
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        HuggingFaceTokenizer current = tokenizer();
        if (current == null) {
            return fallbackCount(text);
        }
        try {
            // DJL 未声明 HuggingFaceTokenizer 是否线程安全，这里按最保守的方式串行调用；
            // 单用户工作台量级下计量开销远小于一次模型调用，串行不会成为瓶颈。
            synchronized (current) {
                return current.encode(text, false, false).getIds().length;
            }
        } catch (Throwable error) {
            // 必须兜住 Throwable：DJL 的原生库加载失败抛的是 NoClassDefFoundError / UnsatisfiedLinkError
            // 这类 Error，只 catch Exception 会让它们直接击穿到调用方（历史上表现为流式接口静默挂住）。
            logFallbackOnce(error);
            return fallbackCount(text);
        }
    }

    /** 懒加载词表：只有真正需要计量时才付出解压原生库和解析 6MB 词表的启动成本。 */
    private HuggingFaceTokenizer tokenizer() {
        if (initialized) {
            return tokenizer;
        }
        synchronized (initLock) {
            if (!initialized) {
                tokenizer = loadTokenizer();
                initialized = true;
            }
        }
        return tokenizer;
    }

    private HuggingFaceTokenizer loadTokenizer() {
        try (InputStream stream = TokenCounter.class.getResourceAsStream(TOKENIZER_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("classpath 缺少官方词表 " + TOKENIZER_RESOURCE);
            }
            HuggingFaceTokenizer loaded = HuggingFaceTokenizer.newInstance(stream, TOKENIZER_OPTIONS);
            log.info("DeepSeek 官方词表加载完成，本地 token 计量可用");
            return loaded;
        } catch (Throwable error) {
            // 同上：类初始化失败（ExceptionInInitializerError）也是 Error，必须一起兜住。
            logFallbackOnce(error);
            return null;
        }
    }

    private int fallbackCount(String text) {
        return (int) Math.ceil(text.length() * FALLBACK_TOKENS_PER_CHAR);
    }

    /** 兜底只提示一次，避免每次模型调用都刷同一条日志。 */
    private void logFallbackOnce(Throwable error) {
        if (fallbackLogged.compareAndSet(false, true)) {
            log.error("DeepSeek 官方词表不可用（{}），token 计量已降级为按字符估算（阈值会有偏差）：{}",
                    error.getClass().getName(), error.getMessage());
        }
    }
}
