package com.link.linkagent.creator.creation.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 公开参考汇率客户端。
 * <p>
 * 业务路径只读缓存，避免在数据库事务里等待外部网络；缓存过期时异步刷新，
 * 首次启动尚未取到值时使用部署配置的折算率，并在设置页明确标为回退值。
 */
@Service
public class LiveBillingExchangeRate implements BillingExchangeRate {

    private static final Logger log = LoggerFactory.getLogger(LiveBillingExchangeRate.class);
    private static final BigDecimal MIN_RATE = new BigDecimal("2");
    private static final BigDecimal MAX_RATE = new BigDecimal("20");

    private final BillingExchangeRateProperties properties;
    private final CreationBillingProperties fallback;
    private final RestClient client;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Rate cached;

    public LiveBillingExchangeRate(BillingExchangeRateProperties properties, CreationBillingProperties fallback) {
        this.properties = properties;
        this.fallback = fallback;
        this.client = RestClient.builder().requestFactory(factory(properties)).build();
    }

    @Override
    public Rate current() {
        if (!properties.isEnabled()) return fallback();
        Rate snapshot = cached;
        if (snapshot == null) {
            triggerRefresh();
            return fallback();
        }
        if (expired(snapshot)) {
            triggerRefresh();
            return new Rate(snapshot.usdToCny(), snapshot.fetchedAt(), Source.CACHED);
        }
        return snapshot;
    }

    @Override
    public Rate refresh() {
        if (!properties.isEnabled()) return fallback();
        if (!refreshing.compareAndSet(false, true)) return lastKnown();
        try {
            JsonNode root = client.get().uri(properties.getUrl()).retrieve().body(JsonNode.class);
            BigDecimal rate = parse(root);
            Rate next = new Rate(rate, Instant.now(), Source.LIVE);
            cached = next;
            return next;
        } catch (RuntimeException exception) {
            log.warn("汇率获取失败，沿用上次成功值或配置回退。原因={}", exception.getClass().getSimpleName());
            return lastKnown();
        } finally {
            refreshing.set(false);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        triggerRefresh();
    }

    /** 兜底定时刷新；真正的新鲜度由 current() 发现过期后触发，配置的刷新间隔仍然生效。 */
    @Scheduled(fixedDelay = 30 * 60 * 1000L)
    public void scheduledRefresh() {
        refresh();
    }

    private void triggerRefresh() {
        if (!properties.isEnabled()) return;
        Rate snapshot = cached;
        if (snapshot != null && !expired(snapshot)) return;
        CompletableFuture.runAsync(this::refresh);
    }

    private boolean expired(Rate rate) {
        if (rate.fetchedAt() == null) return true;
        var interval = properties.getRefreshInterval() == null
                ? java.time.Duration.ofMinutes(30) : properties.getRefreshInterval();
        return Instant.now().isAfter(rate.fetchedAt().plus(interval));
    }

    private Rate lastKnown() {
        Rate snapshot = cached;
        return snapshot == null ? fallback()
                : new Rate(snapshot.usdToCny(), snapshot.fetchedAt(), Source.CACHED);
    }

    private Rate fallback() {
        return new Rate(fallback.getUsdToCny(), null, Source.CONFIG_FALLBACK);
    }

    private BigDecimal parse(JsonNode root) {
        if (root == null) throw new IllegalArgumentException("汇率响应为空");
        String result = root.path("result").asText("");
        if (!result.isBlank() && !"success".equalsIgnoreCase(result))
            throw new IllegalArgumentException("汇率接口返回失败");
        JsonNode rates = root.path("rates");
        if (!rates.path("CNY").isNumber()) rates = root.path("conversion_rates");
        JsonNode cny = rates.path("CNY");
        if (!cny.isNumber()) throw new IllegalArgumentException("汇率响应缺少 CNY");
        BigDecimal rate = cny.decimalValue();
        if (rate.compareTo(MIN_RATE) < 0 || rate.compareTo(MAX_RATE) > 0)
            throw new IllegalArgumentException("汇率超出合理范围");
        return rate;
    }

    private static SimpleClientHttpRequestFactory factory(BillingExchangeRateProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(millis(properties.getConnectTimeout()));
        factory.setReadTimeout(millis(properties.getReadTimeout()));
        return factory;
    }

    private static int millis(java.time.Duration duration) {
        long value = duration == null ? 2000 : duration.toMillis();
        return (int) Math.max(500, Math.min(30_000, value));
    }
}
