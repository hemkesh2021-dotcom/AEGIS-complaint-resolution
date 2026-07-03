package com.aegis.api.service;

import com.aegis.api.dto.ComplaintRequest;
import com.aegis.api.dto.ComplaintResponse;
import com.aegis.api.dto.ComplaintResponse.Compliance;
import com.aegis.api.dto.ComplaintResponse.Draft;
import com.aegis.api.dto.ComplaintResponse.Escalation;
import com.aegis.api.dto.ComplaintResponse.Prediction;
import com.aegis.api.dto.ComplaintResponse.Task;
import com.aegis.api.service.RagRetriever.RetrievedContext;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Phase 1 + orchestration — chains the six phases in order and assembles the
 * response. The Java equivalent of v1 pipeline.py.
 */
@Service
public class PipelineService {

    private static final DateTimeFormatter ISO_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final ClassifierClient classifier;
    private final ComplianceEngine compliance;
    private final RagRetriever retriever;
    private final DraftService drafting;
    private final DraftVerifier verifier;
    private final EscalationDecider escalation;
    private final UrgencyRouter urgencyRouter;
    private final AuditService audit;

    public PipelineService(ClassifierClient classifier, ComplianceEngine compliance,
                           RagRetriever retriever, DraftService drafting, DraftVerifier verifier,
                           EscalationDecider escalation, UrgencyRouter urgencyRouter,
                           AuditService audit) {
        this.classifier = classifier;
        this.compliance = compliance;
        this.retriever = retriever;
        this.drafting = drafting;
        this.verifier = verifier;
        this.escalation = escalation;
        this.urgencyRouter = urgencyRouter;
        this.audit = audit;
    }

    public ComplaintResponse run(ComplaintRequest req, String defaultChannel) {
        String customer = isBlank(req.customerName()) ? "Customer" : req.customerName();
        String id = isBlank(req.complaintId())
                ? "CMP-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase()
                : req.complaintId();

        // Phase 2 — classify
        Prediction prediction = classifier.classify(req.text());

        // Phase 3 — compliance
        Compliance comp = compliance.compute(prediction.label(), req.receivedAt(), req.text());

        // Phase 4 — retrieve context (RAG) + draft
        String text = req.text().trim();
        String summary = text.length() > 180 ? text.substring(0, 180).trim() : text;
        RetrievedContext context = retriever.retrieve(req.text(), 4);
        Draft draft = drafting.draft(customer, id, prediction.label(), summary, comp, context);

        // Phase 4c — grounding check: does every figure/contact detail in the draft
        // actually come from the complaint, the retrieved context, or the case facts?
        List<String> draftWarnings = verifier.verify(draft.subject(), draft.body(),
                req.text(), context == null ? "" : context.text(),
                comp.clause(), comp.ackDue(), comp.resolutionDue());

        // Phase 6 — escalation decision
        Escalation esc = escalation.decide(comp.riskFlags(), prediction.confidence());

        // Phase 5 — tasks
        List<Task> tasks = buildTasks(prediction.label(), comp, prediction.confidence(), esc.escalate());

        // Urgency tier + channel of origin
        String urgency = urgencyRouter.tier(comp.riskFlags(), prediction.confidence(), esc.escalate());
        String channel = (req.channel() != null && !req.channel().isBlank()) ? req.channel() : defaultChannel;

        ComplaintResponse response = new ComplaintResponse(
                id, prediction, comp, draft, tasks, esc, urgency, "IN_REVIEW", channel);

        // Phase 7 — audit
        audit.record(response, req.text(), customer, req.email(), draftWarnings);
        return response;
    }

    private List<Task> buildTasks(String category, Compliance comp, double confidence, boolean escalate) {
        List<Task> tasks = new ArrayList<>();
        tasks.add(new Task("Acknowledge complaint", "Customer Care",
                confidence >= 0.6 ? "High" : "Medium", comp.ackDue(), "OPEN"));
        tasks.add(new Task("Investigate " + category.toLowerCase(), "Operations",
                comp.riskFlags().contains("financial_risk") ? "High" : "Medium", comp.resolutionDue(), "OPEN"));
        if (escalate) {
            String due = LocalDateTime.now().plusHours(4).format(ISO_MIN);
            tasks.add(new Task("Escalate to senior reviewer", "Compliance", "Critical", due, "OPEN"));
        }
        return tasks;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
