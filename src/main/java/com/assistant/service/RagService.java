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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * RAG (检索增强生成) 服务
 * 支持本地文档的导入、向量化存储和语义检索
 */
@Slf4j
@Service
public class RagService {

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

    /**
     * 语义搜索知识库
     */
    public String search(String query) {
        if (!properties.getRag().isEnabled()) {
            return "RAG 知识库功能未启用";
        }

        try {
            // 1. 将查询文本向量化
            var queryEmbedding = embeddingModel.embed(query).content();

            // 2. 构建搜索请求
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(properties.getRag().getMaxResults())
                    .minScore(properties.getRag().getMinScore())
                    .build();

            // 3. 在向量库中搜索最相似的内容
            var searchResult = embeddingStore.search(searchRequest);
            List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

            if (matches.isEmpty()) {
                return "在知识库中未找到相关信息。";
            }

            // 4. 组装结果
            StringBuilder context = new StringBuilder();
            context.append("以下是知识库中与问题相关的内容:\n\n");

            for (int i = 0; i < matches.size(); i++) {
                EmbeddingMatch<TextSegment> match = matches.get(i);
                context.append("--- 参考 ").append(i + 1).append(" ---\n");
                context.append(match.embedded().text()).append("\n\n");
            }

            context.append("请基于以上内容回答用户的问题。如果内容不足以回答问题，请如实告知。");

            log.debug("RAG 搜索 '{}' 找到 {} 条结果", query, matches.size());
            return context.toString();

        } catch (Exception e) {
            log.error("RAG 搜索失败", e);
            return "知识库检索出错: " + e.getMessage();
        }
    }

    /**
     * 导入文本到知识库
     */
    public void ingestText(String text, String source) {
        try {
            TextSegment segment = TextSegment.from(text);
            var embedding = embeddingModel.embed(text).content();
            embeddingStore.add(embedding, segment);
            log.info("已导入文本 (来源: {}): {}...", source, text.substring(0, Math.min(50, text.length())));
        } catch (Exception e) {
            log.error("导入文本失败", e);
        }
    }

    /**
     * 批量导入文档文本
     */
    public void ingestTexts(List<String> texts, String sourcePrefix) {
        for (int i = 0; i < texts.size(); i++) {
            ingestText(texts.get(i), sourcePrefix + " #" + (i + 1));
        }
    }

    /**
     * 从目录加载文档
     */
    private void loadDocumentsFromDirectory() {
        String docDir = properties.getRag().getDocumentDir();
        Path dirPath = Paths.get(docDir);

        if (!Files.exists(dirPath)) {
            try {
                Files.createDirectories(dirPath);
                log.info("创建文档目录: {}", docDir);
            } catch (IOException e) {
                log.warn("无法创建文档目录: {}", docDir);
            }
            return;
        }

        try {
            List<Path> docFiles = new ArrayList<>();
            Files.walkFileTree(dirPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.toString().toLowerCase();
                    if (fileName.endsWith(".txt") || fileName.endsWith(".md")
                            || fileName.endsWith(".json") || fileName.endsWith(".xml")) {
                        docFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            if (docFiles.isEmpty()) {
                log.info("文档目录中没有找到支持的文档文件");
                return;
            }

            int totalDocuments = 0;
            for (Path docFile : docFiles) {
                try {
                    String content = Files.readString(docFile);
                    if (!content.isBlank()) {
                        ingestText(content, docFile.getFileName().toString());
                        totalDocuments++;
                    }
                } catch (IOException e) {
                    log.warn("读取文档失败: {}", docFile);
                }
            }
            log.info("RAG 初始化完成, 共加载 {} 个文档", totalDocuments);

        } catch (IOException e) {
            log.error("扫描文档目录失败", e);
        }
    }

    /**
     * 获取知识库统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("enabled", properties.getRag().isEnabled());
        stats.put("documentDir", properties.getRag().getDocumentDir());
        stats.put("embeddingModel", "AllMiniLmL6V2");
        return stats;
    }
}
