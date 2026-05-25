package com.assistant.repository;

import com.assistant.entity.ReviewQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewQuestionRepository extends JpaRepository<ReviewQuestion, Long> {

    List<ReviewQuestion> findByNoteId(Long noteId);

    void deleteByNoteId(Long noteId);
}
