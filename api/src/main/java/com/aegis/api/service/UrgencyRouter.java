package com.aegis.api.service;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Routes a processed complaint into an urgency tier using signals AEGIS already
 * produces — high-severity risk flags, the escalation decision, and confidence.
 * No new model; this just prioritises the CS queue.
 */
@Service
public class UrgencyRouter {

    private static final Set<String> HIGH_SEVERITY =
            Set.of("financial_risk", "legal_risk", "harassment", "vulnerable_customer");

    /** CRITICAL (high-severity flag) · PRIORITIZED (escalated) · NORMAL. */
    public String tier(List<String> riskFlags, double confidence, boolean escalate) {
        if (riskFlags != null && riskFlags.stream().anyMatch(HIGH_SEVERITY::contains)) {
            return "CRITICAL";
        }
        if (escalate) {
            return "PRIORITIZED";
        }
        return "NORMAL";
    }
}
