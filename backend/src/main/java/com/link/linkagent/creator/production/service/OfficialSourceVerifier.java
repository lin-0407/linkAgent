package com.link.linkagent.creator.production.service;

import com.link.linkagent.creator.production.model.ToolCatalogRecord;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/** 校验工具资料链接是否仍然属于目录记录中的官方域名。 */
@Component
public class OfficialSourceVerifier {

    public boolean matches(String rawUrl, ToolCatalogRecord catalog) {
        if (rawUrl == null || rawUrl.isBlank() || catalog == null || catalog.officialDomain() == null) {
            return false;
        }
        try {
            URI uri = URI.create(rawUrl);
            String host = uri.getHost();
            String expected = catalog.officialDomain().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase(expected) || host.toLowerCase(Locale.ROOT).endsWith("." + expected));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
