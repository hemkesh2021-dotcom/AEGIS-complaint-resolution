package com.aegis.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Offline ingestion — loads the regulation/precedent knowledge base into the
 * pgvector store on startup (once). Idempotent: if the store already has data it
 * skips, so restarts don't duplicate passages.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        try {
            List<Document> existing = vectorStore.similaritySearch(
                    SearchRequest.builder().query("complaint regulation").topK(1).build());
            if (existing != null && !existing.isEmpty()) {
                log.info("Knowledge base already present — skipping ingestion.");
                return;
            }

            List<Document> docs = new ArrayList<>();
            ObjectMapper mapper = new ObjectMapper();
            try (InputStream in = new ClassPathResource("knowledge/kb.json").getInputStream()) {
                JsonNode arr = mapper.readTree(in);
                for (JsonNode n : arr) {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("source", n.path("id").asText("kb"));
                    meta.put("category", n.path("category").asText(""));
                    docs.add(new Document(n.path("text").asText(""), meta));
                }
            }
            vectorStore.add(docs);
            log.info("Ingested {} knowledge-base passages into pgvector.", docs.size());
        } catch (Exception e) {
            log.warn("Knowledge-base ingestion failed ({}); retrieval will return empty context.", e.getMessage());
        }
    }
}
