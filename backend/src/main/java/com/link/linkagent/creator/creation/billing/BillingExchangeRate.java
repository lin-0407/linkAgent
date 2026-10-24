package com.link.linkagent.creator.creation.billing;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 记账用的美元兑人民币折算率。
 * <p>
 * 新调用使用当前汇率并写入价格快照；已有预留和结算只读各自快照，不能被后续汇率变化改写。
 * 实时来源不可用时必须明确回退，不能悄悄按固定值继续。
 */
public interface BillingExchangeRate {

    Rate current();

    /** 强制同步刷新；设置面板的“刷新汇率”使用，业务路径不调用。 */
    Rate refresh();

    record Rate(BigDecimal usdToCny, Instant fetchedAt, Source source) {
    }

    enum Source {
        /** 本次成功从公开接口获取。 */
        LIVE,
        /** 使用上次成功获取但已超过刷新间隔的值。 */
        CACHED,
        /** 从未成功获取或已关闭实时汇率，使用部署配置值。 */
        CONFIG_FALLBACK
    }

    static BillingExchangeRate fixed(BigDecimal rate) {
        Rate fixed = new Rate(rate, null, Source.CONFIG_FALLBACK);
        return new BillingExchangeRate() {
            @Override public Rate current() { return fixed; }
            @Override public Rate refresh() { return fixed; }
        };
    }
}
