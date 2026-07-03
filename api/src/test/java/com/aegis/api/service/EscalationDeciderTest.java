package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aegis.api.dto.ComplaintResponse.Escalation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Escalation must be explainable: right decision AND a human-readable reason. */
class EscalationDeciderTest {

    private final EscalationDecider decider = new EscalationDecider();

    @Test
    void riskFlagsEscalate() {
        Escalation e = decider.decide(List.of("financial_risk"), 0.95);
        assertTrue(e.escalate());
        assertEquals("Senior Review", e.level());
        assertTrue(e.reason().contains("financial_risk"));
    }

    @Test
    void lowConfidenceEscalates_uncertaintyIsASignal() {
        Escalation e = decider.decide(List.of(), 0.40);
        assertTrue(e.escalate());
        assertTrue(e.reason().toLowerCase().contains("confidence"));
    }

    @Test
    void confidentAndCleanStaysNormal() {
        Escalation e = decider.decide(List.of(), 0.90);
        assertFalse(e.escalate());
        assertEquals("Normal", e.level());
    }

    @Test
    void boundaryConfidenceDoesNotEscalate() {
        assertFalse(decider.decide(List.of(), 0.55).escalate(), "0.55 is within threshold");
        assertTrue(decider.decide(List.of(), 0.549).escalate());
    }
}
