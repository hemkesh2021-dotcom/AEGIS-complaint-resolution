package com.aegis.api.controller;

import com.aegis.api.dto.CaseView;
import com.aegis.api.entity.AuditEvent;
import com.aegis.api.entity.CaseRecord;
import com.aegis.api.repo.AuditEventRepository;
import com.aegis.api.repo.CaseRepository;
import com.aegis.api.service.DraftService;
import java.util.List;
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

    private final CaseRepository cases;
    private final AuditEventRepository events;
    private final DraftService drafting;

    public AuditController(CaseRepository cases, AuditEventRepository events, DraftService drafting) {
        this.cases = cases;
        this.events = events;
        this.drafting = drafting;
    }

    /** One case + its ordered audit trail. */
    @GetMapping("/complaints/{id}")
    public ResponseEntity<CaseView> getCase(@PathVariable String id) {
        return cases.findById(id)
                .map(c -> ResponseEntity.ok(new CaseView(c, events.findByComplaintIdOrderByCreatedAtAsc(id))))
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

    /** A CS agent approves (and optionally edits) the drafted reply; marks it sent. */
    public record ApproveRequest(String subject, String body) {
    }

    @PostMapping("/complaints/{id}/approve")
    public ResponseEntity<CaseRecord> approve(@PathVariable String id,
                                              @RequestBody(required = false) ApproveRequest req) {
        return cases.findById(id).map(c -> {
            String subject = (req != null && req.subject() != null && !req.subject().isBlank())
                    ? req.subject() : c.getDraftSubject();
            String body = (req != null && req.body() != null && !req.body().isBlank())
                    ? req.body() : c.getDraftBody();
            c.setFinalSubject(subject);
            c.setFinalBody(body);
            c.setFinalSummary(drafting.summarize(body));
            c.setStatus("SENT");
            cases.save(c);
            events.save(new AuditEvent(id, "approved", "CS reviewed, approved, and sent the reply"));
            return ResponseEntity.ok(c);
        }).orElse(ResponseEntity.notFound().build());
    }
}
