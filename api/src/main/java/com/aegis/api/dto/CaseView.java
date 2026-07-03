package com.aegis.api.dto;

import com.aegis.api.entity.AuditEvent;
import com.aegis.api.entity.CaseMessage;
import com.aegis.api.entity.CaseRecord;
import java.util.List;

/** A stored case plus its audit trail and follow-up thread (GET /api/complaints/{id}). */
public record CaseView(CaseRecord caseRecord, List<AuditEvent> auditTrail,
                       List<CaseMessage> followUps) {
}
