package com.assistant.repository;

import com.assistant.entity.ReviewSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewSessionRepository extends JpaRepository<ReviewSession, Long> {

    List<ReviewSession> findByNoteIdOrderByCreatedAtDesc(Long noteId);
}
