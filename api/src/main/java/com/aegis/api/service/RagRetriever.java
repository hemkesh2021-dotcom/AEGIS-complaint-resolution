package com.aegis.api.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Phase 4a — HYBRID retrieval: semantic (pgvector, local ONNX embeddings) and
 * keyword (in-memory BM25) rankings merged with reciprocal-rank fusion.
 *
 * <p>Embeddings catch paraphrase ("money vanished from my account"); BM25
 * catches exact regulatory vocabulary ("Regulation E", "provisional credit").
 * Passages surfaced by both retrievers rank highest. Every retrieval also
 * returns {@link Citation}s — the exact passages that ground the draft, stored
 * on the case and shown to the operator.
 *
 * <p>Fails soft in layers: if pgvector is down we degrade to keyword-only; if
 * everything fails, drafting proceeds with empty context.
 */
@Service
public class RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);
    private static final int RRF_K = 60;
    private static final int SNIPPET_CHARS = 220;

    private final VectorStore vectorStore;
    private final Bm25Index bm25;

    public RagRetriever(VectorStore vectorStore, Bm25Index bm25) {
        this.vectorStore = vectorStore;
        this.bm25 = bm25;
    }

    /** One grounding passage: which source, how it was found, and a preview. */
    public record Citation(String source, String category, String via, String snippet) {
    }

    /** Retrieved passages joined into one context block, plus ids, categories, citations. */
    public record RetrievedContext(String text, List<String> sources, List<String> categories,
                                   List<Citation> citations) {
        public boolean isEmpty() {
            return text == null || text.isBlank();
        }
    }

    public RetrievedContext retrieve(String query, int topK) {
        try {
            // 1 · semantic ranking (may fail — degrade to keyword-only)
            List<Document> vdocs = List.of();
            try {
                List<Document> found = vectorStore.similaritySearch(
                        SearchRequest.builder().query(query).topK(topK).build());
                if (found != null) {
                    vdocs = found;
                }
            } catch (Exception e) {
                log.warn("Vector retrieval failed ({}); degrading to keyword-only.", e.getMessage());
            }

            // 2 · keyword ranking (in-memory, always available)
            List<Bm25Index.Hit> kdocs = bm25.search(query, topK);

            // 3 · collect passage info per id, preserving each ranking
            Map<String, String[]> info = new LinkedHashMap<>(); // id → [text, category]
            List<String> vRank = new ArrayList<>(), kRank = new ArrayList<>();
            for (Document d : vdocs) {
                String id = String.valueOf(d.getMetadata().getOrDefault("source", "regulation"));
                vRank.add(id);
                info.putIfAbsent(id, new String[]{
                        d.getText() == null ? "" : d.getText(),
                        String.valueOf(d.getMetadata().getOrDefault("category", ""))});
            }
            for (Bm25Index.Hit h : kdocs) {
                kRank.add(h.passage().id());
                info.putIfAbsent(h.passage().id(),
                        new String[]{h.passage().text(), h.passage().category()});
            }
            if (info.isEmpty()) {
                return new RetrievedContext("", List.of(), List.of(), List.of());
            }

            // 4 · reciprocal-rank fusion → final top-K
            Map<String, Double> fused = Fusion.rrf(RRF_K, List.of(vRank, kRank));
            List<String> top = fused.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(topK)
                    .map(Map.Entry::getKey)
                    .toList();

            StringBuilder text = new StringBuilder();
            List<String> categories = new ArrayList<>();
            List<Citation> citations = new ArrayList<>();
            for (String id : top) {
                String[] meta = info.get(id);
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(meta[0]);
                if (!meta[1].isBlank() && !categories.contains(meta[1])) {
                    categories.add(meta[1]);
                }
                String via = vRank.contains(id) && kRank.contains(id) ? "hybrid"
                        : vRank.contains(id) ? "semantic" : "keyword";
                String snippet = meta[0].length() > SNIPPET_CHARS
                        ? meta[0].substring(0, SNIPPET_CHARS).trim() + "…" : meta[0];
                citations.add(new Citation(id, meta[1], via, snippet));
            }
            return new RetrievedContext(text.toString(), top, categories, citations);
        } catch (Exception e) {
            log.warn("Retrieval failed ({}); proceeding with empty context.", e.getMessage());
            return new RetrievedContext("", List.of(), List.of(), List.of());
        }
    }
}
