package com.aegis.api.controller;

import com.aegis.api.ApiKeyFilter;
import com.aegis.api.dto.CaseView;
import com.aegis.api.entity.AuditEvent;
import jakarta.servlet.http.HttpServletRequest;
import com.aegis.api.entity.CaseMessage;
import com.aegis.api.entity.CaseRecord;
import com.aegis.api.repo.AuditEventRepository;
import com.aegis.api.repo.CaseMessageRepository;
import com.aegis.api.repo.CaseRepository;
import com.aegis.api.service.CaseEventsPublisher;
import com.aegis.api.service.DraftService;
import com.aegis.api.service.DraftVerifier;
import com.aegis.api.service.EmailService;
import com.aegis.api.service.RagRetriever;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read side — powers the Audit Trail and Escalations views. */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final CaseRepository cases;
    private final AuditEventRepository events;
    private final CaseMessageRepository messages;
    private final DraftService drafting;
    private final DraftVerifier verifier;
    private final RagRetriever retriever;
    private final EmailService email;
    private final CaseEventsPublisher caseEvents;

    public AuditController(CaseRepository cases, AuditEventRepository events,
                           CaseMessageRepository messages, DraftService drafting,
                           DraftVerifier verifier, RagRetriever retriever,
                           EmailService email, CaseEventsPublisher caseEvents) {
        this.cases = cases;
        this.events = events;
        this.messages = messages;
        this.drafting = drafting;
        this.verifier = verifier;
        this.retriever = retriever;
        this.email = email;
        this.caseEvents = caseEvents;
    }

    /** One case + its ordered audit trail + follow-up thread. */
    @GetMapping("/complaints/{id}")
    public ResponseEntity<CaseView> getCase(@PathVariable String id) {
        return cases.findById(id)
                .map(c -> ResponseEntity.ok(new CaseView(c,
                        events.findByComplaintIdOrderByCreatedAtAsc(id),
                        messages.findByComplaintIdOrderByCreatedAtAsc(id))))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Most recent processed cases (audit trail view). */
    @GetMapping("/complaints")
    public List<CaseRecord> recent() {
        return cases.findTop50ByOrderByCreatedAtDesc();
    }

    /** Only the cases flagged for senior review (escalations view). */
    @GetMapping("/escalations")
    public List<CaseRecord> escalations() {
        return cases.findByEscalateTrueOrderByCreatedAtDesc();
    }

    /**
     * A CS agent approves (and optionally edits) the drafted reply; marks it sent.
     *
     * <p>The final text is re-verified against the complaint and case context before
     * anything reaches the customer. If verification fails, the send is blocked with
     * a 422 and the issues; the operator may explicitly override with {@code force},
     * which is recorded in the audit trail.
     */
    public record ApproveRequest(String subject, String body, Boolean force) {
    }

    @PostMapping("/complaints/{id}/approve")
    public ResponseEntity<Object> approve(@PathVariable String id,
                                          @RequestBody(required = false) ApproveRequest req,
                                          HttpServletRequest http) {
        String actor = actor(http);
        return cases.findById(id).<ResponseEntity<Object>>map(c -> {
            // Sent communications are immutable — the customer has already seen them.
            // Corrections and updates go through POST /complaints/{id}/follow-up.
            if ("SENT".equals(c.getStatus())) {
                return ResponseEntity.status(409).body((Object) Map.of(
                        "error", "this reply was already sent and is immutable — send a follow-up instead"));
            }

            // Maker-checker: CRITICAL or escalated cases need a supervisor's sign-off.
            boolean highStakes = "CRITICAL".equals(c.getUrgency()) || c.isEscalate();
            if (highStakes && !hasRole(http, ApiKeyFilter.ROLE_SUPERVISOR)) {
                events.save(new AuditEvent(id, "maker-checker",
                        "approval by '" + actor + "' REFUSED — CRITICAL/escalated cases require the supervisor role"));
                return ResponseEntity.status(403).body((Object) Map.of(
                        "error", "maker-checker: CRITICAL and escalated cases require a supervisor to approve",
                        "requiredRole", ApiKeyFilter.ROLE_SUPERVISOR));
            }
            String subject = (req != null && req.subject() != null && !req.subject().isBlank())
                    ? req.subject() : c.getDraftSubject();
            String body = (req != null && req.body() != null && !req.body().isBlank())
                    ? req.body() : c.getDraftBody();

            // Send gate — nothing with invented figures or template debris goes out.
            List<String> issues = verifier.verify(subject, body,
                    c.getComplaintText(), retrievedContext(c.getComplaintText()),
                    c.getClause(), c.getAckDue(), c.getResolutionDue());
            boolean force = req != null && Boolean.TRUE.equals(req.force());
            if (!issues.isEmpty() && !force) {
                events.save(new AuditEvent(id, "verification",
                        "send BLOCKED by grounding check: " + String.join(" | ", issues)));
                return ResponseEntity.unprocessableEntity()
                        .body((Object) Map.of("error", "draft failed the grounding check", "issues", issues));
            }

            c.setFinalSubject(subject);
            c.setFinalBody(body);
            c.setFinalSummary(drafting.summarize(body, c.getCustomerName()));
            c.setStatus("SENT");
            // Learning loop — every approval is implicit feedback on the AI draft.
            boolean edited = !nzs(body).equals(nzs(c.getDraftBody()))
                    || !nzs(subject).equals(nzs(c.getDraftSubject()));
            double sim = edited ? com.aegis.api.service.EditSignal.similarity(c.getDraftBody(), body) : 1.0;
            c.setOperatorEdited(edited);
            c.setEditSimilarity(sim);
            cases.save(c);
            events.save(new AuditEvent(id, "learning", edited
                    ? String.format("operator edited the draft before sending (similarity %.2f) — captured for retraining", sim)
                    : "draft sent unedited — positive training signal"));
            events.save(new AuditEvent(id, "approved", "by '" + actor + "' — " + (issues.isEmpty()
                    ? "reviewed, approved, and sent the reply"
                    : "sent with OVERRIDE despite grounding issues: " + String.join(" | ", issues))));
            email.sendResponse(c);                            // real delivery, audited
            caseEvents.publish(c.getTrackingToken(), "update"); // push to any open portal page
            return ResponseEntity.ok((Object) c);
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Appends a follow-up message (correction/update) to an already-sent case.
     * The original reply is never modified; the customer sees the full thread.
     * Follow-ups pass the same grounding gate as the original reply.
     */
    public record FollowUpRequest(String subject, String body, Boolean force) {
    }

    @PostMapping("/complaints/{id}/follow-up")
    public ResponseEntity<Object> followUp(@PathVariable String id,
                                           @RequestBody(required = false) FollowUpRequest req,
                                           HttpServletRequest http) {
        String actor = actor(http);
        return cases.findById(id).<ResponseEntity<Object>>map(c -> {
            if (!"SENT".equals(c.getStatus())) {
                return ResponseEntity.status(409).body((Object) Map.of(
                        "error", "no reply has been sent yet — review and approve the draft instead"));
            }
            if (req == null || req.body() == null || req.body().isBlank()) {
                return ResponseEntity.badRequest().body((Object) Map.of(
                        "error", "follow-up body is required"));
            }
            String subject = (req.subject() != null && !req.subject().isBlank())
                    ? req.subject() : "Update on your complaint " + id;

            // Same send gate; the original sent reply is also a legitimate grounding source.
            List<String> issues = verifier.verify(subject, req.body(),
                    c.getComplaintText(), retrievedContext(c.getComplaintText()),
                    c.getClause(), c.getAckDue(), c.getResolutionDue(), c.getFinalBody());
            boolean force = Boolean.TRUE.equals(req.force());
            if (!issues.isEmpty() && !force) {
                events.save(new AuditEvent(id, "verification",
                        "follow-up BLOCKED by grounding check: " + String.join(" | ", issues)));
                return ResponseEntity.unprocessableEntity().body((Object) Map.of(
                        "error", "follow-up failed the grounding check", "issues", issues));
            }

            CaseMessage m = messages.save(new CaseMessage(id, subject, req.body()));
            events.save(new AuditEvent(id, "follow-up", "by '" + actor + "' — " + (issues.isEmpty()
                    ? "sent a follow-up: " + subject
                    : "sent with OVERRIDE despite grounding issues: " + String.join(" | ", issues))));
            email.sendFollowUp(c, subject, req.body());
            caseEvents.publish(c.getTrackingToken(), "update");
            return ResponseEntity.ok((Object) m);
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * The learning-loop export: every approved case as a labeled example —
     * (complaint, category, AI draft, human-approved final, edit signal).
     * Feed it to fine-tuning/prompt-tuning jobs; unedited sends are positive
     * labels, heavy rewrites show exactly where the drafts fall short.
     */
    @GetMapping("/training-data")
    public List<Map<String, Object>> trainingData() {
        return cases.findByStatusOrderByCreatedAtDesc("SENT").stream().map(c -> {
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("complaintId", c.getComplaintId());
            m.put("category", c.getCategory());
            m.put("confidence", c.getConfidence());
            m.put("complaintText", c.getComplaintText());
            m.put("draftBody", c.getDraftBody());
            m.put("finalBody", c.getFinalBody());
            m.put("operatorEdited", c.getOperatorEdited());
            m.put("editSimilarity", c.getEditSimilarity());
            m.put("escalated", c.isEscalate());
            m.put("urgency", c.getUrgency());
            m.put("riskFlags", c.getRiskFlags());
            return m;
        }).toList();
    }

    private static String nzs(String s) {
        return s == null ? "" : s;
    }

    private static String actor(HttpServletRequest http) {
        Object a = http.getAttribute(ApiKeyFilter.ATTR_ACTOR);
        return a == null ? "unknown" : String.valueOf(a);
    }

    private static boolean hasRole(HttpServletRequest http, String role) {
        Object roles = http.getAttribute(ApiKeyFilter.ATTR_ROLES);
        return roles instanceof java.util.Set<?> set && set.contains(role);
    }

    private String retrievedContext(String complaintText) {
        try {
            RagRetriever.RetrievedContext ctx = retriever.retrieve(complaintText, 4);
            return ctx == null || ctx.isEmpty() ? "" : ctx.text();
        } catch (Exception e) {
            log.warn("context retrieval for approve-verification failed: {}", e.getMessage());
            return "";
        }
    }
}
