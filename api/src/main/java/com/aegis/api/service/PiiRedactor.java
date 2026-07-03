package com.aegis.api.service;

import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Redacts sensitive identifiers from text before it leaves the trust boundary
 * (i.e., before any call to the external LLM API). Classification and embedding
 * run locally, so only the drafting/summarization prompts need this.
 *
 * <p>Deliberately conservative regex redaction: emails, SSN-like ids, card-like
 * digit runs, phone numbers, and long account numbers are removed; complaint
 * narrative and monetary amounts (needed for a grounded reply) are preserved.
 * Customer names are handled separately via the {@code {CUSTOMER_NAME}} token
 * substitution in {@link DraftService}.
 */
@Service
public class PiiRedactor {

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private static final Pattern SSN =
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    /** 13–19 digits, optionally separated — card / long account numbers. */
    private static final Pattern CARD =
            Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    private static final Pattern PHONE =
            Pattern.compile("(?:\\+?\\d{1,3}[-. ])?\\(?\\d{3}\\)?[-. ]\\d{3}[-. ]\\d{4}");

    /** Bare 8–12 digit runs — account/reference numbers. */
    private static final Pattern ACCOUNT =
            Pattern.compile("\\b\\d{8,12}\\b");

    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String t = EMAIL.matcher(text).replaceAll("[email removed]");
        t = SSN.matcher(t).replaceAll("[id number removed]");
        t = CARD.matcher(t).replaceAll("[card number removed]");
        t = PHONE.matcher(t).replaceAll("[phone removed]");
        t = ACCOUNT.matcher(t).replaceAll("[account number removed]");
        return t;
    }
}
