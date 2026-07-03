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

            // Incremental: ingest only passages the store doesn't have yet, so the
            // KB can grow without wiping the database (checked per source id).
            List<Document> missing = new ArrayList<>();
            for (Document d : docs) {
                String id = String.valueOf(d.getMetadata().get("source"));
                boolean present = false;
                try {
                    List<Document> hit = vectorStore.similaritySearch(SearchRequest.builder()
                            .query(d.getText()).topK(1)
                            .filterExpression("source == '" + id + "'").build());
                    present = hit != null && !hit.isEmpty();
                } catch (Exception e) {
                    // treat as missing — worst case we re-add one passage
                }
                if (!present) {
                    missing.add(d);
                }
            }
            if (missing.isEmpty()) {
                log.info("Knowledge base up to date ({} passages).", docs.size());
                return;
            }
            vectorStore.add(missing);
            log.info("Ingested {} new knowledge-base passages ({} already present).",
                    missing.size(), docs.size() - missing.size());
        } catch (Exception e) {
            log.warn("Knowledge-base ingestion failed ({}); retrieval will return empty context.", e.getMessage());
        }
    }
}
