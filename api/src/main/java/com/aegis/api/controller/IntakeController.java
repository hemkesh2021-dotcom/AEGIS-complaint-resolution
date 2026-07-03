package com.aegis.api.controller;

import com.aegis.api.dto.ComplaintRequest;
import com.aegis.api.dto.ComplaintResponse;
import com.aegis.api.entity.CaseMessage;
import com.aegis.api.repo.CaseMessageRepository;
import com.aegis.api.repo.CaseRepository;
import com.aegis.api.service.PipelineService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final CaseMessageRepository messages;

    public IntakeController(PipelineService pipeline, CaseRepository cases,
                            CaseMessageRepository messages) {
        this.pipeline = pipeline;
        this.cases = cases;
        this.messages = messages;
    }

    public record AckResponse(String complaintId, String trackingToken, String status,
                              String statusLabel, String ackDue, String resolutionDue,
                              String message) {
    }

    @PostMapping("/intake")
    public AckResponse intake(@Valid @RequestBody ComplaintRequest request) {
        ComplaintResponse r = pipeline.run(request, "WEB");
        // The tracking token — not the guessable reference — is the customer's status key.
        String token = cases.findById(r.complaintId())
                .map(c -> c.getTrackingToken()).orElse("");
        return new AckResponse(
                r.complaintId(), token, r.status(), label(r.status()),
                r.compliance().ackDue(), r.compliance().resolutionDue(),
                "Thank you — your complaint has been received and is under review. "
                        + "A specialist will respond by the resolution date below. "
                        + "Keep your tracking code safe: anyone who has it can view our response.");
    }

    /**
     * Public status lookup — accepts ONLY the high-entropy tracking token, never the
     * CMP reference (short references are enumerable, and the response contains PII).
     */
    @GetMapping("/status/{token}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String token) {
        return cases.findByTrackingToken(token).map(c -> {
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
                // Any follow-up messages sent after the original reply (oldest first).
                List<Map<String, Object>> followUps = new ArrayList<>();
                for (CaseMessage msg : messages.findByComplaintIdOrderByCreatedAtAsc(c.getComplaintId())) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("subject", nz(msg.getSubject()));
                    f.put("body", nz(msg.getBody()));
                    f.put("sentAt", msg.getCreatedAt() == null ? "" : msg.getCreatedAt().toString());
                    followUps.add(f);
                }
                if (!followUps.isEmpty()) {
                    m.put("followUps", followUps);
                }
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
