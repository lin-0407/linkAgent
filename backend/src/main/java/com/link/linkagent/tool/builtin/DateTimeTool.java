package com.link.linkagent.tool.builtin;

import com.link.linkagent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 获取当前日期时间，纯 JDK 无外部依赖。
 */
@Component
public class DateTimeTool implements Tool {

    @Override
    public String getName() {
        return "datetime";
    }

    @Override
    public String getDescription() {
        return "Get the current date and time. Input: timezone (e.g. Asia/Shanghai) or 'now' for system default.";
    }

    @Override
    public String execute(String input) {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
