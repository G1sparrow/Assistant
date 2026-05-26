package com.assistant.service;

import com.assistant.entity.ChatMessage;
import com.assistant.entity.Conversation;
import com.assistant.repository.ChatMessageRepository;
import com.assistant.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 记忆服务 - 管理对话历史和持久化存储
 * 提供消息持久化、历史加载、对话摘要等功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * 创建新对话
     */
    @Transactional
    public Conversation createConversation(String modelType) {
        Conversation conversation = Conversation.builder()
                .title("新对话")
                .modelType(modelType != null ? modelType : "ollama")
                .status("ACTIVE")
                .build();
        conversation = conversationRepository.save(conversation);
        log.debug("创建新对话: id={}", conversation.getId());
        return conversation;
    }

    /**
     * 获取对话
     */
    public Optional<Conversation> getConversation(Long conversationId) {
        return conversationRepository.findById(conversationId);
    }

    /**
     * 获取最近的活跃对话列表
     */
    public List<Conversation> getRecentConversations() {
        return conversationRepository.findRecentActiveConversations();
    }

    /**
     * 保存用户消息
     */
    @Transactional
    public ChatMessage saveUserMessage(Long conversationId, String content) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("对话不存在: " + conversationId));

        ChatMessage message = ChatMessage.builder()
                .conversation(conversation)
                .role("USER")
                .content(content)
                .messageType("text")
                .build();
        message = chatMessageRepository.save(message);

        // 如果标题是默认的"新对话"，用首条消息前20个字作为标题
        if ("新对话".equals(conversation.getTitle())) {
            String title = content.length() > 20 ? content.substring(0, 20) + "..." : content;
            conversation.setTitle(title);
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(conversation);
        }

        return message;
    }

    /**
     * 保存助手回复
     */
    @Transactional
    public ChatMessage saveAssistantMessage(Long conversationId, String content) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("对话不存在: " + conversationId));

        ChatMessage message = ChatMessage.builder()
                .conversation(conversation)
                .role("ASSISTANT")
                .content(content)
                .messageType("text")
                .build();
        message = chatMessageRepository.save(message);

        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        return message;
    }

    /**
     * 获取对话的全部消息历史
     */
    public List<ChatMessage> getConversationMessages(Long conversationId) {
        return chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /**
     * 获取最近的N条消息
     */
    public List<ChatMessage> getRecentMessages(Long conversationId, int limit) {
        List<ChatMessage> messages = chatMessageRepository
                .findByConversationIdOrderByCreatedAtDesc(conversationId);
        int fromIndex = Math.max(0, messages.size() - limit);
        return messages.subList(fromIndex, messages.size());
    }

    /**
     * 更新对话摘要 (用于长期记忆)
     */
    @Transactional
    public void updateConversationSummary(Long conversationId, String summary) {
        conversationRepository.findById(conversationId).ifPresent(conv -> {
            conv.setSummary(summary);
            conversationRepository.save(conv);
        });
    }

    /**
     * 删除对话
     */
    @Transactional
    public void deleteConversation(Long conversationId) {
        chatMessageRepository.deleteByConversationId(conversationId);
        conversationRepository.deleteById(conversationId);
        log.info("删除对话: id={}", conversationId);
    }

    /**
     * 更新对话模型类型
     */
    @Transactional
    public void updateConversationModel(Long conversationId, String modelType) {
        conversationRepository.findById(conversationId).ifPresent(conv -> {
            conv.setModelType(modelType);
            conversationRepository.save(conv);
            log.info("更新对话模型: id={}, modelType={}", conversationId, modelType);
        });
    }

    /**
     * 归档对话
     */
    @Transactional
    public void archiveConversation(Long conversationId) {
        conversationRepository.findById(conversationId).ifPresent(conv -> {
            conv.setStatus("ARCHIVED");
            conversationRepository.save(conv);
        });
    }

    @Transactional
    public ChatMessage saveMessagePlain(Long conversationId, String content, String role) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new RuntimeException("对话不存在: " + conversationId));

        ChatMessage message = ChatMessage.builder()
                .conversation(conversation)
                .role(role)
                .content(content)
                .messageType("text")
                .build();
        message = chatMessageRepository.save(message);

        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        return message;
    }
}
