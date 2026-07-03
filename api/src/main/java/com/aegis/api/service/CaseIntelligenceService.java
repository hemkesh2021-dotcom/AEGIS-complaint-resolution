package com.aegis.api.service;

import com.aegis.api.entity.CaseRecord;
import com.aegis.api.repo.CaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Product intelligence over the case base — all computed from data the
 * pipeline already produces, no new models:
 *
 * <ul>
 *   <li><b>Similar cases</b> — every complaint is embedded at intake (same local
 *       ONNX model as RAG); cosine similarity over recent cases surfaces
 *       near-duplicates and related history for the operator.</li>
 *   <li><b>Repeat complainants</b> — case counts per customer email.</li>
 *   <li><b>Insights</b> — trends, SLA watchlist, risk-flag mix, and how often
 *       operators actually edit the AI's drafts.</li>
 * </ul>
 */
@Service
public class CaseIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(CaseIntelligenceService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ISO_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final ObjectProvider<EmbeddingModel> embeddingProvider;
    private final CaseRepository cases;

    public CaseIntelligenceService(ObjectProvider<EmbeddingModel> embeddingProvider,
                                   CaseRepository cases) {
        this.embeddingProvider = embeddingProvider;
        this.cases = cases;
    }

    /** Embed a complaint for similarity search (fail-soft: null = no embedding). */
    public String embedJson(String text) {
        EmbeddingModel model = embeddingProvider.getIfAvailable();
        if (model == null || text == null || text.isBlank()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(model.embed(text));
        } catch (Exception e) {
            log.warn("case embedding failed: {}", e.getMessage());
            return null;
        }
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private float[] parse(String json) {
        try {
            return json == null ? null : JSON.readValue(json, float[].class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Top-K most similar past cases (cosine over stored embeddings). */
    public List<Map<String, Object>> similar(CaseRecord target, int topK) {
        float[] tv = parse(target.getEmbedding());
        if (tv == null) {
            tv = parse(embedJson(target.getComplaintText()));
        }
        if (tv == null) {
            return List.of();
        }
        record Scored(CaseRecord c, double s) {
        }
        List<Scored> scored = new ArrayList<>();
        for (CaseRecord c : cases.findTop500ByOrderByCreatedAtDesc()) {
            if (c.getComplaintId().equals(target.getComplaintId())) {
                continue;
            }
            float[] v = parse(c.getEmbedding());
            if (v == null || v.length != tv.length) {
                continue;
            }
            double s = cosine(tv, v);
            if (s > 0.5) {
                scored.add(new Scored(c, s));
            }
        }
        scored.sort((x, y) -> Double.compare(y.s(), x.s()));
        String email = target.getCustomerEmail();
        return scored.stream().limit(topK).map(sc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("complaintId", sc.c().getComplaintId());
            m.put("category", sc.c().getCategory());
            m.put("status", sc.c().getStatus());
            m.put("urgency", sc.c().getUrgency());
            m.put("similarity", Math.round(sc.s() * 100) / 100.0);
            m.put("possibleDuplicate", sc.s() > 0.92);
            m.put("sameCustomer", email != null && !email.isBlank()
                    && email.equalsIgnoreCase(sc.c().getCustomerEmail()));
            m.put("snippet", snippet(sc.c().getComplaintText()));
            return m;
        }).toList();
    }

    public long customerCaseCount(String email) {
        return (email == null || email.isBlank()) ? 0 : cases.countByCustomerEmailIgnoreCase(email);
    }

    /** Aggregates for the Insights dashboard. */
    public Map<String, Object> insights() {
        List<CaseRecord> all = cases.findTop500ByOrderByCreatedAtDesc();
        LocalDateTime now = LocalDateTime.now();

        Map<String, Integer> byCategory = new HashMap<>();
        Map<String, Integer> byUrgency = new HashMap<>();
        Map<String, Integer> riskFlags = new HashMap<>();
        Map<LocalDate, Integer> perDay = new TreeMap<>();
        LocalDate today = LocalDate.now();
        for (int i = 13; i >= 0; i--) {
            perDay.put(today.minusDays(i), 0);
        }

        int inReview = 0, sent = 0, escalated = 0;
        int qualityN = 0, unedited = 0;
        double simSum = 0;
        int simN = 0;
        List<Map<String, Object>> slaWatch = new ArrayList<>();
        Map<String, long[]> repeat = new HashMap<>(); // email → [count]

        for (CaseRecord c : all) {
            byCategory.merge(nz(c.getCategory(), "Unknown"), 1, Integer::sum);
            byUrgency.merge(nz(c.getUrgency(), "NORMAL"), 1, Integer::sum);
            if (c.isEscalate()) {
                escalated++;
            }
            boolean isSent = "SENT".equals(c.getStatus());
            if (isSent) {
                sent++;
            } else {
                inReview++;
            }
            if (c.getRiskFlags() != null && !c.getRiskFlags().isBlank()) {
                for (String f : c.getRiskFlags().split(",")) {
                    if (!f.isBlank()) {
                        riskFlags.merge(f.trim(), 1, Integer::sum);
                    }
                }
            }
            if (c.getCreatedAt() != null) {
                LocalDate d = c.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate();
                perDay.computeIfPresent(d, (k, v) -> v + 1);
            }
            if (c.getOperatorEdited() != null) {
                qualityN++;
                if (!c.getOperatorEdited()) {
                    unedited++;
                }
                if (c.getEditSimilarity() != null) {
                    simSum += c.getEditSimilarity();
                    simN++;
                }
            }
            if (!isSent && c.getResolutionDue() != null) {
                try {
                    LocalDateTime due = LocalDateTime.parse(c.getResolutionDue(), ISO_MIN);
                    if (due.isBefore(now.plusDays(5))) {
                        Map<String, Object> w = new LinkedHashMap<>();
                        w.put("complaintId", c.getComplaintId());
                        w.put("category", c.getCategory());
                        w.put("urgency", c.getUrgency());
                        w.put("resolutionDue", c.getResolutionDue());
                        w.put("overdue", due.isBefore(now));
                        slaWatch.add(w);
                    }
                } catch (Exception ignored) {
                    // unparseable due date — skip from watchlist
                }
            }
            if (c.getCustomerEmail() != null && !c.getCustomerEmail().isBlank()) {
                repeat.computeIfAbsent(c.getCustomerEmail().toLowerCase(), k -> new long[]{0})[0]++;
            }
        }

        slaWatch.sort((a, b) -> String.valueOf(a.get("resolutionDue"))
                .compareTo(String.valueOf(b.get("resolutionDue"))));

        List<Map<String, Object>> repeatCustomers = repeat.entrySet().stream()
                .filter(e -> e.getValue()[0] > 1)
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("customer", mask(e.getKey()));
                    m.put("cases", e.getValue()[0]);
                    return m;
                }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("inReview", inReview);
        out.put("sent", sent);
        out.put("escalationRate", all.isEmpty() ? 0 : Math.round(100.0 * escalated / all.size()) / 100.0 * 1.0);
        out.put("byCategory", sortDesc(byCategory));
        out.put("byUrgency", byUrgency);
        out.put("byDay", perDay.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("d", e.getKey().format(DateTimeFormatter.ofPattern("MM-dd")));
            m.put("v", e.getValue());
            return m;
        }).toList());
        out.put("riskFlags", sortDesc(riskFlags));
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("scoredCases", qualityN);
        quality.put("unedited", unedited);
        quality.put("uneditedPct", qualityN == 0 ? null : Math.round(100.0 * unedited / qualityN));
        quality.put("avgEditSimilarity", simN == 0 ? null : Math.round(simSum / simN * 100) / 100.0);
        out.put("draftQuality", quality);
        out.put("slaWatch", slaWatch.stream().limit(8).toList());
        out.put("repeatCustomers", repeatCustomers);
        return out;
    }

    private static List<Map<String, Object>> sortDesc(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("k", e.getKey());
                    m.put("v", e.getValue());
                    return m;
                }).toList();
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 150 ? s.substring(0, 150).trim() + "…" : s;
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0))
                : email.charAt(0) + "***" + email.substring(at);
    }

    private static String nz(String s, String dflt) {
        return s == null || s.isBlank() ? dflt : s;
    }
}
