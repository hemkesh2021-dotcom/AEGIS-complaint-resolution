package com.aegis.api.dto;

import java.util.List;

/** Full six-phase result returned to the caller. */
public record ComplaintResponse(
        String complaintId,
        Prediction prediction,
        Compliance compliance,
        Draft draft,
        List<Task> tasks,
        Escalation escalation,
        String urgency,
        String status,
        String channel) {

    /** Phase 2 — classifier output. */
    public record Prediction(String label, double confidence) {
    }

    /** Phase 3 — compliance rule result. */
    public record Compliance(
            String category,
            String clause,
            String ackDue,
            String resolutionDue,
            List<String> riskFlags,
            boolean escalate) {
    }

    /** Phase 4 — drafted reply. */
    public record Draft(String subject, String body, String source) {
    }

    /** Phase 5 — a generated action item. */
    public record Task(String task, String owner, String priority, String dueAt, String status) {
    }

    /** Phase 6 — escalation decision. */
    public record Escalation(boolean escalate, String level, String reason) {
    }
}
