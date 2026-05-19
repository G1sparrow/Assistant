package com.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 助手配置属性映射
 */
@Data
@Component
@ConfigurationProperties(prefix = "assistant")
public class AssistantProperties {

    private String defaultModel;
    private Ollama ollama;
    private Deepseek deepseek;
    private Mcp mcp;
    private Rag rag;
    private Agent agent;

    @Data
    public static class Ollama {
        private String baseUrl;
        private String modelName;
        private double temperature;
        private double topP;
        private int maxTokens;
        private String embeddingModelName;
    }

    @Data
    public static class Deepseek {
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private double temperature;
        private double topP;
        private int maxTokens;
    }

    @Data
    public static class Mcp {
        private boolean enabled;
        private List<McpServer> servers;
    }

    @Data
    public static class McpServer {
        private String name;
        private String transport;
        private String command;
        private List<String> args;
    }

    @Data
    public static class Rag {
        private boolean enabled;
        private int maxResults;
        private double minScore;
        private String documentDir;
    }

    @Data
    public static class Agent {
        private String systemPrompt;
        private Memory memory;
    }

    @Data
    public static class Memory {
        private int maxMessages;
        private boolean summaryEnabled;
    }
}
