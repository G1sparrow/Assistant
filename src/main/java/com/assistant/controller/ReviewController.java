package com.assistant.controller;

import com.assistant.entity.ChatMessage;
import com.assistant.service.MemoryService;
import com.assistant.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService reviewService;
    private final MemoryService memoryService;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "model", required = false, defaultValue = "ollama") String model) {

        try {
            String noteContent = content;
            String fileName = null;

            if (file != null && !file.isEmpty()) {
                fileName = file.getOriginalFilename();
                noteContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            }

            if (noteContent == null || noteContent.trim().isEmpty()) {
                Map<String, Object> err = new HashMap<>();
                err.put("success", false);
                err.put("message", "笔记内容为空，请提供文本或上传文件");
                return ResponseEntity.badRequest().body(err);
            }

            Map<String, Object> result = reviewService.uploadNote(noteContent, fileName, model);
            result.put("success", true);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("笔记上传处理失败", e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "处理失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    @PostMapping("/grade")
    public ResponseEntity<Map<String, Object>> grade(
            @RequestParam Long noteId,
            @RequestParam(value = "model", required = false, defaultValue = "ollama") String model,
            @RequestBody Map<String, Object> body) {

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawAnswers = (Map<String, Object>) body.get("answers");
            if (rawAnswers == null || rawAnswers.isEmpty()) {
                Map<String, Object> err = new HashMap<>();
                err.put("success", false);
                err.put("message", "答案内容为空");
                return ResponseEntity.badRequest().body(err);
            }

            Map<Integer, String> answers = new HashMap<>();
            for (var entry : rawAnswers.entrySet()) {
                try {
                    int idx = Integer.parseInt(entry.getKey());
                    answers.put(idx, entry.getValue().toString());
                } catch (NumberFormatException e) {
                    // skip invalid keys
                }
            }

            Map<String, Object> result = reviewService.gradeAnswers(noteId, answers, model);
            result.put("success", true);
            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        } catch (Exception e) {
            log.error("批改处理失败", e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "批改失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }

    // ============ SSE 流式端点 ============

    @PostMapping("/grade/stream")
    public SseEmitter streamGrade(
            @RequestParam Long noteId,
            @RequestParam(value = "model", required = false, defaultValue = "ollama") String model,
            @RequestBody Map<String, Object> body) {

        SseEmitter emitter = new SseEmitter(120_000L);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> rawAnswers = (Map<String, Object>) body.get("answers");
            if (rawAnswers == null || rawAnswers.isEmpty()) {
                emitter.send(SseEmitter.event().name("error").data("答案内容为空"));
                emitter.complete();
                return emitter;
            }

            Map<Integer, String> answers = new HashMap<>();
            for (var entry : rawAnswers.entrySet()) {
                try {
                    int idx = Integer.parseInt(entry.getKey());
                    answers.put(idx, entry.getValue().toString());
                } catch (NumberFormatException e) {
                    // skip invalid keys
                }
            }

            reviewService.streamGradeAnswers(noteId, answers, model, emitter);
        } catch (Exception e) {
            log.error("流式批改处理失败", e);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                emitter.complete();
            } catch (IOException ex) {
                log.error("SSE send failed", ex);
            }
        }

        return emitter;
    }

    @PostMapping("/upload/stream")
    public SseEmitter streamUpload(
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "model", required = false, defaultValue = "ollama") String model) {

        SseEmitter emitter = new SseEmitter(120_000L);

        try {
            String noteContent = content;
            String fileName = null;

            if (file != null && !file.isEmpty()) {
                fileName = file.getOriginalFilename();
                noteContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            }

            if (noteContent == null || noteContent.trim().isEmpty()) {
                emitter.send(SseEmitter.event().name("error").data("笔记内容为空"));
                emitter.complete();
                return emitter;
            }

            reviewService.streamUploadNote(noteContent, fileName, model, emitter);
        } catch (Exception e) {
            log.error("流式上传处理失败", e);
            try {
                emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                emitter.complete();
            } catch (IOException ex) {
                log.error("SSE send failed", ex);
            }
        }

        return emitter;
    }

    @GetMapping("/notes")
    public ResponseEntity<List<Map<String, Object>>> listNotes() {
        return ResponseEntity.ok(reviewService.listNotes());
    }

    @GetMapping("/notes/{noteId}")
    public ResponseEntity<Map<String, Object>> getNoteDetail(@PathVariable Long noteId) {
        try {
            return ResponseEntity.ok(reviewService.getNoteDetail(noteId));
        } catch (IllegalArgumentException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    @DeleteMapping("/notes/{noteId}")
    public ResponseEntity<Void> deleteNote(@PathVariable Long noteId) {
        try {
            reviewService.deleteNote(noteId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<Void> saveMessage(
            @PathVariable Long conversationId,
            @RequestBody Map<String, String> body) {
        try {
            String role = body.get("role");
            String content = body.get("content");
            if (role == null || content == null) {
                return ResponseEntity.badRequest().build();
            }
            memoryService.saveMessagePlain(conversationId, content, role);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("保存消息失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<Map<String, Object>>> getMessages(
            @PathVariable Long conversationId) {
        try {
            List<ChatMessage> messages = memoryService.getConversationMessages(conversationId);
            List<Map<String, Object>> result = messages.stream().map(m -> {
                Map<String, Object> item = new HashMap<>();
                item.put("role", m.getRole());
                item.put("content", m.getContent());
                item.put("createdAt", m.getCreatedAt());
                return item;
            }).collect(Collectors.toList());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("获取消息失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
