package com.assistant.repository;

import com.assistant.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 查找某对话的所有消息，按创建时间排序
     */
    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    /**
     * 查找某对话最近的N条消息
     */
    List<ChatMessage> findByConversationIdOrderByCreatedAtDesc(Long conversationId);
}
