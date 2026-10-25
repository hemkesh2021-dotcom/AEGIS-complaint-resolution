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
import com.aegis.api.service.ApprovalTransactions;
import com.aegis.api.service.AfterCommitDelivery;
import com.aegis.api.service.DraftService;
import com.aegis.api.service.DraftVerifier;
import com.aegis.api.service.EmailService;
import com.aegis.api.service.RagRetriever;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final ApprovalTransactions transactions;
    private final AfterCommitDelivery delivery;

    public AuditController(CaseRepository cases, AuditEventRepository events,
                           CaseMessageRepository messages, DraftService drafting,
                           DraftVerifier verifier, RagRetriever retriever,
                           EmailService email, CaseEventsPublisher caseEvents,
                           ApprovalTransactions transactions, AfterCommitDelivery delivery) {
        this.cases = cases;
        this.events = events;
        this.messages = messages;
        this.drafting = drafting;
        this.verifier = verifier;
        this.retriever = retriever;
        this.email = email;
        this.caseEvents = caseEvents;
        this.transactions = transactions;
        this.delivery = delivery;
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
        return cases.findById(id).<ResponseEntity<Object>>map(snapshot -> {
            // Network-backed preparation happens before the short locked transaction.
            // Unauthorized/already-sent requests do not invoke retrieval or summarization.
            boolean prepare = !"SENT".equals(snapshot.getStatus())
                    && (!highStakes(snapshot) || hasRole(http, ApiKeyFilter.ROLE_SUPERVISOR));
            String context = prepare ? retrievedContext(snapshot.getComplaintText()) : "";
            String preparedBody = req != null && req.body() != null && !req.body().isBlank()
                    ? req.body() : snapshot.getDraftBody();
            String summary = prepare ? drafting.summarize(preparedBody, snapshot.getCustomerName()) : "";
            return transactions.withLockedCase(id, c -> {
                // Sent communications are immutable — the customer has already seen them.
                // Corrections and updates go through POST /complaints/{id}/follow-up.
                if ("SENT".equals(c.getStatus())) {
                    return ResponseEntity.status(409).body((Object) Map.of(
                            "error", "this reply was already sent and is immutable — send a follow-up instead"));
                }

                ResponseEntity<Object> denied = requireSupervisor(c, http, actor);
                if (denied != null) return denied;
                if (!sameApprovalInputs(snapshot, c)) return changedCase();
                String subject = (req != null && req.subject() != null && !req.subject().isBlank())
                        ? req.subject() : c.getDraftSubject();
                String body = (req != null && req.body() != null && !req.body().isBlank())
                        ? req.body() : c.getDraftBody();

                // Send gate — nothing with invented figures or template debris goes out.
                List<String> issues = verifier.verify(subject, body,
                        c.getComplaintText(), context,
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
                c.setFinalSummary(summary);
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
                delivery.schedule(() -> email.sendResponse(c));
                delivery.schedule(() -> caseEvents.publish(c.getTrackingToken(), "update"));
                return ResponseEntity.ok((Object) c);
            });
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
        return cases.findById(id).<ResponseEntity<Object>>map(snapshot -> {
            boolean prepare = "SENT".equals(snapshot.getStatus())
                    && (!highStakes(snapshot) || hasRole(http, ApiKeyFilter.ROLE_SUPERVISOR));
            String context = prepare ? retrievedContext(snapshot.getComplaintText()) : "";
            return transactions.withLockedCase(id, c -> {
                if (!"SENT".equals(c.getStatus())) {
                    return ResponseEntity.status(409).body((Object) Map.of(
                            "error", "no reply has been sent yet — review and approve the draft instead"));
                }
                ResponseEntity<Object> denied = requireSupervisor(c, http, actor);
                if (denied != null) return denied;
                if (!sameApprovalInputs(snapshot, c)) return changedCase();
                if (req == null || req.body() == null || req.body().isBlank()) {
                    return ResponseEntity.badRequest().body((Object) Map.of(
                            "error", "follow-up body is required"));
                }
                String subject = (req.subject() != null && !req.subject().isBlank())
                        ? req.subject() : "Update on your complaint " + id;

                // Same send gate; the original sent reply is also a legitimate grounding source.
                List<String> issues = verifier.verify(subject, req.body(),
                        c.getComplaintText(), context,
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
                delivery.schedule(() -> email.sendFollowUp(c, subject, req.body()));
                delivery.schedule(() -> caseEvents.publish(c.getTrackingToken(), "update"));
                return ResponseEntity.ok((Object) m);
            });
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

    private ResponseEntity<Object> requireSupervisor(CaseRecord c, HttpServletRequest http, String actor) {
        if (!highStakes(c) || hasRole(http, ApiKeyFilter.ROLE_SUPERVISOR)) return null;
        events.save(new AuditEvent(c.getComplaintId(), "maker-checker",
                "approval by '" + actor + "' REFUSED — CRITICAL/escalated cases require the supervisor role"));
        return ResponseEntity.status(403).body(Map.of(
                "error", "maker-checker: CRITICAL and escalated cases require a supervisor to approve",
                "requiredRole", ApiKeyFilter.ROLE_SUPERVISOR));
    }

    private static boolean highStakes(CaseRecord c) {
        return "CRITICAL".equals(c.getUrgency()) || c.isEscalate();
    }

    // Reject stale preparation rather than approving a different case snapshot.
    private static boolean sameApprovalInputs(CaseRecord a, CaseRecord b) {
        return a.isEscalate() == b.isEscalate()
                && Objects.equals(a.getUrgency(), b.getUrgency())
                && Objects.equals(a.getCustomerEmail(), b.getCustomerEmail())
                && Objects.equals(a.getTrackingToken(), b.getTrackingToken())
                && Objects.equals(a.getComplaintText(), b.getComplaintText())
                && Objects.equals(a.getCustomerName(), b.getCustomerName())
                && Objects.equals(a.getDraftSubject(), b.getDraftSubject())
                && Objects.equals(a.getDraftBody(), b.getDraftBody())
                && Objects.equals(a.getClause(), b.getClause())
                && Objects.equals(a.getAckDue(), b.getAckDue())
                && Objects.equals(a.getResolutionDue(), b.getResolutionDue())
                && Objects.equals(a.getFinalBody(), b.getFinalBody());
    }

    private static ResponseEntity<Object> changedCase() {
        return ResponseEntity.status(409).body(Map.of("error", "case changed during review — reload and retry"));
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

