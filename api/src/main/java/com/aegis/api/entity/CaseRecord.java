package com.aegis.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One row per processed complaint — the full pipeline outcome. */
@Entity
@Table(name = "cases")
public class CaseRecord {

    @Id
    private String complaintId;

    /** High-entropy customer tracking token — the only key the public status endpoint accepts. */
    @Column(unique = true)
    private String trackingToken;

    private String customerName;

    @Column(columnDefinition = "text")
    private String complaintText;

    /** Newline-separated grounding-check findings on the AI draft (null = clean). */
    @Column(columnDefinition = "text")
    private String draftWarnings;

    private String category;
    private double confidence;

    @Column(columnDefinition = "text")
    private String clause;

    private String ackDue;
    private String resolutionDue;
    private String riskFlags; // comma-separated

    private boolean escalate;
    private String escalationLevel;

    @Column(columnDefinition = "text")
    private String escalationReason;

    @Column(columnDefinition = "text")
    private String draftSubject;

    @Column(columnDefinition = "text")
    private String draftBody;

    private String status;   // IN_REVIEW → SENT
    private String urgency;  // CRITICAL / PRIORITIZED / NORMAL
    private String channel;  // WEB / CONSOLE (EMAIL later)

    @Column(columnDefinition = "text")
    private String finalSubject;

    @Column(columnDefinition = "text")
    private String finalBody;

    @Column(columnDefinition = "text")
    private String finalSummary;

    private Instant createdAt;

    public CaseRecord() {
    }

    public String getComplaintId() { return complaintId; }
    public void setComplaintId(String v) { this.complaintId = v; }

    public String getTrackingToken() { return trackingToken; }
    public void setTrackingToken(String v) { this.trackingToken = v; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String v) { this.customerName = v; }

    public String getDraftWarnings() { return draftWarnings; }
    public void setDraftWarnings(String v) { this.draftWarnings = v; }

    public String getComplaintText() { return complaintText; }
    public void setComplaintText(String v) { this.complaintText = v; }

    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double v) { this.confidence = v; }

    public String getClause() { return clause; }
    public void setClause(String v) { this.clause = v; }

    public String getAckDue() { return ackDue; }
    public void setAckDue(String v) { this.ackDue = v; }

    public String getResolutionDue() { return resolutionDue; }
    public void setResolutionDue(String v) { this.resolutionDue = v; }

    public String getRiskFlags() { return riskFlags; }
    public void setRiskFlags(String v) { this.riskFlags = v; }

    public boolean isEscalate() { return escalate; }
    public void setEscalate(boolean v) { this.escalate = v; }

    public String getEscalationLevel() { return escalationLevel; }
    public void setEscalationLevel(String v) { this.escalationLevel = v; }

    public String getEscalationReason() { return escalationReason; }
    public void setEscalationReason(String v) { this.escalationReason = v; }

    public String getDraftSubject() { return draftSubject; }
    public void setDraftSubject(String v) { this.draftSubject = v; }

    public String getDraftBody() { return draftBody; }
    public void setDraftBody(String v) { this.draftBody = v; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public String getUrgency() { return urgency; }
    public void setUrgency(String v) { this.urgency = v; }

    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }

    public String getFinalSubject() { return finalSubject; }
    public void setFinalSubject(String v) { this.finalSubject = v; }

    public String getFinalBody() { return finalBody; }
    public void setFinalBody(String v) { this.finalBody = v; }

    public String getFinalSummary() { return finalSummary; }
    public void setFinalSummary(String v) { this.finalSummary = v; }
}
