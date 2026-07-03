package com.aegis.api.dto;

import com.aegis.api.entity.AuditEvent;
import com.aegis.api.entity.CaseRecord;
import java.util.List;

/** A stored case plus its ordered audit trail (returned by GET /api/complaints/{id}). */
public record CaseView(CaseRecord caseRecord, List<AuditEvent> auditTrail) {
}
