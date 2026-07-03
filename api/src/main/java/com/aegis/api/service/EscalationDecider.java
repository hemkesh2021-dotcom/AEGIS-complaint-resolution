package com.aegis.api.service;

import com.aegis.api.dto.ComplaintResponse.Escalation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Phase 6 — explainable escalation. Escalate on any risk flag OR low model
 * confidence, and always record the exact reason.
 */
@Service
public class EscalationDecider {

    private static final double LOW_CONFIDENCE = 0.55;

    public Escalation decide(List<String> riskFlags, double confidence) {
        boolean lowConfidence = confidence < LOW_CONFIDENCE;
        boolean escalate = !riskFlags.isEmpty() || lowConfidence;

        List<String> reasons = new ArrayList<>();
        if (!riskFlags.isEmpty()) {
            reasons.add("Risk flags: " + String.join(", ", riskFlags));
        }
        if (lowConfidence) {
            reasons.add(String.format("Low model confidence (%.0f%%)", confidence * 100));
        }
        String reason = reasons.isEmpty()
                ? "No risk flags; confidence within threshold"
                : String.join(" · ", reasons);

        return new Escalation(escalate, escalate ? "Senior Review" : "Normal", reason);
    }
}
