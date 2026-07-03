package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The grounding gate. Case 1 reproduces a real incident from testing: the LLM
 * cited "$150" when the complaint said $420, invented a 1-800 number, left a
 * "[Bank Name]" placeholder, and printed a raw ISO timestamp — all in one
 * approved letter. Every one of those must be caught, forever.
 */
class DraftVerifierTest {

    private final DraftVerifier verifier = new DraftVerifier();

    private static final String COMPLAINT =
            "I noticed three charges on my credit card last week that I never authorized — "
            + "$420 total to a merchant I have never heard of.";
    private static final String CLAUSE = "Reg Z / Fair Credit Billing Act billing-error resolution";

    @Test
    void blocksTheRealIncident_fourDistinctViolations() {
        List<String> issues = verifier.verify(
                "Response to Your Billing Dispute",
                "Dear A. Rao, you are not required to pay the disputed $150 amount. "
                + "Our target date is 2026-08-13T20:25. Contact our team at 1-800-555-1234. "
                + "Sincerely, [Bank Name]",
                COMPLAINT, "", CLAUSE, "2026-07-09T20:25", "2026-08-13T20:25");
        assertEquals(4, issues.size(), "expected placeholder + timestamp + amount + phone: " + issues);
        assertTrue(issues.stream().anyMatch(i -> i.contains("[Bank Name]")));
        assertTrue(issues.stream().anyMatch(i -> i.contains("2026-08-13T20:25")));
        assertTrue(issues.stream().anyMatch(i -> i.contains("$150")));
        assertTrue(issues.stream().anyMatch(i -> i.contains("1-800-555-1234")));
    }

    @Test
    void passesAGroundedReply() {
        List<String> issues = verifier.verify(
                "Re: Complaint CMP-1",
                "Dear A. Rao, we have opened an investigation into the disputed $420. "
                + "You will receive our final decision by August 13, 2026. "
                + "Reply to this message with any questions.",
                COMPLAINT, "", CLAUSE, "2026-07-09T20:25", "2026-08-13T20:25");
        assertTrue(issues.isEmpty(), "grounded reply must pass: " + issues);
    }

    @Test
    void amountFormattingVariantsStillCountAsGrounded() {
        assertTrue(verifier.verify("s", "The disputed $420.00 will be reviewed.", COMPLAINT).isEmpty());
        assertTrue(verifier.verify("s", "The disputed USD 420 will be reviewed.", COMPLAINT).isEmpty());
    }

    @Test
    void amountFromRegulationContextIsAllowed() {
        List<String> issues = verifier.verify("s",
                "Your maximum liability is $50 under the regulation.",
                COMPLAINT, "Under Reg Z liability is capped at $50.");
        assertTrue(issues.isEmpty(), issues.toString());
    }

    @Test
    void flagsUnfilledTemplateVariables() {
        List<String> issues = verifier.verify("s",
                "Dear {customer_name} / {CUSTOMER_NAME}, we received your complaint.", COMPLAINT);
        assertEquals(2, issues.size(), issues.toString());
    }

    @Test
    void phonePresentInComplaintIsAllowed() {
        List<String> issues = verifier.verify("s",
                "We tried to reach you at 555-123-4567.",
                "My number is (555) 123-4567 and I dispute the $10 charge. The $10 charge is wrong.");
        assertTrue(issues.isEmpty(), issues.toString());
    }

    @Test
    void nullSafeInputs() {
        assertTrue(verifier.verify(null, null, (String) null).isEmpty());
    }
}
