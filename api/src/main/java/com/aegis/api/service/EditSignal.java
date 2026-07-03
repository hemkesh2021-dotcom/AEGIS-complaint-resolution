package com.aegis.api.service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The learning-loop signal: how much did a human change the AI's draft?
 *
 * <p>Every approval is implicit feedback — an unedited send is a positive
 * label, a heavy rewrite is a negative one. We quantify it as a Jaccard
 * similarity over word multisets (cheap, order-insensitive, robust on long
 * letters where true edit distance would be quadratic).
 */
public final class EditSignal {

    private EditSignal() {
    }

    /** @return similarity in [0,1]: 1.0 = sent unchanged, 0.0 = completely rewritten. */
    public static double similarity(String draft, String fin) {
        if (draft == null || fin == null) {
            return (draft == null && fin == null) ? 1.0 : 0.0;
        }
        Map<String, Integer> a = bag(draft), b = bag(fin);
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        long intersection = 0, union = 0;
        for (var e : a.entrySet()) {
            int other = b.getOrDefault(e.getKey(), 0);
            intersection += Math.min(e.getValue(), other);
            union += Math.max(e.getValue(), other);
        }
        for (var e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                union += e.getValue();
            }
        }
        return union == 0 ? 1.0 : (double) intersection / union;
    }

    private static Map<String, Integer> bag(String text) {
        Map<String, Integer> m = new HashMap<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (!w.isBlank()) {
                m.merge(w, 1, Integer::sum);
            }
        }
        return m;
    }
}
