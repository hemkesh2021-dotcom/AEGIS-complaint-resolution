package com.aegis.api.service;

import com.aegis.api.dto.ComplaintResponse.Compliance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Phase 3 — deterministic compliance rules (ported from v1 compliance.py).
 * Computes acknowledgement/resolution deadlines with business-day math and
 * scans the narrative for risk flags. No network calls.
 */
@Service
public class ComplianceEngine {

    private static final DateTimeFormatter ISO_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final Map<String, Pattern> RISK_PATTERNS = buildRiskPatterns();

    /** A regulation rule row loaded from regulations.json (snake_case keys). */
    public record Rule(String category, int ackDays, int resolutionDays, String clause, int escalationThresholdDays) {
    }

    private List<Rule> rules = List.of();

    @PostConstruct
    void load() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try (InputStream in = new ClassPathResource("regulations.json").getInputStream()) {
            rules = Arrays.asList(mapper.readValue(in, Rule[].class));
        }
    }

    public Compliance compute(String category, String receivedAt, String text) {
        Rule rule = findRule(category);
        LocalDateTime received = parse(receivedAt);
        LocalDateTime ackDue = addBusinessDays(received, rule.ackDays());
        LocalDateTime resolutionDue = addBusinessDays(received, rule.resolutionDays());
        long daysLeft = Duration.between(LocalDateTime.now(), resolutionDue).toDays();

        List<String> flags = new ArrayList<>();
        if (daysLeft <= rule.escalationThresholdDays()) {
            flags.add("deadline_near");
        }
        String lc = category == null ? "" : category.toLowerCase();
        if ((lc.contains("fraud") || lc.contains("unauthorized")) && !flags.contains("financial_risk")) {
            flags.add("financial_risk");
        }
        String narrative = text == null ? "" : text;
        for (var e : RISK_PATTERNS.entrySet()) {
            if (e.getValue().matcher(narrative).find() && !flags.contains(e.getKey())) {
                flags.add(e.getKey());
            }
        }

        return new Compliance(
                category,
                rule.clause(),
                ackDue.format(ISO_MIN),
                resolutionDue.format(ISO_MIN),
                flags,
                !flags.isEmpty());
    }

    private Rule findRule(String category) {
        String norm = category == null ? "" : category.trim().toLowerCase();
        for (Rule r : rules) {
            if (r.category().trim().toLowerCase().equals(norm)) {
                return r;
            }
        }
        return new Rule(category, 2, 10, "General complaint handling policy", 2);
    }

    private LocalDateTime parse(String iso) {
        if (iso == null || iso.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(iso);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private LocalDateTime addBusinessDays(LocalDateTime start, int days) {
        LocalDateTime cur = start;
        int added = 0;
        while (added < days) {
            cur = cur.plusDays(1);
            DayOfWeek d = cur.getDayOfWeek();
            if (d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return cur;
    }

    private static Map<String, Pattern> buildRiskPatterns() {
        Map<String, List<String>> kw = new LinkedHashMap<>();
        kw.put("financial_risk", List.of("fraud", "fraudulent", "unauthorized", "scam", "stolen",
                "identity theft", "hacked", "phishing", "money was taken", "without my consent"));
        kw.put("legal_risk", List.of("lawsuit", "i will sue", "my attorney", "my lawyer",
                "legal action", "take legal", "litigation", "small claims"));
        kw.put("harassment", List.of("harass", "threaten", "threatening", "abusive", "calling me every"));
        kw.put("vulnerable_customer", List.of("elderly", "senior citizen", "disabled", "veteran", "deceased", "widow"));

        Map<String, Pattern> out = new LinkedHashMap<>();
        for (var e : kw.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) {
                    sb.append('|');
                }
                sb.append(Pattern.quote(e.getValue().get(i)));
            }
            out.put(e.getKey(), Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE));
        }
        return out;
    }
}
