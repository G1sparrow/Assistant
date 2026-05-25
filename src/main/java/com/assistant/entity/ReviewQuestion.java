package com.assistant.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "review_questions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    @Column(columnDefinition = "CLOB", nullable = false)
    private String questionText;

    @Column(name = "correct_answer", columnDefinition = "CLOB", nullable = false)
    private String correctAnswer;

    @Column(name = "question_type", length = 20)
    private String questionType;

    @Column(length = 100)
    private String category;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
