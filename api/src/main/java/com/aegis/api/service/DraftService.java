package com.aegis.api.service;

import com.aegis.api.dto.ComplaintResponse.Compliance;
import com.aegis.api.dto.ComplaintResponse.Draft;
import com.aegis.api.service.RagRetriever.RetrievedContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Phase 4b — drafting.
 *
 * <p>If a chat model (NVIDIA NIM) is available, generate a reply grounded in the
 * retrieved regulation context (RAG). On any failure — no key, timeout, error —
 * fall back to the deterministic template engine, so the pipeline never breaks.
 *
 * <p>Privacy: the customer's name never leaves the trust boundary — prompts use a
 * {@code {CUSTOMER_NAME}} token that is substituted locally after generation, and
 * complaint text is passed through {@link PiiRedactor} before any external call.
 */
@Service
public class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);

    /** Local-substitution token so real names are never sent to the external LLM. */
    static final String NAME_TOKEN = "{CUSTOMER_NAME}";

    private static final DateTimeFormatter ISO_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter HUMAN_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private static final String SYSTEM_PROMPT =
            "You are a professional bank complaint-response assistant. Write a formal, "
            + "empathetic reply to the customer in PLAIN TEXT — no markdown, asterisks, "
            + "headings, bullet symbols, or code fences. Ground the reply ONLY in the provided "
            + "regulation context and case facts, cite the relevant clause, and state the deadline. "
            + "HARD RULES — violating any of these makes the reply unusable: "
            + "(1) Use only monetary amounts, dates, and account details that appear verbatim in the "
            + "complaint or the provided context. If the complaint does not state an amount, do not mention one. "
            + "(2) Never invent phone numbers, email addresses, URLs, addresses, or names. If the customer "
            + "needs to reach us, tell them to reply to this message. "
            + "(3) Never use bracket placeholders such as [Bank Name] or [Agent Name] — write \"our team\" "
            + "or omit the detail entirely. "
            + "(4) Address the customer exactly as {CUSTOMER_NAME} — do not guess, expand, or replace their name. "
            + "(5) Write all dates in a human-readable form (e.g., \"August 13, 2026\"), never machine "
            + "timestamps like 2026-08-13T20:25. "
            + "Start with a 'Subject:' line.";

    /** A response template loaded from templates.json. */
    public record Template(String category, String subject, String body) {
    }

    private final Map<String, Template> templates = new HashMap<>();
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final PiiRedactor redactor;

    public DraftService(ObjectProvider<ChatModel> chatModelProvider, PiiRedactor redactor) {
        this.chatModelProvider = chatModelProvider;
        this.redactor = redactor;
    }

    @PostConstruct
    void load() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = new ClassPathResource("templates.json").getInputStream()) {
            for (Template t : mapper.readValue(in, Template[].class)) {
                templates.put(t.category().trim().toLowerCase(), t);
            }
        }
    }

    public Draft draft(String customerName, String complaintId, String category,
                       String summary, Compliance compliance, RetrievedContext context) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel != null) {
            try {
                String reply = ChatClient.create(chatModel)
                        .prompt()
                        .system(SYSTEM_PROMPT)
                        .user(buildPrompt(complaintId, category, summary, compliance, context))
                        .call()
                        .content();
                if (reply != null && !reply.isBlank()) {
                    // substitute the real name locally — it was never sent to the LLM
                    reply = reply.replace(NAME_TOKEN, customerName);
                    return new Draft(extractSubject(reply, complaintId), cleanReply(reply), "NVIDIA NIM (RAG)");
                }
            } catch (Exception e) {
                log.warn("LLM drafting failed ({}); using template engine.", e.getMessage());
            }
        }
        return templateDraft(customerName, complaintId, category, summary, compliance);
    }

    private String buildPrompt(String complaintId, String category,
                               String summary, Compliance compliance, RetrievedContext context) {
        String ctx = (context == null || context.isEmpty())
                ? "(no additional context retrieved)"
                : context.text();
        return "Customer name: " + NAME_TOKEN + "  (a placeholder — use it verbatim in the salutation)\n"
                + "Complaint ID: " + complaintId + "\n"
                + "Product category: " + category + "\n"
                + "Complaint (sensitive identifiers redacted): " + redactor.redact(summary) + "\n"
                + "Regulatory clause: " + compliance.clause() + "\n"
                + "Resolution deadline: " + humanDate(compliance.resolutionDue()) + "\n\n"
                + "Relevant regulation context:\n" + ctx + "\n\n"
                + "Write the complete reply (a Subject line followed by the body).";
    }

    /** "2026-08-13T20:25" → "August 13, 2026" (falls back to the input if unparseable). */
    static String humanDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            return LocalDateTime.parse(iso, ISO_MIN).format(HUMAN_DATE);
        } catch (Exception e) {
            return iso;
        }
    }

    private String extractSubject(String reply, String complaintId) {
        for (String line : reply.split("\\r?\\n")) {
            // strip leading markdown (**, #, >, spaces) so "**Subject:**" is matched too
            String l = line.strip().replaceAll("^[*#>\\s]+", "").strip();
            if (l.toLowerCase().startsWith("subject:")) {
                String s = l.substring(l.indexOf(':') + 1).replace("*", "").strip();
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return "Re: Complaint " + complaintId;
    }

    /** Strip a surrounding ``` code fence and a leading duplicate "Subject:" line. */
    private String cleanReply(String reply) {
        String r = reply.strip();
        if (r.startsWith("```")) {
            int firstNewline = r.indexOf('\n');
            if (firstNewline > 0) {
                r = r.substring(firstNewline + 1);
            }
            if (r.endsWith("```")) {
                r = r.substring(0, r.length() - 3);
            }
            r = r.strip();
        }
        String[] parts = r.split("\\r?\\n", 2);
        if (parts.length == 2) {
            String first = parts[0].strip().replaceAll("^[*#>\\s]+", "");
            if (first.toLowerCase().startsWith("subject:")) {
                r = parts[1].strip();
            }
        }
        return r.strip();
    }

    /** Short, customer-facing summary of the approved reply (LLM, with an extract fallback). */
    public String summarize(String reply, String customerName) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        // Privacy: tokenize the name and redact identifiers before the external call.
        String safe = reply;
        if (customerName != null && !customerName.isBlank()) {
            safe = safe.replace(customerName, NAME_TOKEN);
        }
        safe = redactor.redact(safe);

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel != null) {
            try {
                String s = ChatClient.create(chatModel)
                        .prompt()
                        .system("Summarize the following bank complaint-response letter for the customer in 1-2 short, "
                                + "plain-text sentences. Focus on what was decided and the deadline. Use only facts "
                                + "from the letter — do not add amounts, dates, or contact details that are not in it. "
                                + "No preamble, no markdown.")
                        .user(safe)
                        .call()
                        .content();
                if (s != null && !s.isBlank()) {
                    return s.strip().replace(NAME_TOKEN, customerName == null ? "" : customerName);
                }
            } catch (Exception e) {
                log.warn("Summary generation failed ({}); using extract fallback.", e.getMessage());
            }
        }
        String t = reply.replaceAll("\\s+", " ").strip();
        return t.length() > 240 ? t.substring(0, 240).trim() + "…" : t;
    }

    private Draft templateDraft(String customerName, String complaintId, String category,
                                String summary, Compliance compliance) {
        Template t = templates.getOrDefault(category.toLowerCase(), templates.get("default"));

        Map<String, String> fields = new HashMap<>();
        fields.put("customer_name", customerName);
        fields.put("complaint_id", complaintId);
        fields.put("category", category);
        fields.put("complaint_summary", summary);
        fields.put("ack_due", humanDate(compliance.ackDue()));
        fields.put("resolution_due", humanDate(compliance.resolutionDue()));
        fields.put("clause", compliance.clause());
        fields.put("resolution_notes", "Your complaint is under review by our support team.");

        String subjectTemplate = (t != null) ? t.subject() : "Re: Complaint {complaint_id}";
        String bodyTemplate = (t != null) ? t.body()
                : "Dear {customer_name},\n\nWe acknowledge your complaint {complaint_id} regarding {category}. "
                + "Our team is reviewing the matter and will update you by {resolution_due}.\n\nRegards,\nCustomer Care Team";

        return new Draft(fill(subjectTemplate, fields), fill(bodyTemplate, fields), "Template engine");
    }

    private String fill(String template, Map<String, String> fields) {
        String out = template;
        for (var e : fields.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            out = out.replace("{" + e.getKey() + "}", value);
        }
        return out;
    }
}
