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
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewService(
            ReviewNoteRepository noteRepository,
            ReviewQuestionRepository questionRepository,
            ReviewSessionRepository sessionRepository,
            @Qualifier("ollamaChatModel") ChatModel ollamaChatModel,
            @Qualifier("deepseekChatModel") ChatModel deepseekChatModel) {
        this.noteRepository = noteRepository;
        this.questionRepository = questionRepository;
        this.sessionRepository = sessionRepository;
        this.ollamaChatModel = ollamaChatModel;
        this.deepseekChatModel = deepseekChatModel;
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

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", session.getId());
        result.put("totalScore", totalScore);
        result.put("maxScore", 100);
        result.put("details", gradingResults);
        result.put("questionCount", questions.size());

        return result;
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
        result.put("note", Map.of(
                "id", note.getId(),
                "title", note.getTitle(),
                "content", note.getContent(),
                "category", note.getCategory(),
                "questionCount", note.getQuestionCount(),
                "createdAt", note.getCreatedAt()
        ));

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

    private List<ReviewQuestion> generateQuestions(String content, Long noteId, String modelType) {
        String prompt = String.format(GENERATE_PROMPT_TEMPLATE, content);
        ChatModel model = selectModel(modelType);
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from(prompt)))
                .build());
        String llmResponse = response.aiMessage().text();

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
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start, end).trim();
            }
        }
        if (text.startsWith("[")) {
            int end = text.lastIndexOf(']');
            if (end > 0) {
                text = text.substring(0, end + 1);
            }
        }
        // repair missing commas between JSON array elements
        text = text.replaceAll("\\}(\\s*)\\n(\\s*)\\{", "},$1\n$2{");
        text = text.replaceAll("\\](\\s*)\\n(\\s*)\\[", "],$1\n$2[");
        text = text.replaceAll("\"(\\s*)\\n(\\s*)\"", "\",$1\n$2\"");
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
