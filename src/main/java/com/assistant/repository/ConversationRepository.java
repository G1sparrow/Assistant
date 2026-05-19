package com.assistant.repository;

import com.assistant.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /**
     * 查找所有活跃的对话，按更新时间降序排列
     */
    List<Conversation> findByStatusOrderByUpdatedAtDesc(String status);

    /**
     * 查找最近的活跃对话
     */
    @Query("SELECT c FROM Conversation c WHERE c.status = 'ACTIVE' ORDER BY c.updatedAt DESC")
    List<Conversation> findRecentActiveConversations();

    /**
     * 根据标题模糊搜索
     */
    List<Conversation> findByTitleContainingIgnoreCase(String title);
}
