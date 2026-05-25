package com.assistant.service;

import com.assistant.config.AssistantProperties;
import com.assistant.config.LangChain4jConfig;
import com.assistant.config.LangChain4jConfig.StreamingAssistantAiService;
import com.assistant.entity.ChatMessage;
import com.assistant.entity.Conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatService {

    private final LangChain4jConfig.AssistantAiService ollamaAiService;
    private final LangChain4jConfig.AssistantAiService deepseekAiService;
    private final StreamingAssistantAiService ollamaStreamingAiService;
    private final StreamingAssistantAiService deepseekStreamingAiService;
    private final MemoryService memoryService;
    private final AssistantProperties properties;

    public ChatService(
            @Qualifier("ollamaAssistantAiService") LangChain4jConfig.AssistantAiService ollamaAiService,
            @Qualifier("deepseekAssistantAiService") LangChain4jConfig.AssistantAiService deepseekAiService,
            @Qualifier("ollamaStreamingAssistantAiService") StreamingAssistantAiService ollamaStreamingAiService,
            @Qualifier("deepseekStreamingAssistantAiService") StreamingAssistantAiService deepseekStreamingAiService,
            MemoryService memoryService,
            AssistantProperties properties) {
        this.ollamaAiService = ollamaAiService;
        this.deepseekAiService = deepseekAiService;
        this.ollamaStreamingAiService = ollamaStreamingAiService;
        this.deepseekStreamingAiService = deepseekStreamingAiService;
        this.memoryService = memoryService;
        this.properties = properties;
    }

    public ChatResponse chat(Long conversationId, String userMessage) {
        log.info("===== 用户输入 [conversationId={}] =====", conversationId);
        log.info("{}", userMessage);

        String systemPrompt = buildSystemPrompt(conversationId);
        String modelType = getModelType(conversationId);

        try {
            LangChain4jConfig.AssistantAiService aiService = selectAiService(modelType);
            String response = aiService.chat(userMessage, systemPrompt);

            memoryService.saveUserMessage(conversationId, userMessage);
            memoryService.saveAssistantMessage(conversationId, response);

            log.info("===== AI 回复 [conversationId={}, model={}] =====", conversationId, modelType);
            log.info("{}", response);

            if (properties.getAgent().getMemory().isSummaryEnabled()) {
                updateSummaryAsync(conversationId);
            }

            return ChatResponse.builder()
                    .conversationId(conversationId)
                    .message(response)
                    .build();

        } catch (Exception e) {
            log.error("AI 调用失败 [model={}]", modelType, e);
            return ChatResponse.builder()
                    .conversationId(conversationId)
                    .message("抱歉，我遇到了一些问题: " + e.getMessage())
                    .error(true)
                    .build();
        }
    }

    public void streamChat(Long conversationId, String userMessage, SseEmitter emitter) {
        log.info("===== 用户输入 [conversationId={}] =====", conversationId);
        log.info("{}", userMessage);

        String systemPrompt = buildSystemPrompt(conversationId);
        String modelType = getModelType(conversationId);

        try {
            ObjectMapper mapper = new ObjectMapper();
            StringBuilder fullResponse = new StringBuilder();

            StreamingAssistantAiService aiService = selectStreamingAiService(modelType);

            aiService.chat(userMessage, systemPrompt)
                    .onPartialResponse(token -> {
                        fullResponse.append(token);
                        try {
                            emitter.send(SseEmitter.event().name("token").data(token));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .onToolExecuted(toolExec -> {
                        try {
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("name", toolExec.request().name());
                            info.put("arguments", toolExec.request().arguments());
                            info.put("result", toolExec.result());
                            emitter.send(SseEmitter.event().name("tool_call").data(mapper.writeValueAsString(info)));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .onCompleteResponse(response -> {
                        String reply = fullResponse.toString();
                        memoryService.saveUserMessage(conversationId, userMessage);
                        memoryService.saveAssistantMessage(conversationId, reply);
                        log.info("===== AI 回复 [conversationId={}, model={}] =====", conversationId, modelType);
                        log.info("{}", reply);
                        if (properties.getAgent().getMemory().isSummaryEnabled()) {
                            updateSummaryAsync(conversationId);
                        }
                        try {
                            emitter.send(SseEmitter.event().name("done").data(conversationId));
                            emitter.complete();
                        } catch (IOException e) {
                            log.error("SSE complete send failed", e);
                        }
                    })
                    .onError(error -> {
                        log.error("Streaming AI 调用失败 [model={}]", modelType, error);
                        try {
                            emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                            emitter.complete();
                        } catch (IOException e) {
                            log.error("SSE error send failed", e);
                        }
                    })
                    .start();
        } catch (Exception e) {
            log.error("启动流式 AI 失败 [model={}]", modelType, e);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ex) {
                log.error("SSE send failed", ex);
            }
            emitter.completeWithError(e);
        }
    }

    public Long createConversation(String modelType) {
        Conversation conversation = memoryService.createConversation(modelType);
        return conversation.getId();
    }

    public List<Conversation> getConversations() {
        return memoryService.getRecentConversations();
    }

    public List<ChatMessage> getMessages(Long conversationId) {
        return memoryService.getConversationMessages(conversationId);
    }

    public void deleteConversation(Long conversationId) {
        memoryService.deleteConversation(conversationId);
    }

    public void updateModel(Long conversationId, String modelType) {
        memoryService.updateConversationModel(conversationId, modelType);
        log.info("切换对话模型: id={}, modelType={}", conversationId, modelType);
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("conversations", getConversations().size());
        info.put("model", properties.getDefaultModel());
        return info;
    }

    private String getModelType(Long conversationId) {
        return memoryService.getConversation(conversationId)
                .map(Conversation::getModelType)
                .orElse("ollama");
    }

    private LangChain4jConfig.AssistantAiService selectAiService(String modelType) {
        return switch (modelType.toLowerCase()) {
            case "deepseek" -> deepseekAiService;
            default -> ollamaAiService;
        };
    }

    private StreamingAssistantAiService selectStreamingAiService(String modelType) {
        return switch (modelType.toLowerCase()) {
            case "deepseek" -> deepseekStreamingAiService;
            default -> ollamaStreamingAiService;
        };
    }

    private String buildSystemPrompt(Long conversationId) {
        String basePrompt = properties.getAgent().getSystemPrompt();
        StringBuilder prompt = new StringBuilder(basePrompt);

        List<ChatMessage> messages = memoryService.getConversationMessages(conversationId);
        int maxHistory = properties.getAgent().getMemory().getMaxHistoryMessages();
        if (messages != null && !messages.isEmpty()) {
            prompt.append("\n\n===== 对话历史 =====\n");
            int start = Math.max(0, messages.size() - maxHistory);
            for (int i = start; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                String role;
                if ("USER".equals(msg.getRole())) {
                    role = "用户";
                } else if ("ASSISTANT".equals(msg.getRole())) {
                    role = "助手";
                } else {
                    continue;
                }
                prompt.append(role).append(": ").append(msg.getContent()).append("\n");
            }
        }

        memoryService.getConversation(conversationId).ifPresent(conv -> {
            if (conv.getSummary() != null && !conv.getSummary().isBlank()) {
                prompt.append("\n===== 对话摘要 =====\n");
                prompt.append(conv.getSummary());
            }
        });
        return prompt.toString();
    }

    private void updateSummaryAsync(Long conversationId) {
        log.debug("对话摘要更新已触发: conversationId={}", conversationId);
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ChatResponse {
        private Long conversationId;
        private String message;
        @lombok.Builder.Default
        private boolean error = false;
    }
}
