package com.aegis.api.controller;

import com.aegis.api.repo.CaseRepository;
import com.aegis.api.service.CaseIntelligenceService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Product intelligence — operator-gated (X-API-Key) like all non-public endpoints. */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class InsightsController {

    private final CaseIntelligenceService intelligence;
    private final CaseRepository cases;

    public InsightsController(CaseIntelligenceService intelligence, CaseRepository cases) {
        this.intelligence = intelligence;
        this.cases = cases;
    }

    /** Trends, SLA watchlist, risk mix, and AI-draft quality over recent cases. */
    @GetMapping("/insights")
    public Map<String, Object> insights() {
        return intelligence.insights();
    }

    /** Similar past cases + repeat-complainant context for one case. */
    @GetMapping("/complaints/{id}/similar")
    public ResponseEntity<Map<String, Object>> similar(@PathVariable String id) {
        return cases.findById(id).map(c -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("customerCaseCount", intelligence.customerCaseCount(c.getCustomerEmail()));
            m.put("similar", intelligence.similar(c, 5));
            return ResponseEntity.ok(m);
        }).orElse(ResponseEntity.notFound().build());
    }
}
