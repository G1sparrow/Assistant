package com.assistant.service;

import com.assistant.config.AssistantProperties;
import com.assistant.config.LangChain4jConfig;
import com.assistant.config.LangChain4jConfig.StreamingAssistantAiService;
import com.assistant.entity.ChatMessage;
import com.assistant.entity.Conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天服务 - 桌面助手的核心业务逻辑
 * 整合了 LLM 调用、历史记忆、RAG 检索、Tool 调用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final LangChain4jConfig.AssistantAiService aiService;
    private final StreamingAssistantAiService streamingAiService;
    private final MemoryService memoryService;
    private final AssistantProperties properties;

    /**
     * 发送消息并获取回复 (非流式)
     */
    public ChatResponse chat(Long conversationId, String userMessage) {
        log.info("===== 用户输入 [conversationId={}] =====", conversationId);
        log.info("{}", userMessage);

        // 1. 构建包含完整对话历史的 system prompt
        String systemPrompt = buildSystemPrompt(conversationId);

        try {
            // 2. 调用 AI 模型（不再依赖 LangChain4j ChatMemory）
            String response = aiService.chat(userMessage, systemPrompt);

            // 3. 统一保存用户消息 + AI 回复到 DB
            memoryService.saveUserMessage(conversationId, userMessage);
            memoryService.saveAssistantMessage(conversationId, response);

            log.info("===== AI 回复 [conversationId={}] =====", conversationId);
            log.info("{}", response);

            // 4. 如果启用对话摘要，异步更新
            if (properties.getAgent().getMemory().isSummaryEnabled()) {
                updateSummaryAsync(conversationId);
            }

            return ChatResponse.builder()
                    .conversationId(conversationId)
                    .message(response)
                    .build();

        } catch (Exception e) {
            log.error("AI 调用失败", e);
            return ChatResponse.builder()
                    .conversationId(conversationId)
                    .message("抱歉，我遇到了一些问题: " + e.getMessage())
                    .error(true)
                    .build();
        }
    }
    /**
     * 发送消息并获取流式回复 (SSE)
     */
    public void streamChat(Long conversationId, String userMessage, SseEmitter emitter) {
        log.info("===== 用户输入 [conversationId={}] =====", conversationId);
        log.info("{}", userMessage);

        String systemPrompt = buildSystemPrompt(conversationId);

        try {
            ObjectMapper mapper = new ObjectMapper();
            StringBuilder fullResponse = new StringBuilder();

            streamingAiService.chat(userMessage, systemPrompt)
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
                        log.info("===== AI 回复 [conversationId={}] =====", conversationId);
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
                        log.error("Streaming AI 调用失败", error);
                        try {
                            emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                            emitter.complete();
                        } catch (IOException e) {
                            log.error("SSE error send failed", e);
                        }
                    })
                    .start();
        } catch (Exception e) {
            log.error("启动流式 AI 失败", e);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
            } catch (IOException ex) {
                log.error("SSE send failed", ex);
            }
            emitter.completeWithError(e);
        }
    }

    /**
     * 创建新对话
     */
    public Long createConversation(String modelType) {
        Conversation conversation = memoryService.createConversation(modelType);
        return conversation.getId();
    }

    /**
     * 获取历史对话列表
     */
    public List<Conversation> getConversations() {
        return memoryService.getRecentConversations();
    }

    /**
     * 获取指定对话的消息历史
     */
    public List<ChatMessage> getMessages(Long conversationId) {
        return memoryService.getConversationMessages(conversationId);
    }

    /**
     * 删除对话
     */
    public void deleteConversation(Long conversationId) {
        memoryService.deleteConversation(conversationId);
    }

    /**
     * 构建系统提示词（含完整对话历史 + 摘要）
     */
    private String buildSystemPrompt(Long conversationId) {
        String basePrompt = properties.getAgent().getSystemPrompt();
        StringBuilder prompt = new StringBuilder(basePrompt);

        // 1. 从 DB 加载对话历史（跳过 TOOL 等非对话消息）
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

        // 2. 附加对话摘要（如果启用）
        memoryService.getConversation(conversationId).ifPresent(conv -> {
            if (conv.getSummary() != null && !conv.getSummary().isBlank()) {
                prompt.append("\n===== 对话摘要 =====\n");
                prompt.append(conv.getSummary());
            }
        });
        log.info("上下文内容拼接后的提示词:"+ prompt);
        return prompt.toString();
    }

    /**
     * 异步更新对话摘要
     */
    private void updateSummaryAsync(Long conversationId) {
        // 在实际应用中，这里可以调用 LLM 生成对话摘要
        // 简单起见，目前只记录日志
        log.debug("对话摘要更新已触发: conversationId={}", conversationId);
    }

    /**
     * 聊天响应对象
     */
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
