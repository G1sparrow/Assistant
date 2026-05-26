package com.assistant.tool;

import com.assistant.service.RagService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 桌面助手 Tool 集合
 * 这些工具会被 LangChain4j 自动注入到 AI 模型中使用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantTools {

    private final RagService ragService;

    /**
     * 获取当前日期和时间
     */
    @Tool("获取当前的日期和时间")
    public String getCurrentDateTime() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String result = now.format(formatter);
        log.debug("Tool: getCurrentDateTime -> {}", result);
        return result;
    }

    /**
     * 计算器工具
     */
    @Tool("执行数学计算，传入数学表达式如 '2 + 3 * 4'，返回计算结果")
    public String calculate(@P("数学表达式，例如 '2 + 3 * 4'") String expression) {
        log.debug("Tool: calculate -> {}", expression);
        try {
            double result = evaluate(expression);
            if (result == (long) result) {
                return String.valueOf((long) result);
            }
            return String.format("%.2f", result);
        } catch (Exception e) {
            log.warn("计算失败: {}", e.getMessage());
            return "计算错误: " + e.getMessage();
        }
    }

    /**
     * RAG 知识库检索工具
     */
    @Tool("搜索本地知识库文档中的内容。仅当用户明确询问本地文档/知识库中的信息时才使用，通用知识问题请直接回答")
    public String searchKnowledgeBase(@P("搜索关键词") String query) {
        log.debug("Tool: searchKnowledgeBase -> {}", query);
        return ragService.search(query);
    }

    /**
     * 文本处理工具 - 统计字数
     */
    @Tool("统计一段文本的字数和字符数")
    public String countTextStats(@P("要统计的文本") String text) {
        log.debug("Tool: countTextStats");
        String[] words = text.split("[\\s\\p{Punct}，。！？、；：\"\"''（）【】《》]+");
        int wordCount = 0;
        for (String w : words) {
            if (!w.isEmpty()) wordCount++;
        }
        return String.format("字数: %d, 字符数(含空格): %d, 字符数(不含空格): %d",
                wordCount,
                text.length(),
                text.replace(" ", "").length());
    }

    /**
     * 递归下降表达式求值 — 支持 + - * / ( ) 和数字
     */
    private static class Parser {
        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input.replace(" ", "");
            this.pos = 0;
        }

        double parse() {
            double result = parseTerm();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '+') { pos++; result += parseTerm(); }
                else if (c == '-') { pos++; result -= parseTerm(); }
                else break;
            }
            return result;
        }

        double parseTerm() {
            double result = parseFactor();
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c == '*') { pos++; result *= parseFactor(); }
                else if (c == '/') { pos++; result /= parseFactor(); }
                else break;
            }
            return result;
        }

        double parseFactor() {
            char c = input.charAt(pos);
            if (c == '(') {
                pos++;
                double result = parse();
                if (pos < input.length() && input.charAt(pos) == ')') pos++;
                return result;
            }
            if (c == '-' || c == '+') {
                int sign = c == '-' ? -1 : 1;
                pos++;
                return sign * parseFactor();
            }
            int start = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) pos++;
            return Double.parseDouble(input.substring(start, pos));
        }
    }

    private double evaluate(String expr) {
        return new Parser(expr).parse();
    }
}
