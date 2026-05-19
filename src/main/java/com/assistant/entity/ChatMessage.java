package com.assistant.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天消息实体 - 每条消息的持久化存储
 */
@Entity
@Table(name = "chat_messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的对话ID (JSON 序列化时忽略，避免循环引用)
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    /**
     * 角色: USER / ASSISTANT / SYSTEM / TOOL
     */
    @Column(name = "role", nullable = false, length = 20)
    private String role;

    /**
     * 消息内容
     */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 消息类型: text / tool_call / tool_result / image
     */
    @Column(name = "message_type", length = 20)
    @Builder.Default
    private String messageType = "text";

    /**
     * Tool 调用信息 (JSON格式)
     */
    @Column(name = "tool_info", columnDefinition = "TEXT")
    private String toolInfo;

    /**
     * Token 用量 (如果有)
     */
    @Column(name = "tokens_used")
    private Integer tokensUsed;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
