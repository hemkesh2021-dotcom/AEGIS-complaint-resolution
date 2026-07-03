package com.aegis.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * In-memory BM25 over the regulation knowledge base — the keyword half of
 * hybrid retrieval. Embeddings are great at "sounds like" but miss exact
 * regulatory vocabulary ("Regulation Z", "provisional credit", "escrow");
 * BM25 is the opposite. Fused, they cover each other's blind spots.
 *
 * <p>The KB is small (dozens of passages), so a full in-memory index costs
 * microseconds per query and nothing to operate.
 */
@Service
public class Bm25Index {

    public record Passage(String id, String category, String text) {
    }

    public record Hit(Passage passage, double score) {
    }

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private List<Passage> passages = List.of();
    private List<Map<String, Integer>> termFreqs = List.of();
    private Map<String, Integer> docFreq = Map.of();
    private int[] docLen = new int[0];
    private double avgLen = 1.0;

    @PostConstruct
    void load() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = new ClassPathResource("knowledge/kb.json").getInputStream()) {
            JsonNode arr = mapper.readTree(in);
            List<Passage> ps = new ArrayList<>();
            for (JsonNode n : arr) {
                ps.add(new Passage(n.path("id").asText("kb"),
                        n.path("category").asText(""), n.path("text").asText("")));
            }
            index(ps);
        }
    }

    /** (Re)build the index — also the entry point for tests. */
    public void index(List<Passage> ps) {
        List<Map<String, Integer>> tfs = new ArrayList<>(ps.size());
        Map<String, Integer> df = new HashMap<>();
        int[] lens = new int[ps.size()];
        long total = 0;
        for (int i = 0; i < ps.size(); i++) {
            Map<String, Integer> tf = new HashMap<>();
            List<String> tokens = tokenize(ps.get(i).text());
            for (String t : tokens) {
                tf.merge(t, 1, Integer::sum);
            }
            tf.keySet().forEach(t -> df.merge(t, 1, Integer::sum));
            lens[i] = tokens.size();
            total += tokens.size();
            tfs.add(tf);
        }
        this.passages = List.copyOf(ps);
        this.termFreqs = tfs;
        this.docFreq = df;
        this.docLen = lens;
        this.avgLen = ps.isEmpty() ? 1.0 : Math.max(1.0, (double) total / ps.size());
    }

    public List<Hit> search(String query, int topK) {
        Set<String> terms = new LinkedHashSet<>(tokenize(query));
        int n = passages.size();
        if (terms.isEmpty() || n == 0) {
            return List.of();
        }
        double[] scores = new double[n];
        for (String term : terms) {
            Integer dfv = docFreq.get(term);
            if (dfv == null) {
                continue;
            }
            double idf = Math.log(1.0 + (n - dfv + 0.5) / (dfv + 0.5));
            for (int i = 0; i < n; i++) {
                Integer tf = termFreqs.get(i).get(term);
                if (tf == null) {
                    continue;
                }
                double denom = tf + K1 * (1 - B + B * docLen[i] / avgLen);
                scores[i] += idf * (tf * (K1 + 1)) / denom;
            }
        }
        return IntStream.range(0, n)
                .filter(i -> scores[i] > 0)
                .boxed()
                .sorted((a, b) -> Double.compare(scores[b], scores[a]))
                .limit(topK)
                .map(i -> new Hit(passages.get(i), scores[i]))
                .toList();
    }

    private static List<String> tokenize(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(w -> w.length() > 1)
                .toList();
    }
}
