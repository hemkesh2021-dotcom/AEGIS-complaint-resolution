package com.aegis.api.service;

import com.aegis.api.dto.ComplaintResponse;
import com.aegis.api.entity.AuditEvent;
import com.aegis.api.entity.CaseRecord;
import com.aegis.api.repo.AuditEventRepository;
import com.aegis.api.repo.CaseRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 7 — audit trail, persisted to Postgres.
 *
 * <p>Writes one {@link CaseRecord} (the full outcome) plus an append-only
 * {@link AuditEvent} trail (one row per phase) for every processed complaint.
 */
@Service
public class AuditService {

    private final CaseRepository cases;
    private final AuditEventRepository events;

    public AuditService(CaseRepository cases, AuditEventRepository events) {
        this.cases = cases;
        this.events = events;
    }

    @Transactional
    public void record(ComplaintResponse r, String complaintText) {
        CaseRecord c = new CaseRecord();
        c.setComplaintId(r.complaintId());
        c.setComplaintText(complaintText);
        c.setCategory(r.prediction().label());
        c.setConfidence(r.prediction().confidence());
        c.setClause(r.compliance().clause());
        c.setAckDue(r.compliance().ackDue());
        c.setResolutionDue(r.compliance().resolutionDue());
        c.setRiskFlags(String.join(",", r.compliance().riskFlags()));
        c.setEscalate(r.escalation().escalate());
        c.setEscalationLevel(r.escalation().level());
        c.setEscalationReason(r.escalation().reason());
        c.setDraftSubject(r.draft().subject());
        c.setDraftBody(r.draft().body());
        c.setStatus(r.status());
        c.setUrgency(r.urgency());
        c.setChannel(r.channel());
        c.setCreatedAt(Instant.now());
        cases.save(c);

        String id = r.complaintId();
        events.save(new AuditEvent(id, "classify",
                "label=" + r.prediction().label() + " confidence=" + r.prediction().confidence()));
        events.save(new AuditEvent(id, "compliance",
                "clause=" + r.compliance().clause()
                        + " resolutionDue=" + r.compliance().resolutionDue()
                        + " riskFlags=" + String.join(",", r.compliance().riskFlags())));
        events.save(new AuditEvent(id, "draft",
                "source=" + r.draft().source() + " subject=" + r.draft().subject()));
        events.save(new AuditEvent(id, "escalation",
                r.escalation().level() + " — " + r.escalation().reason()));
    }
}
