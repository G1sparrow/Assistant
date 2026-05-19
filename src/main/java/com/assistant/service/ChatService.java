package com.assistant.service;

import com.assistant.config.AssistantProperties;
import com.assistant.config.LangChain4jConfig;
import com.assistant.entity.ChatMessage;
import com.assistant.entity.Conversation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天服务 - 桌面助手的核心业务逻辑
 * 整合了 LLM 调用、历史记忆、RAG 检索、Tool 调用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final LangChain4jConfig.AssistantAiService aiService;
    private final MemoryService memoryService;
    private final AssistantProperties properties;

    // 存储 SSE 流式会话 (conversationId -> SseEmitter)
    private final Map<Long, List<Object>> streamingSessions = new ConcurrentHashMap<>();

    /**
     * 发送消息并获取回复 (非流式)
     */
    public ChatResponse chat(Long conversationId, String userMessage) {
        // 1. 保存用户消息
        memoryService.saveUserMessage(conversationId, userMessage);

        // 2. 获取对话历史上下文
        String systemPrompt = buildSystemPrompt(conversationId);

        try {
            // 3. 调用 AI 模型
            log.debug("调用 AI 模型, conversationId={}, message={}", conversationId,
                    userMessage.substring(0, Math.min(50, userMessage.length())));

            String response = aiService.chat(conversationId, userMessage, systemPrompt);

            // 4. 保存助手回复
            memoryService.saveAssistantMessage(conversationId, response);

            // 5. 如果启用对话摘要，异步更新
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
     * 构建系统提示词
     */
    private String buildSystemPrompt(Long conversationId) {
        String basePrompt = properties.getAgent().getSystemPrompt();

        StringBuilder prompt = new StringBuilder(basePrompt);

        // 附加对话历史摘要 (如果有)
        memoryService.getConversation(conversationId).ifPresent(conv -> {
            if (conv.getSummary() != null && !conv.getSummary().isBlank()) {
                prompt.append("\n\n===== 对话历史摘要 =====\n");
                prompt.append(conv.getSummary());
            }
        });

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
