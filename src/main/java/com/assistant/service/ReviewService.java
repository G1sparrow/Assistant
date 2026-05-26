package com.assistant.service;

import com.assistant.entity.ReviewNote;
import com.assistant.entity.ReviewQuestion;
import com.assistant.entity.ReviewSession;
import com.assistant.repository.ReviewNoteRepository;
import com.assistant.repository.ReviewQuestionRepository;
import com.assistant.repository.ReviewSessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ReviewService {

    private final ReviewNoteRepository noteRepository;
    private final ReviewQuestionRepository questionRepository;
    private final ReviewSessionRepository sessionRepository;
    private final ChatModel ollamaChatModel;
    private final ChatModel deepseekChatModel;
    private final StreamingChatModel ollamaStreamingChatModel;
    private final StreamingChatModel deepseekStreamingChatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewService(
            ReviewNoteRepository noteRepository,
            ReviewQuestionRepository questionRepository,
            ReviewSessionRepository sessionRepository,
            @Qualifier("ollamaChatModel") ChatModel ollamaChatModel,
            @Qualifier("deepseekChatModel") ChatModel deepseekChatModel,
            @Qualifier("ollamaStreamingChatModel") StreamingChatModel ollamaStreamingChatModel,
            @Qualifier("deepseekStreamingChatModel") StreamingChatModel deepseekStreamingChatModel) {
        this.noteRepository = noteRepository;
        this.questionRepository = questionRepository;
        this.sessionRepository = sessionRepository;
        this.ollamaChatModel = ollamaChatModel;
        this.deepseekChatModel = deepseekChatModel;
        this.ollamaStreamingChatModel = ollamaStreamingChatModel;
        this.deepseekStreamingChatModel = deepseekStreamingChatModel;
    }

    private static final String GENERATE_PROMPT_TEMPLATE = """
            你是一个学习辅导老师。根据以下学习笔记内容，生成 3-5 道复习题。

            要求：
            1. 题目类型必须混合：识记型（概念/定义）、应用型（用法/场景）、理解型（原理/对比/分析）
            2. 每道题需给出标准答案
            3. 输出格式为合法的 JSON 数组，不要包含 markdown 代码块标记
            4. JSON 数组元素之间必须用英文逗号分隔，否则解析失败！！！

            输出格式示例（注意每个对象后面都有逗号，最后一个对象后面没有逗号）：
            [
              {
                "question": "什么是机器学习？",
                "type": "识记型",
                "correctAnswer": "机器学习是...",
                "category": "基础概念"
              },
              {
                "question": "......",
                "type": "应用型",
                "correctAnswer": "......",
                "category": "......"
              }
            ]

            笔记内容：
            %s
            """;

    private static final String GRADE_PROMPT_TEMPLATE = """
            你是学习批改老师。请批改以下 %d 道题的答案。

            评分规则：
            - 每题满分按总分100分平均分配
            - 客观题（识记型）严格按关键词匹配评分
            - 主观题（应用型、理解型）根据知识点覆盖度评分：完整/部分/缺失
            - 给出具体错误原因和正确答案提示

            输出格式为合法的 JSON 数组，不要包含 markdown 代码块标记。
            JSON 数组元素之间必须用英文逗号分隔，否则解析失败！！！

            输出格式示例（注意每个对象后面都有逗号）：
            [
              {
                "questionIndex": 0,
                "score": 30,
                "correct": true,
                "feedback": "回答正确/错误原因...",
                "correctAnswer": "标准答案"
              },
              {
                "questionIndex": 1,
                "score": 20,
                "correct": false,
                "feedback": "......",
                "correctAnswer": "......"
              }
            ]

            题目与答案：
            %s
            """;

    @Transactional
    public Map<String, Object> uploadNote(String content, String fileName, String modelType) {
        String title = generateTitle(content);
        ReviewNote note = ReviewNote.builder()
                .title(title)
                .content(content)
                .filePath(fileName)
                .questionCount(0)
                .build();
        note = noteRepository.save(note);

        List<ReviewQuestion> questions = generateQuestions(content, note.getId(), modelType);
        note.setQuestionCount(questions.size());
        noteRepository.save(note);

        Map<String, Object> result = new HashMap<>();
        result.put("noteId", note.getId());
        result.put("title", note.getTitle());
        result.put("questionCount", questions.size());

        List<Map<String, Object>> questionList = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            ReviewQuestion q = questions.get(i);
            Map<String, Object> qm = new HashMap<>();
            qm.put("index", i);
            qm.put("question", q.getQuestionText());
            qm.put("type", q.getQuestionType());
            questionList.add(qm);
        }
        result.put("questions", questionList);

        return result;
    }

    @Transactional
    public Map<String, Object> gradeAnswers(Long noteId, Map<Integer, String> answers, String modelType) {
        log.info("用户提交答案 [noteId={}, model={}, 题数={}]", noteId, modelType, answers.size());
        List<ReviewQuestion> questions = questionRepository.findByNoteId(noteId);
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("未找到该笔记的复习题: " + noteId);
        }

        StringBuilder qaBuilder = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            ReviewQuestion q = questions.get(i);
            String userAnswer = answers.getOrDefault(i, "");
            qaBuilder.append("题").append(i + 1).append(": ").append(q.getQuestionText()).append("\n");
            qaBuilder.append("  标准答案: ").append(q.getCorrectAnswer()).append("\n");
            qaBuilder.append("  用户答案: ").append(userAnswer).append("\n\n");
        }

        int perScore = 100 / questions.size();
        int remainder = 100 % questions.size();
        List<Integer> scoreMap = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            scoreMap.add(perScore + (i < remainder ? 1 : 0));
        }

        String prompt = String.format(GRADE_PROMPT_TEMPLATE, questions.size(), qaBuilder.toString());
        String extra = "各题满分: ";
        for (int i = 0; i < scoreMap.size(); i++) {
            extra += "题" + (i + 1) + "=" + scoreMap.get(i) + "分" + (i < scoreMap.size() - 1 ? ", " : "");
        }
        prompt += "\n" + extra;

        ChatModel model = selectModel(modelType);
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from(prompt)))
                .build());
        String llmResponse = response.aiMessage().text();
        log.info("AI 批改结果 [noteId={}, model={}]: length={}", noteId, modelType, llmResponse.length());
        List<Map<String, Object>> gradingResults = parseGradingResult(llmResponse, questions.size());

        int totalScore = 0;
        for (var g : gradingResults) {
            totalScore += ((Number) g.getOrDefault("score", 0)).intValue();
        }

        Map<String, Object> feedbackMap = new HashMap<>();
        feedbackMap.put("grading", gradingResults);
        feedbackMap.put("scoreMap", scoreMap);
        String feedbackJson = toJsonString(feedbackMap);

        String answersJson = toJsonString(answers);

        ReviewSession session = ReviewSession.builder()
                .noteId(noteId)
                .answersJson(answersJson)
                .totalScore(totalScore)
                .feedbackJson(feedbackJson)
                .build();
        sessionRepository.save(session);

        log.info("批改完成 [noteId={}, 总分={}/100, sessionId={}]", noteId, totalScore, session.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", session.getId());
        result.put("totalScore", totalScore);
        result.put("maxScore", 100);
        result.put("details", gradingResults);
        result.put("questionCount", questions.size());

        return result;
    }

    public void streamGradeAnswers(Long noteId, Map<Integer, String> answers, String modelType, SseEmitter emitter) {
        log.info("流式批改开始 [noteId={}, model={}, 题数={}]", noteId, modelType, answers.size());

        List<ReviewQuestion> questions;
        try {
            questions = questionRepository.findByNoteId(noteId);
            if (questions.isEmpty()) {
                emitter.send(SseEmitter.event().name("error").data("未找到该笔记的复习题: " + noteId));
                emitter.complete();
                return;
            }
        } catch (Exception e) {
            try {
                emitter.send(SseEmitter.event().name("error").data("查询失败: " + e.getMessage()));
                emitter.complete();
            } catch (IOException ex) {}
            return;
        }

        StringBuilder qaBuilder = new StringBuilder();
        for (int i = 0; i < questions.size(); i++) {
            ReviewQuestion q = questions.get(i);
            String userAnswer = answers.getOrDefault(i, "");
            qaBuilder.append("题").append(i + 1).append(": ").append(q.getQuestionText()).append("\n");
            qaBuilder.append("  标准答案: ").append(q.getCorrectAnswer()).append("\n");
            qaBuilder.append("  用户答案: ").append(userAnswer).append("\n\n");
        }

        int perScore = 100 / questions.size();
        int remainder = 100 % questions.size();
        List<Integer> scoreMap = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            scoreMap.add(perScore + (i < remainder ? 1 : 0));
        }

        String prompt = String.format(GRADE_PROMPT_TEMPLATE, questions.size(), qaBuilder.toString());
        String extra = "各题满分: ";
        for (int i = 0; i < scoreMap.size(); i++) {
            extra += "题" + (i + 1) + "=" + scoreMap.get(i) + "分" + (i < scoreMap.size() - 1 ? ", " : "");
        }
        prompt += "\n" + extra;

        StreamingChatModel model = selectStreamingModel(modelType);
        StringBuilder fullResponse = new StringBuilder();

        model.chat(
                ChatRequest.builder()
                        .messages(List.of(UserMessage.from(prompt)))
                        .build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        fullResponse.append(partialResponse);
                        try {
                            emitter.send(SseEmitter.event().name("token").data(partialResponse));
                        } catch (IOException e) {
                            log.error("SSE token send failed", e);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        String llmResponse = fullResponse.toString();
                        log.info("AI 流式批改完成 [noteId={}, model={}]: length={}", noteId, modelType, llmResponse.length());

                        List<Map<String, Object>> gradingResults = parseGradingResult(llmResponse, questions.size());
                        int totalScore = 0;
                        for (var g : gradingResults) {
                            totalScore += ((Number) g.getOrDefault("score", 0)).intValue();
                        }

                        Map<String, Object> feedbackMap = new HashMap<>();
                        feedbackMap.put("grading", gradingResults);
                        feedbackMap.put("scoreMap", scoreMap);
                        String feedbackJson = toJsonString(feedbackMap);
                        String answersJson = toJsonString(answers);

                        ReviewSession session = ReviewSession.builder()
                                .noteId(noteId)
                                .answersJson(answersJson)
                                .totalScore(totalScore)
                                .feedbackJson(feedbackJson)
                                .build();
                        sessionRepository.save(session);

                        log.info("流式批改完成 [noteId={}, 总分={}/100, sessionId={}]", noteId, totalScore, session.getId());

                        try {
                            Map<String, Object> result = new HashMap<>();
                            result.put("sessionId", session.getId());
                            result.put("totalScore", totalScore);
                            result.put("maxScore", 100);
                            result.put("details", gradingResults);
                            result.put("questionCount", questions.size());
                            String resultJson = objectMapper.writeValueAsString(result);
                            emitter.send(SseEmitter.event().name("done").data(resultJson));
                            emitter.complete();
                        } catch (IOException e) {
                            log.error("SSE complete send failed", e);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("流式批改 LLM 调用失败 [noteId={}, model={}]", noteId, modelType, error);
                        try {
                            emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                            emitter.complete();
                        } catch (IOException e) {
                            log.error("SSE error send failed", e);
                        }
                    }
                }
        );
    }

    @Transactional
    public void streamUploadNote(String content, String fileName, String modelType, SseEmitter emitter) {
        log.info("流式上传笔记 [length={}, model={}]", content.length(), modelType);

        String title = generateTitle(content);
        ReviewNote note = ReviewNote.builder()
                .title(title)
                .content(content)
                .filePath(fileName)
                .questionCount(0)
                .build();
        note = noteRepository.save(note);
        Long noteId = note.getId();

        streamGenerateQuestions(content, noteId, modelType, emitter);
    }

    public void streamGenerateQuestions(String content, Long noteId, String modelType, SseEmitter emitter) {
        log.info("流式出题开始 [noteId={}, length={}, model={}]", noteId, content.length(), modelType);

        String prompt = String.format(GENERATE_PROMPT_TEMPLATE, content);
        StreamingChatModel model = selectStreamingModel(modelType);
        StringBuilder fullResponse = new StringBuilder();

        model.chat(
                ChatRequest.builder()
                        .messages(List.of(UserMessage.from(prompt)))
                        .build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        fullResponse.append(partialResponse);
                        try {
                            emitter.send(SseEmitter.event().name("token").data(partialResponse));
                        } catch (IOException e) {
                            log.error("SSE token send failed", e);
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        String llmResponse = fullResponse.toString();
                        log.info("AI 流式出题完成 [noteId={}, model={}]: length={}", noteId, modelType, llmResponse.length());

                        List<ReviewQuestion> questions;
                        try {
                            List<Map<String, Object>> parsed = parseQuestionResult(llmResponse);
                            questions = new ArrayList<>();
                            for (Map<String, Object> qm : parsed) {
                                ReviewQuestion q = ReviewQuestion.builder()
                                        .noteId(noteId)
                                        .questionText((String) qm.getOrDefault("question", ""))
                                        .correctAnswer((String) qm.getOrDefault("correctAnswer", ""))
                                        .questionType((String) qm.getOrDefault("type", "识记型"))
                                        .category((String) qm.getOrDefault("category", ""))
                                        .build();
                                questions.add(questionRepository.save(q));
                            }
                        } catch (Exception e) {
                            log.warn("流式出题解析失败 [noteId={}]", noteId, e);
                            questions = List.of();
                        }

                        // update question count
                        ReviewNote note = noteRepository.findById(noteId).orElse(null);
                        if (note != null) {
                            note.setQuestionCount(questions.size());
                            noteRepository.save(note);
                        }

                        try {
                            Map<String, Object> result = new HashMap<>();
                            result.put("noteId", noteId);
                            result.put("questionCount", questions.size());

                            List<Map<String, Object>> questionList = new ArrayList<>();
                            for (int i = 0; i < questions.size(); i++) {
                                ReviewQuestion q = questions.get(i);
                                Map<String, Object> qm = new HashMap<>();
                                qm.put("index", i);
                                qm.put("question", q.getQuestionText());
                                qm.put("type", q.getQuestionType());
                                questionList.add(qm);
                            }
                            result.put("questions", questionList);

                            String resultJson = objectMapper.writeValueAsString(result);
                            emitter.send(SseEmitter.event().name("done").data(resultJson));
                            emitter.complete();
                        } catch (IOException e) {
                            log.error("SSE complete send failed", e);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("流式出题 LLM 调用失败 [noteId={}, model={}]", noteId, modelType, error);
                        try {
                            emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                            emitter.complete();
                        } catch (IOException e) {
                            log.error("SSE error send failed", e);
                        }
                    }
                }
        );
    }

    public List<Map<String, Object>> listNotes() {
        List<ReviewNote> notes = noteRepository.findAllByOrderByCreatedAtDesc();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReviewNote note : notes) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", note.getId());
            item.put("title", note.getTitle());
            item.put("category", note.getCategory());
            item.put("questionCount", note.getQuestionCount());
            item.put("createdAt", note.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> getNoteDetail(Long noteId) {
        ReviewNote note = noteRepository.findById(noteId)
                .orElseThrow(() -> new IllegalArgumentException("笔记不存在: " + noteId));
        List<ReviewQuestion> questions = questionRepository.findByNoteId(noteId);
        List<ReviewSession> sessions = sessionRepository.findByNoteIdOrderByCreatedAtDesc(noteId);

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> noteMap = new HashMap<>();
        noteMap.put("id", note.getId());
        noteMap.put("title", note.getTitle() != null ? note.getTitle() : "");
        noteMap.put("content", note.getContent() != null ? note.getContent() : "");
        noteMap.put("category", note.getCategory() != null ? note.getCategory() : "");
        noteMap.put("questionCount", note.getQuestionCount());
        noteMap.put("createdAt", note.getCreatedAt());
        result.put("note", noteMap);

        List<Map<String, Object>> questionList = new ArrayList<>();
        for (ReviewQuestion q : questions) {
            Map<String, Object> qm = new HashMap<>();
            qm.put("id", q.getId());
            qm.put("question", q.getQuestionText());
            qm.put("type", q.getQuestionType());
            qm.put("category", q.getCategory());
            qm.put("correctAnswer", q.getCorrectAnswer());
            questionList.add(qm);
        }
        result.put("questions", questionList);

        List<Map<String, Object>> sessionList = new ArrayList<>();
        for (ReviewSession s : sessions) {
            Map<String, Object> sm = new HashMap<>();
            sm.put("id", s.getId());
            sm.put("totalScore", s.getTotalScore());
            sm.put("createdAt", s.getCreatedAt());
            sessionList.add(sm);
        }
        result.put("sessions", sessionList);

        return result;
    }

    @Transactional
    public void deleteNote(Long noteId) {
        if (!noteRepository.existsById(noteId)) {
            throw new IllegalArgumentException("笔记不存在: " + noteId);
        }
        questionRepository.deleteByNoteId(noteId);
        sessionRepository.findByNoteIdOrderByCreatedAtDesc(noteId)
                .forEach(s -> sessionRepository.delete(s));
        noteRepository.deleteById(noteId);
    }

    private ChatModel selectModel(String modelType) {
        return switch (modelType != null ? modelType.toLowerCase() : "") {
            case "deepseek" -> deepseekChatModel;
            default -> ollamaChatModel;
        };
    }

    private StreamingChatModel selectStreamingModel(String modelType) {
        return switch (modelType != null ? modelType.toLowerCase() : "") {
            case "deepseek" -> deepseekStreamingChatModel;
            default -> ollamaStreamingChatModel;
        };
    }

    private List<ReviewQuestion> generateQuestions(String content, Long noteId, String modelType) {
        log.info("用户笔记 [noteId={}, length={}, model={}]", noteId, content.length(), modelType);
        String prompt = String.format(GENERATE_PROMPT_TEMPLATE, content);
        ChatModel model = selectModel(modelType);
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from(prompt)))
                .build());
        String llmResponse = response.aiMessage().text();
        log.info("AI 出题结果 [noteId={}, model={}]: return length={}", noteId, modelType, llmResponse.length());

        List<Map<String, Object>> parsed = parseQuestionResult(llmResponse);
        List<ReviewQuestion> questions = new ArrayList<>();

        for (Map<String, Object> qm : parsed) {
            ReviewQuestion q = ReviewQuestion.builder()
                    .noteId(noteId)
                    .questionText((String) qm.getOrDefault("question", ""))
                    .correctAnswer((String) qm.getOrDefault("correctAnswer", ""))
                    .questionType((String) qm.getOrDefault("type", "识记型"))
                    .category((String) qm.getOrDefault("category", ""))
                    .build();
            questions.add(questionRepository.save(q));
        }

        return questions;
    }

    private List<Map<String, Object>> parseQuestionResult(String llmResponse) {
        String json = extractJson(llmResponse);
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("解析出题结果失败，返回空列表: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> parseGradingResult(String llmResponse, int expectedCount) {
        String json = extractJson(llmResponse);
        try {
            List<Map<String, Object>> results = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            if (results.size() != expectedCount) {
                log.warn("批改结果数量({})与预期({})不符，尝试修正", results.size(), expectedCount);
            }
            return results;
        } catch (Exception e) {
            log.warn("解析批改结果失败: {}", e.getMessage());
            List<Map<String, Object>> fallback = new ArrayList<>();
            for (int i = 0; i < expectedCount; i++) {
                Map<String, Object> item = new HashMap<>();
                item.put("questionIndex", i);
                item.put("score", 0);
                item.put("correct", false);
                item.put("feedback", "批改解析失败");
                item.put("correctAnswer", "");
                fallback.add(item);
            }
            return fallback;
        }
    }

    private String extractJson(String text) {
        text = text.trim();
        // strip markdown code fences
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline, lastFence).trim();
            }
        }
        // find the outermost JSON array
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        // repair missing commas between JSON array elements
        text = text.replaceAll("\\}\\s*\\{", "},{");
        text = text.replaceAll("\\]\\s*\\[", "],[");
        text = text.replaceAll("\"\\s*\n\\s*\"", "\",\"");
        return text;
    }

    private String generateTitle(String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) return "未命名笔记";
        int end = Math.min(trimmed.length(), 40);
        String title = trimmed.substring(0, end).replaceAll("\\s+", " ");
        return title.length() > 40 ? title + "..." : title;
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return "{}";
        }
    }
}
