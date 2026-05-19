package com.assistant.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 模型选择配置 - 根据配置切换使用的 LLM 模型
 */
@Slf4j
@Configuration
public class ModelSelectionConfig {

    private final AssistantProperties properties;

    public ModelSelectionConfig(AssistantProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        log.info("默认模型类型: {}", properties.getDefaultModel());
    }

    @Bean
    @Primary
    public ChatModel chatModel(
            @Qualifier("ollamaChatModel") ChatModel ollamaModel,
            @Qualifier("deepseekChatModel") ChatModel deepseekModel) {

        String defaultModel = properties.getDefaultModel();
        log.info("选择 ChatModel: {}", defaultModel);

        return switch (defaultModel.toLowerCase()) {
            case "deepseek" -> deepseekModel;
            case "ollama" -> ollamaModel;
            default -> {
                log.warn("未知的模型类型 '{}', 使用 Ollama 作为默认", defaultModel);
                yield ollamaModel;
            }
        };
    }

    @Bean
    @Primary
    public StreamingChatModel streamingChatModel(
            @Qualifier("ollamaStreamingChatModel") StreamingChatModel ollamaModel,
            @Qualifier("deepseekStreamingChatModel") StreamingChatModel deepseekModel) {

        String defaultModel = properties.getDefaultModel();

        return switch (defaultModel.toLowerCase()) {
            case "deepseek" -> deepseekModel;
            case "ollama" -> ollamaModel;
            default -> ollamaModel;
        };
    }
}
