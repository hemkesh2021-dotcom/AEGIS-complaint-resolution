package com.aegis.api.service;

import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Phase 4a — retrieval. Embeds the complaint (local ONNX model) and pulls the
 * most similar regulation/precedent passages from the pgvector store. Fails soft:
 * on any error it returns empty context so drafting still proceeds.
 */
@Service
public class RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);

    private final VectorStore vectorStore;

    public RagRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** Retrieved passages joined into one context block, plus source ids and categories. */
    public record RetrievedContext(String text, List<String> sources, List<String> categories) {
        public boolean isEmpty() {
            return text == null || text.isBlank();
        }
    }

    public RetrievedContext retrieve(String query, int topK) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(topK).build());
            if (docs == null || docs.isEmpty()) {
                return new RetrievedContext("", List.of(), List.of());
            }
            String text = docs.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
            List<String> sources = docs.stream()
                    .map(d -> String.valueOf(d.getMetadata().getOrDefault("source", "regulation")))
                    .distinct().toList();
            List<String> categories = docs.stream()
                    .map(d -> String.valueOf(d.getMetadata().getOrDefault("category", "")))
                    .filter(s -> !s.isBlank())
                    .distinct().toList();
            return new RetrievedContext(text, sources, categories);
        } catch (Exception e) {
            log.warn("Retrieval failed ({}); proceeding with empty context.", e.getMessage());
            return new RetrievedContext("", List.of(), List.of());
        }
    }
}
