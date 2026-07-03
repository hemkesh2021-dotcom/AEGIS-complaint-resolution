package com.aegis.api.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Grounding + render-safety gate for outgoing drafts.
 *
 * <p>Catches the failure modes observed in testing before they reach a customer:
 * <ul>
 *   <li><b>Invented figures</b> — a reply citing "$150" when the complaint said $420.</li>
 *   <li><b>Invented contact details</b> — phone numbers the model made up.</li>
 *   <li><b>Template debris</b> — "[Bank Name]", unfilled {tokens}, raw ISO timestamps.</li>
 * </ul>
 *
 * <p>{@link #verify} returns human-readable issues; an empty list means the draft is
 * safe to send. Callers decide whether to warn (draft time) or block (approve time).
 */
@Service
public class DraftVerifier {

    /** $1,234.56 · $150 · USD 150 */
    private static final Pattern MONEY =
            Pattern.compile("(?:\\$|USD\\s?)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");

    /** [Bank Name], [Your Name], [Insert Date] … */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\[[A-Za-z][A-Za-z .'/-]{1,40}\\]");

    /** Unfilled template variables: {customer_name}, {CUSTOMER_NAME} … */
    private static final Pattern TEMPLATE_VAR =
            Pattern.compile("\\{[A-Za-z_]{2,40}\\}");

    /** Raw machine timestamps: 2026-08-13T20:25 */
    private static final Pattern ISO_TIMESTAMP =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}");

    /** Phone-like sequences: 1-800-555-1234 · (555) 123-4567 · +1 555 123 4567 */
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+?1[-. ])?\\(?[0-9]{3}\\)?[-. ][0-9]{3}[-. ][0-9]{4}");

    /**
     * @param subject          draft subject line
     * @param body             draft body
     * @param groundingSources texts a claim may legitimately come from (complaint text,
     *                         retrieved regulation context, compliance clause/dates)
     * @return issues found; empty list = draft passed all checks
     */
    public List<String> verify(String subject, String body, String... groundingSources) {
        String draft = nz(subject) + "\n" + nz(body);
        StringBuilder sb = new StringBuilder();
        for (String s : groundingSources) {
            sb.append(nz(s)).append('\n');
        }
        String sources = sb.toString();

        List<String> issues = new ArrayList<>();

        for (String p : findAll(PLACEHOLDER, draft)) {
            issues.add("Unresolved placeholder \"" + p + "\" — replace it with real details before sending.");
        }
        for (String v : findAll(TEMPLATE_VAR, draft)) {
            issues.add("Unfilled template variable \"" + v + "\" — the template did not render completely.");
        }
        for (String t : findAll(ISO_TIMESTAMP, draft)) {
            issues.add("Raw timestamp \"" + t + "\" — rewrite as a customer-friendly date (e.g., \"August 13, 2026\").");
        }

        Set<BigDecimal> sourceAmounts = amounts(sources);
        for (BigDecimal a : amounts(draft)) {
            if (!sourceAmounts.contains(a)) {
                issues.add("Amount $" + a.toPlainString()
                        + " does not appear in the complaint or case context — the model may have invented it.");
            }
        }

        Set<String> sourcePhones = phoneDigits(sources);
        for (String p : findAll(PHONE, draft)) {
            if (!sourcePhones.contains(digits(p))) {
                issues.add("Phone number \"" + p.strip()
                        + "\" does not appear in the complaint or case context — never invent contact details.");
            }
        }
        return issues;
    }

    private static Set<BigDecimal> amounts(String text) {
        Set<BigDecimal> out = new LinkedHashSet<>();
        Matcher m = MONEY.matcher(text);
        while (m.find()) {
            try {
                out.add(new BigDecimal(m.group(1).replace(",", "")).stripTrailingZeros());
            } catch (NumberFormatException ignored) {
                // not a parseable amount — skip
            }
        }
        return out;
    }

    private static Set<String> phoneDigits(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : findAll(PHONE, text)) {
            out.add(digits(p));
        }
        return out;
    }

    private static List<String> findAll(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static String digits(String s) {
        String d = s.replaceAll("\\D", "");
        // normalize a leading US country code so "+1 555…" matches "555…"
        return (d.length() == 11 && d.startsWith("1")) ? d.substring(1) : d;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
