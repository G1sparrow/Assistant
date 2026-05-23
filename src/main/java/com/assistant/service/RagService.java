package com.assistant.service;

import com.assistant.config.AssistantProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
public class RagService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".txt", ".md", ".json", ".xml");

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final AssistantProperties properties;

    public RagService(EmbeddingModel embeddingModel,
                      EmbeddingStore<TextSegment> embeddingStore,
                      AssistantProperties properties) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (properties.getRag().isEnabled()) {
            log.info("RAG 服务已启用, 文档目录: {}", properties.getRag().getDocumentDir());
            loadDocumentsFromDirectory();
        } else {
            log.info("RAG 服务未启用");
        }
    }

    public String search(String query) {
        if (!properties.getRag().isEnabled()) {
            return "RAG 知识库功能未启用";
        }
        try {
            var queryEmbedding = embeddingModel.embed(query).content();
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(properties.getRag().getMaxResults())
                    .minScore(properties.getRag().getMinScore())
                    .build();

            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(searchRequest).matches();
            if (matches.isEmpty()) {
                return "在知识库中未找到相关信息。";
            }

            StringBuilder context = new StringBuilder("以下是知识库中与问题相关的内容:\n\n");
            for (int i = 0; i < matches.size(); i++) {
                context.append("--- 参考 ").append(i + 1).append(" ---\n")
                       .append(matches.get(i).embedded().text()).append("\n\n");
            }
            context.append("请基于以上内容回答用户的问题。如果内容不足以回答问题，请如实告知。");

            log.debug("RAG 搜索 '{}' 找到 {} 条结果", query, matches.size());
            return context.toString();
        } catch (Exception e) {
            log.error("RAG 搜索失败", e);
            return "知识库检索出错: " + e.getMessage();
        }
    }

    public void ingestText(String text, String source) {
        try {
            TextSegment segment = TextSegment.from(text);
            embeddingStore.add(embeddingModel.embed(text).content(), segment);
            log.info("已导入文本 (来源: {}): {}...", source, text.substring(0, Math.min(50, text.length())));
        } catch (Exception e) {
            log.error("导入文本失败", e);
        }
    }

    public void ingestTexts(List<String> texts, String sourcePrefix) {
        for (int i = 0; i < texts.size(); i++) {
            ingestText(texts.get(i), sourcePrefix + " #" + (i + 1));
        }
    }

    public Map<String, Object> ingestFile(String fileName, byte[] fileBytes) {
        Map<String, Object> result = new HashMap<>();
        if (!isSupportedFile(fileName)) {
            result.put("success", false);
            result.put("message", "不支持的文件格式，仅支持 .txt .md .json .xml");
            return result;
        }
        try {
            String content = new String(fileBytes, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                result.put("success", false);
                result.put("message", "文件内容为空");
                return result;
            }
            Path target = resolveTargetPath(fileName);
            Files.write(target, fileBytes);
            ingestText(content, target.getFileName().toString());

            log.info("知识库文档上传成功: {}", target.getFileName());
            result.put("success", true);
            result.put("message", "文档 " + target.getFileName() + " 已导入知识库");
            result.put("fileName", target.getFileName().toString());
        } catch (Exception e) {
            log.error("导入文件失败: {}", fileName, e);
            result.put("success", false);
            result.put("message", "导入失败: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("enabled", properties.getRag().isEnabled());
        stats.put("documentDir", properties.getRag().getDocumentDir());
        stats.put("embeddingModel", "AllMiniLmL6V2");
        stats.put("documentCount", countDocuments());
        return stats;
    }

    private void loadDocumentsFromDirectory() {
        Path dirPath = Paths.get(properties.getRag().getDocumentDir());
        if (!Files.exists(dirPath)) {
            createDirectory(dirPath);
            return;
        }
        try (Stream<Path> files = Files.list(dirPath)) {
            long count = files.filter(this::isSupportedFile)
                    .map(this::ingestFromPath)
                    .filter(success -> success)
                    .count();
            log.info("RAG 初始化完成, 共加载 {} 个文档", count);
        } catch (IOException e) {
            log.error("扫描文档目录失败", e);
        }
    }

    private Path resolveTargetPath(String fileName) throws IOException {
        Path docDir = Paths.get(properties.getRag().getDocumentDir());
        if (!Files.exists(docDir)) {
            Files.createDirectories(docDir);
        }
        Path target = docDir.resolve(fileName);
        if (Files.exists(target)) {
            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            String ext = dot > 0 ? fileName.substring(dot) : "";
            target = docDir.resolve(base + "_" + System.currentTimeMillis() + ext);
        }
        return target;
    }

    private boolean ingestFromPath(Path file) {
        try {
            String content = Files.readString(file);
            if (!content.isBlank()) {
                ingestText(content, file.getFileName().toString());
                return true;
            }
        } catch (IOException e) {
            log.warn("读取文档失败: {}", file);
        }
        return false;
    }

    private long countDocuments() {
        Path dir = Paths.get(properties.getRag().getDocumentDir());
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean isSupportedFile(Path file) {
        return isSupportedFile(file.getFileName().toString());
    }

    private boolean isSupportedFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && SUPPORTED_EXTENSIONS.contains(fileName.substring(dot).toLowerCase());
    }

    private void createDirectory(Path dirPath) {
        try {
            Files.createDirectories(dirPath);
            log.info("创建文档目录: {}", dirPath);
        } catch (IOException e) {
            log.warn("无法创建文档目录: {}", dirPath);
        }
    }
}
