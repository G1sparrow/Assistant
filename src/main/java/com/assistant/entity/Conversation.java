package com.assistant.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话会话实体 - 持久化存储对话记录
 */
@Entity
@Table(name = "conversations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 对话标题 (根据首条消息自动生成)
     */
    @Column(length = 200)
    private String title;

    /**
     * 使用的模型类型: ollama / deepseek
     */
    @Column(name = "model_type", length = 20)
    private String modelType;

    /**
     * 对话摘要 (用于长期记忆)
     */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /**
     * 对话状态: ACTIVE / ARCHIVED
     */
    @Column(length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 关联的消息列表
     * (JSON 序列化时忽略，消息通过独立 API 加载)
     */
    @JsonIgnore
    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<ChatMessage> messages = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
