package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Nothing personally identifying may cross the trust boundary to the external LLM. */
class PiiRedactorTest {

    private final PiiRedactor redactor = new PiiRedactor();

    @Test
    void redactsAllIdentifierClasses() {
        String out = redactor.redact(
                "Email a.rao@example.com or call 555-123-4567. "
                + "Card 4111 1111 1111 1111, SSN 123-45-6789, account 12345678.");
        assertFalse(out.contains("example.com"), out);
        assertFalse(out.contains("4111"), out);
        assertFalse(out.contains("123-45-6789"), out);
        assertFalse(out.contains("12345678"), out);
        assertFalse(out.contains("555-123"), out);
        assertTrue(out.contains("[email removed]"), out);
        assertTrue(out.contains("[card number removed]"), out);
    }

    @Test
    void preservesTheComplaintSubstance() {
        String out = redactor.redact("The $420 charge from June 20 is disputed within the 90-day window.");
        assertEquals("The $420 charge from June 20 is disputed within the 90-day window.", out);
    }

    @Test
    void nullAndBlankAreSafe() {
        assertNull(redactor.redact(null));
        assertEquals("", redactor.redact(""));
    }
}
