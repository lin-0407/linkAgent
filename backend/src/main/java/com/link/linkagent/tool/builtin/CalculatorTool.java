package com.link.linkagent.tool.builtin;

import com.link.linkagent.tool.Tool;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

/**
 * 数学表达式求值，基于 Spring SpEL。
 */
@Component
public class CalculatorTool implements Tool {

    private final SpelExpressionParser parser = new SpelExpressionParser();

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "Evaluate a mathematical expression. Input: expression (e.g. 2 + 3 * 4).";
    }

    @Override
    public String execute(String input) {
        try {
            var expression = parser.parseExpression(input);
            var result = expression.getValue();
            return result != null ? result.toString() : "null";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
