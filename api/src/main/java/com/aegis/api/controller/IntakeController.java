package com.aegis.api.controller;

import com.aegis.api.dto.ComplaintRequest;
import com.aegis.api.dto.ComplaintResponse;
import com.aegis.api.repo.CaseRepository;
import com.aegis.api.service.PipelineService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer-facing web intake + status tracking. Runs the full pipeline (so the
 * case is classified, drafted, tiered, and queued for CS review) but returns
 * ONLY an acknowledgement — no internal draft, risk flags, or classification.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class IntakeController {

    private final PipelineService pipeline;
    private final CaseRepository cases;

    public IntakeController(PipelineService pipeline, CaseRepository cases) {
        this.pipeline = pipeline;
        this.cases = cases;
    }

    public record AckResponse(String complaintId, String status, String statusLabel,
                              String ackDue, String resolutionDue, String message) {
    }

    @PostMapping("/intake")
    public AckResponse intake(@Valid @RequestBody ComplaintRequest request) {
        ComplaintResponse r = pipeline.run(request, "WEB");
        return new AckResponse(
                r.complaintId(), r.status(), label(r.status()),
                r.compliance().ackDue(), r.compliance().resolutionDue(),
                "Thank you — your complaint has been received and is under review. "
                        + "A specialist will respond by the resolution date below.");
    }

    @GetMapping("/status/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String id) {
        return cases.findById(id).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("complaintId", c.getComplaintId());
            m.put("status", c.getStatus() == null ? "IN_REVIEW" : c.getStatus());
            m.put("statusLabel", label(c.getStatus()));
            m.put("category", nz(c.getCategory()));
            m.put("ackDue", nz(c.getAckDue()));
            m.put("resolutionDue", nz(c.getResolutionDue()));
            // Once the CS team has approved and sent, share the reply + summary with the customer.
            if ("SENT".equals(c.getStatus())) {
                Map<String, Object> reply = new LinkedHashMap<>();
                reply.put("subject", nz(c.getFinalSubject() != null ? c.getFinalSubject() : c.getDraftSubject()));
                reply.put("body", nz(c.getFinalBody() != null ? c.getFinalBody() : c.getDraftBody()));
                reply.put("summary", nz(c.getFinalSummary()));
                m.put("reply", reply);
            }
            return ResponseEntity.ok(m);
        }).orElse(ResponseEntity.notFound().build());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String label(String status) {
        return "SENT".equals(status) ? "Responded" : "Under review";
    }
}
