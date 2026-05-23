package com.assistant.controller;

import com.assistant.config.AssistantProperties;
import com.assistant.entity.ChatMessage;
import com.assistant.entity.Conversation;
import com.assistant.service.ChatService;
import com.assistant.service.RagService;
import com.assistant.mcp.McpConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天控制器 - 提供 REST API
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final RagService ragService;
    private final McpConfig mcpConfig;
    private final AssistantProperties properties;

    // ===================== REST API =====================

    /**
     * 创建新对话
     */
    @PostMapping("/api/conversations")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createConversation(
            @RequestParam(defaultValue = "ollama") String modelType) {
        Long conversationId = chatService.createConversation(modelType);
        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", conversationId);
        log.info("创建新对话: id={}, modelType={}", conversationId, modelType);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取对话列表
     */
    @GetMapping("/api/conversations")
    @ResponseBody
    public ResponseEntity<List<Conversation>> getConversations() {
        return ResponseEntity.ok(chatService.getConversations());
    }

    /**
     * 获取对话消息历史
     */
    @GetMapping("/api/conversations/{conversationId}/messages")
    @ResponseBody
    public ResponseEntity<List<ChatMessage>> getMessages(
            @PathVariable Long conversationId) {
        return ResponseEntity.ok(chatService.getMessages(conversationId));
    }

    /**
     * 发送聊天消息 (非流式)
     */
    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<ChatService.ChatResponse> chat(
            @RequestParam Long conversationId,
            @RequestParam String message) {

        log.info("收到消息: conversationId={}, message={}", conversationId,
                message.length() > 50 ? message.substring(0, 50) + "..." : message);

        ChatService.ChatResponse response = chatService.chat(conversationId, message);
        return ResponseEntity.ok(response);
    }

    /**
     * 发送聊天消息 (流式 SSE)
     */
    @PostMapping("/api/chat/stream")
    public SseEmitter streamChat(
            @RequestParam Long conversationId,
            @RequestParam String message) {

        log.info("收到流式请求: conversationId={}", conversationId);
        SseEmitter emitter = new SseEmitter(120_000L);

        chatService.streamChat(conversationId, message, emitter);

        return emitter;
    }

    /**
     * 删除对话
     */
    @DeleteMapping("/api/conversations/{conversationId}")
    @ResponseBody
    public ResponseEntity<Void> deleteConversation(
            @PathVariable Long conversationId) {
        chatService.deleteConversation(conversationId);
        return ResponseEntity.ok().build();
    }

    /**
     * 健康检查
     */
    @GetMapping("/api/health")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "running");
        status.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(status);
    }

    /**
     * 助手状态
     */
    @GetMapping("/api/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> info = new HashMap<>();
        info.put("conversations", chatService.getConversations().size());
        info.put("model", properties.getDefaultModel());
        info.put("rag", ragService.getStats());
        info.put("mcp", mcpConfig.getConnectedServers());
        return ResponseEntity.ok(info);
    }

    /**
     * 上传文档到知识库
     */
    @PostMapping("/api/documents/upload")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file) {
        log.info("上传文档: {} ({} bytes)", file.getOriginalFilename(), file.getSize());

        if (file.isEmpty()) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "上传文件为空");
            return ResponseEntity.badRequest().body(err);
        }

        try {
            Map<String, Object> result = ragService.ingestFile(
                    file.getOriginalFilename(), file.getBytes());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("文档上传处理失败", e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "上传失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }
}
