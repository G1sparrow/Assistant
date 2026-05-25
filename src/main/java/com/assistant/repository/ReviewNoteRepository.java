package com.assistant.repository;

import com.assistant.entity.ReviewNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewNoteRepository extends JpaRepository<ReviewNote, Long> {

    List<ReviewNote> findAllByOrderByCreatedAtDesc();
}
