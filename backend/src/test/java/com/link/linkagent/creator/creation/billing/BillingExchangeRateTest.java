package com.link.linkagent.creator.creation.billing;

import com.link.linkagent.creator.creation.service.CreationBillingProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BillingExchangeRateTest {

    private HttpServer server;
    private AtomicReference<String> body;
    private AtomicInteger status;
    private BillingExchangeRateProperties properties;
    private CreationBillingProperties fallback;

    @BeforeEach
    void setUp() throws Exception {
        body = new AtomicReference<>("{\"result\":\"success\",\"rates\":{\"CNY\":6.684864}}");
        status = new AtomicInteger(200);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rate", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        properties = new BillingExchangeRateProperties();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/rate");
        properties.setRefreshInterval(Duration.ofMinutes(30));
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(2));
        fallback = new CreationBillingProperties();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void liveRateIsParsedAndKeptInCache() {
        var rates = new LiveBillingExchangeRate(properties, fallback);

        var rate = rates.refresh();

        assertThat(rate.source()).isEqualTo(BillingExchangeRate.Source.LIVE);
        assertThat(rate.usdToCny()).isEqualByComparingTo("6.684864");
        assertThat(rates.current().usdToCny()).isEqualByComparingTo("6.684864");
        assertThat(rates.current().fetchedAt()).isNotNull();
    }

    @Test
    void failedRefreshKeepsLastKnownValueAndMarksItCached() {
        var rates = new LiveBillingExchangeRate(properties, fallback);
        rates.refresh();

        status.set(503);
        var rate = rates.refresh();

        assertThat(rate.source()).isEqualTo(BillingExchangeRate.Source.CACHED);
        assertThat(rate.usdToCny()).isEqualByComparingTo("6.684864");
    }

    @Test
    void invalidOrUnreachableSourceFallsBackToConfiguredRate() {
        var rates = new LiveBillingExchangeRate(properties, fallback);

        status.set(500);
        var rate = rates.refresh();

        assertThat(rate.source()).isEqualTo(BillingExchangeRate.Source.CONFIG_FALLBACK);
        assertThat(rate.usdToCny()).isEqualByComparingTo(fallback.getUsdToCny());
        assertThat(rate.fetchedAt()).isNull();
    }

    @Test
    void missingCnyFieldIsRejectedInsteadOfGuessing() {
        body.set("{\"result\":\"success\",\"rates\":{\"JPY\":150}}");
        var rates = new LiveBillingExchangeRate(properties, fallback);

        assertThat(rates.refresh().source()).isEqualTo(BillingExchangeRate.Source.CONFIG_FALLBACK);
    }

    @Test
    void disabledSourceUsesConfiguredRateWithoutRequest() {
        properties.setEnabled(false);
        var rates = new LiveBillingExchangeRate(properties, fallback);

        var rate = rates.refresh();

        assertThat(rate.source()).isEqualTo(BillingExchangeRate.Source.CONFIG_FALLBACK);
        assertThat(rate.usdToCny()).isEqualByComparingTo(new BigDecimal("7.20"));
    }
}
