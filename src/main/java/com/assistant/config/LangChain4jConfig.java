package com.assistant.config;

import com.assistant.tool.AssistantTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j 核心配置 - 支持 Ollama 本地模型和 DeepSeek API
 */
@Slf4j
@Configuration
public class LangChain4jConfig {

    private final AssistantProperties properties;

    public LangChain4jConfig(AssistantProperties properties) {
        this.properties = properties;
    }

    // ===================== Ollama 配置 =====================

    @Bean(name = "ollamaChatModel")
    public ChatModel ollamaChatModel() {
        AssistantProperties.Ollama cfg = properties.getOllama();
        log.info("初始化 Ollama 模型: {} @ {}", cfg.getModelName(), cfg.getBaseUrl());
        return OllamaChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getModelName())
                .temperature(cfg.getTemperature())
                .topP(cfg.getTopP())
                .numPredict(cfg.getMaxTokens())
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    @Bean(name = "ollamaStreamingChatModel")
    public StreamingChatModel ollamaStreamingChatModel() {
        AssistantProperties.Ollama cfg = properties.getOllama();
        return OllamaStreamingChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getModelName())
                .temperature(cfg.getTemperature())
                .topP(cfg.getTopP())
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    // ===================== DeepSeek 配置 (兼容 OpenAI API) =====================

    @Bean(name = "deepseekChatModel")
    public ChatModel deepseekChatModel() {
        AssistantProperties.Deepseek cfg = properties.getDeepseek();
        log.info("初始化 DeepSeek 模型: {}", cfg.getModelName());
        return OpenAiChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .modelName(cfg.getModelName())
                .temperature(cfg.getTemperature())
                .topP(cfg.getTopP())
                .maxTokens(cfg.getMaxTokens())
                .timeout(Duration.ofSeconds(120))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean(name = "deepseekStreamingChatModel")
    public StreamingChatModel deepseekStreamingChatModel() {
        AssistantProperties.Deepseek cfg = properties.getDeepseek();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .modelName(cfg.getModelName())
                .temperature(cfg.getTemperature())
                .topP(cfg.getTopP())
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    // ===================== Embedding 模型 (RAG) =====================

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("初始化本地 Embedding 模型: AllMiniLmL6V2");
        return new dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel();
    }

    @Bean
    public EmbeddingStore<dev.langchain4j.data.segment.TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    // ===================== AI 服务接口定义 =====================

    /**
     * AI 助手服务接口 - LangChain4j AI Service 模式
     * 注意：不再使用 @MemoryId + ChatMemoryProvider，
     * 对话历史由 ChatService 从 DB 加载显式放入 system prompt
     */
    public interface AssistantAiService {

        @SystemMessage("{{systemPrompt}}")
        String chat(@UserMessage String userMessage,
                    @V("systemPrompt") String systemPrompt);
    }

    /**
     * 流式 AI 助手服务接口
     */
    public interface StreamingAssistantAiService {

        @SystemMessage("{{systemPrompt}}")
        TokenStream chat(@UserMessage String userMessage,
                         @V("systemPrompt") String systemPrompt);
    }

    // ===================== AI 服务 Bean 构建 =====================

    @Bean(name = "ollamaAssistantAiService")
    public AssistantAiService ollamaAssistantAiService(
            @Qualifier("ollamaChatModel") ChatModel chatModel,
            AssistantTools tools) {
        log.info("构建 Ollama AiService");
        return AiServices.builder(AssistantAiService.class)
                .chatModel(chatModel)
                .tools(tools)
                .build();
    }

    @Bean(name = "deepseekAssistantAiService")
    public AssistantAiService deepseekAssistantAiService(
            @Qualifier("deepseekChatModel") ChatModel chatModel,
            AssistantTools tools) {
        log.info("构建 DeepSeek AiService");
        return AiServices.builder(AssistantAiService.class)
                .chatModel(chatModel)
                .tools(tools)
                .build();
    }

    @Bean(name = "ollamaStreamingAssistantAiService")
    public StreamingAssistantAiService ollamaStreamingAssistantAiService(
            @Qualifier("ollamaStreamingChatModel") StreamingChatModel streamingChatModel,
            AssistantTools tools) {
        return AiServices.builder(StreamingAssistantAiService.class)
                .streamingChatModel(streamingChatModel)
                .tools(tools)
                .build();
    }

    @Bean(name = "deepseekStreamingAssistantAiService")
    public StreamingAssistantAiService deepseekStreamingAssistantAiService(
            @Qualifier("deepseekStreamingChatModel") StreamingChatModel streamingChatModel,
            AssistantTools tools) {
        return AiServices.builder(StreamingAssistantAiService.class)
                .streamingChatModel(streamingChatModel)
                .tools(tools)
                .build();
    }
}
