package com.aegis.api.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal-rank fusion — the standard way to merge rankings from systems
 * whose scores aren't comparable (cosine distance vs BM25). Each ranking
 * contributes 1/(k + rank); items surfaced by BOTH retrievers rise to the top.
 */
public final class Fusion {

    private Fusion() {
    }

    /** @param k dampening constant (60 is the literature default) */
    public static Map<String, Double> rrf(int k, List<List<String>> rankings) {
        Map<String, Double> fused = new LinkedHashMap<>();
        for (List<String> ranking : rankings) {
            for (int r = 0; r < ranking.size(); r++) {
                fused.merge(ranking.get(r), 1.0 / (k + r + 1), Double::sum);
            }
        }
        return fused;
    }
}
