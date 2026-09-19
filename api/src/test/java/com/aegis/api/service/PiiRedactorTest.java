package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Regression tests for supported redaction patterns, not a complete PII guarantee. */
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
    @Test
    void masksDeclaredNamesIncludingUnicodeAndRegexCharacters() {
        assertEquals("I am {CUSTOMER_NAME}; refund $420.",
                redactor.redact("I am A. Rao; refund $420.", "a. rao"));
        assertEquals("ನಾನು {CUSTOMER_NAME}", redactor.redact("ನಾನು ಹೇಮಕೇಶ್", "ಹೇಮಕೇಶ್"));
        assertEquals("{CUSTOMER_NAME} wrote", redactor.redact("Zoë wrote", "ZOË"));
        assertNull(redactor.redact(null, "A. Rao"));
        assertEquals("{CUSTOMER_NAME} disputes an annual fee",
                redactor.redact("Ann disputes an annual fee", "Ann"));
    }

}

