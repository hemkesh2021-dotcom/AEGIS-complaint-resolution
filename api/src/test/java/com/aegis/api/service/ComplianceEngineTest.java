package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aegis.api.dto.ComplaintResponse.Compliance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The compliance clocks are legally meaningful — the deadline in the customer's
 * acknowledgement comes straight from this math, so it gets exact-value tests.
 */
class ComplianceEngineTest {

    private ComplianceEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = new ComplianceEngine();
        engine.load(); // reads regulations.json from the classpath
    }

    @Test
    void creditCardUsesRegZWithBusinessDayMath() {
        // 2026-07-03 is a Friday. Credit card: ack 5 business days, resolve 30.
        Compliance c = engine.compute("Credit card", "2026-07-03T10:00", "simple billing question");
        assertEquals("2026-07-10T10:00", c.ackDue(), "5 business days from Friday = next Friday");
        assertEquals("2026-08-14T10:00", c.resolutionDue(), "30 business days from 2026-07-03");
        assertTrue(c.clause().contains("Fair Credit Billing Act"));
    }

    @Test
    void weekendsAreSkipped() {
        // Unknown category falls back to the default rule: ack 2, resolve 10 business days.
        Compliance c = engine.compute("Nonexistent category", "2026-07-03T09:30", "hello");
        assertEquals("2026-07-07T09:30", c.ackDue(), "Fri + 2 business days skips Sat/Sun → Tue");
        assertEquals("2026-07-17T09:30", c.resolutionDue());
    }

    @Test
    void detectsFinancialRisk() {
        Compliance c = engine.compute("Credit card", null, "There is an unauthorized charge on my card.");
        assertTrue(c.riskFlags().contains("financial_risk"), c.riskFlags().toString());
        assertTrue(c.escalate());
    }

    @Test
    void detectsLegalRisk() {
        Compliance c = engine.compute("Debt collection", null, "My attorney will be in touch about legal action.");
        assertTrue(c.riskFlags().contains("legal_risk"), c.riskFlags().toString());
    }

    @Test
    void detectsHarassmentAndVulnerability() {
        Compliance c = engine.compute("Debt collection", null,
                "They keep threatening my elderly mother on the phone.");
        assertTrue(c.riskFlags().contains("harassment"), c.riskFlags().toString());
        assertTrue(c.riskFlags().contains("vulnerable_customer"), c.riskFlags().toString());
    }

    @Test
    void cleanNarrativeHasNoFlags() {
        Compliance c = engine.compute("Credit card", null,
                "Please explain the annual fee on my statement.");
        assertTrue(c.riskFlags().isEmpty(), c.riskFlags().toString());
        assertFalse(c.escalate());
    }
}
