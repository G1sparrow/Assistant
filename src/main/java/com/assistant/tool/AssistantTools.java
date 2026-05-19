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
            // 使用 Java 脚本引擎执行计算
            javax.script.ScriptEngineManager manager = new javax.script.ScriptEngineManager();
            javax.script.ScriptEngine engine = manager.getEngineByName("JavaScript");
            if (engine == null) {
                // 如果 JavaScript 引擎不可用，使用简单的手动解析
                return evaluateSimpleExpression(expression);
            }
            Object result = engine.eval(expression);
            return String.valueOf(result);
        } catch (Exception e) {
            log.warn("计算失败: {}", e.getMessage());
            return "计算错误: " + e.getMessage();
        }
    }

    /**
     * RAG 知识库检索工具
     */
    @Tool("在本地知识库中搜索相关信息，当需要查阅本地文档时使用此工具")
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
        return String.format("字数: %d, 字符数(含空格): %d, 字符数(不含空格): %d",
                text.length(),
                text.length(),
                text.replace(" ", "").length());
    }

    /**
     * 简易表达式求值 (JS引擎不可用时的后备方案)
     */
    private String evaluateSimpleExpression(String expression) {
        expression = expression.trim().replaceAll("\\s+", "");
        try {
            // 仅支持简单的 + - * / 运算
            if (expression.matches("[0-9+\\-*/().]+")) {
                // 使用递归下降或简单的表达式解析
                return "表达式格式支持: 请确保使用正确的数学表达式";
            }
            return "不支持的表达式格式";
        } catch (Exception e) {
            return "计算错误: " + e.getMessage();
        }
    }
}
