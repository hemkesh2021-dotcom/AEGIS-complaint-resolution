package com.aegis.api.controller;

import com.aegis.api.dto.ComplaintResponse.Compliance;
import com.aegis.api.dto.ComplaintResponse.Escalation;
import com.aegis.api.dto.ComplaintResponse.Prediction;
import com.aegis.api.service.ClassifierClient;
import com.aegis.api.service.ComplianceEngine;
import com.aegis.api.service.EscalationDecider;
import com.aegis.api.service.RagRetriever;
import com.aegis.api.service.UrgencyRouter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fast, non-persisting, LLM-free run of the deterministic pipeline (classify →
 * compliance → escalation → urgency → retrieval) for the evaluation harness.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class EvalController {

    private final ClassifierClient classifier;
    private final ComplianceEngine compliance;
    private final RagRetriever retriever;
    private final EscalationDecider escalation;
    private final UrgencyRouter urgency;

    public EvalController(ClassifierClient classifier, ComplianceEngine compliance,
                          RagRetriever retriever, EscalationDecider escalation, UrgencyRouter urgency) {
        this.classifier = classifier;
        this.compliance = compliance;
        this.retriever = retriever;
        this.escalation = escalation;
        this.urgency = urgency;
    }

    public record EvalRequest(
            @NotBlank @Size(max = 8000) String text,
            String receivedAt) {
    }

    @PostMapping("/eval")
    public Map<String, Object> eval(@Valid @RequestBody EvalRequest req) {
        Prediction pred = classifier.classify(req.text());
        Compliance comp = compliance.compute(pred.label(), req.receivedAt(), req.text());
        Escalation esc = escalation.decide(comp.riskFlags(), pred.confidence());
        String tier = urgency.tier(comp.riskFlags(), pred.confidence(), esc.escalate());
        RagRetriever.RetrievedContext ctx = retriever.retrieve(req.text(), 4);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", pred.label());
        m.put("confidence", pred.confidence());
        m.put("riskFlags", comp.riskFlags());
        m.put("escalate", esc.escalate());
        m.put("urgency", tier);
        m.put("retrievedCategories", ctx.categories());
        return m;
    }
}
