package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aegis.api.dto.ComplaintResponse.Compliance;
import com.aegis.api.dto.ComplaintResponse.Draft;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Drafting with NO chat model available — the deterministic template path.
 * The fallback is a resilience promise (LLM down ≠ pipeline down), so its
 * output must itself pass the grounding gate.
 */
class DraftServiceTest {

    /** An ObjectProvider with no bean — how the service sees a missing/down LLM. */
    private static final ObjectProvider<ChatModel> NO_MODEL = new ObjectProvider<>() {
        @Override
        public ChatModel getObject() {
            throw new UnsupportedOperationException("no chat model in this test");
        }

        @Override
        public ChatModel getIfAvailable() {
            return null;
        }
    };

    private DraftService service;

    private static final Compliance COMPLIANCE = new Compliance(
            "Credit card",
            "Reg Z / Fair Credit Billing Act billing-error resolution",
            "2026-07-10T10:00", "2026-08-14T10:00",
            List.of(), false);

    @BeforeEach
    void setUp() throws Exception {
        service = new DraftService(NO_MODEL, new PiiRedactor());
        service.load(); // templates.json from the classpath
    }

    @Test
    void humanDateFormatsForCustomers() {
        assertEquals("August 13, 2026", DraftService.humanDate("2026-08-13T20:25"));
        assertEquals("", DraftService.humanDate(""));
        assertEquals("not-a-date", DraftService.humanDate("not-a-date"), "unparseable input passes through");
    }

    @Test
    void templateFallbackRendersCompletely() {
        Draft d = service.draft("A. Rao", "CMP-TEST-1", "Credit card",
                "unauthorized charge dispute", COMPLIANCE, null);
        assertEquals("Template engine", d.source());
        assertTrue(d.body().contains("A. Rao"), d.body());
        assertTrue(d.body().contains("August 14, 2026"), "dates must be humanized: " + d.body());
        assertFalse(d.body().contains("{"), "no unfilled template vars: " + d.body());
        assertFalse(d.subject().contains("{"), d.subject());
    }

    @Test
    void templateFallbackPassesTheGroundingGate() {
        Draft d = service.draft("A. Rao", "CMP-TEST-2", "Mortgage",
                "escrow analysis question", COMPLIANCE, null);
        List<String> issues = new DraftVerifier().verify(d.subject(), d.body(),
                "escrow analysis question", "", COMPLIANCE.clause(),
                COMPLIANCE.ackDue(), COMPLIANCE.resolutionDue());
        assertTrue(issues.isEmpty(), "template output must be send-safe: " + issues);
    }

    @Test
    void unknownCategoryUsesTheDefaultTemplate() {
        Draft d = service.draft("A. Rao", "CMP-TEST-3", "Something brand new",
                "general question", COMPLIANCE, null);
        assertFalse(d.body().isBlank());
        assertFalse(d.body().contains("{"), d.body());
    }

    @Test
    void summarizeFallbackTruncatesWithoutAnLlm() {
        String longReply = "We have received your complaint. ".repeat(30);
        String s = service.summarize(longReply, "A. Rao");
        assertTrue(s.length() <= 241, "fallback summary is bounded: " + s.length());
        assertFalse(s.isBlank());
        assertEquals("", service.summarize("", "A. Rao"));
    }
}
