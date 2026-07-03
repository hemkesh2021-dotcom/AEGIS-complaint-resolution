package com.aegis.api.service;

import com.aegis.api.dto.ComplaintResponse.Compliance;
import com.aegis.api.dto.ComplaintResponse.Draft;
import com.aegis.api.service.RagRetriever.RetrievedContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
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
 */
@Service
public class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);

    /** A response template loaded from templates.json. */
    public record Template(String category, String subject, String body) {
    }

    private final Map<String, Template> templates = new HashMap<>();
    private final ObjectProvider<ChatModel> chatModelProvider;

    public DraftService(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
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
                        .system("You are a professional bank complaint-response assistant. Write a formal, "
                                + "empathetic reply to the customer in PLAIN TEXT — no markdown, asterisks, "
                                + "headings, bullet symbols, or code fences. Ground it ONLY in the provided "
                                + "regulation context and case facts, cite the relevant clause, state the "
                                + "deadline, and do not invent facts. Start with a 'Subject:' line.")
                        .user(buildPrompt(customerName, complaintId, category, summary, compliance, context))
                        .call()
                        .content();
                if (reply != null && !reply.isBlank()) {
                    return new Draft(extractSubject(reply, complaintId), cleanReply(reply), "NVIDIA NIM (RAG)");
                }
            } catch (Exception e) {
                log.warn("LLM drafting failed ({}); using template engine.", e.getMessage());
            }
        }
        return templateDraft(customerName, complaintId, category, summary, compliance);
    }

    private String buildPrompt(String customerName, String complaintId, String category,
                               String summary, Compliance compliance, RetrievedContext context) {
        String ctx = (context == null || context.isEmpty())
                ? "(no additional context retrieved)"
                : context.text();
        return "Customer name: " + customerName + "\n"
                + "Complaint ID: " + complaintId + "\n"
                + "Product category: " + category + "\n"
                + "Complaint: " + summary + "\n"
                + "Regulatory clause: " + compliance.clause() + "\n"
                + "Resolution deadline: " + compliance.resolutionDue() + "\n\n"
                + "Relevant regulation context:\n" + ctx + "\n\n"
                + "Write the complete reply (a Subject line followed by the body).";
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
    public String summarize(String reply) {
        if (reply == null || reply.isBlank()) {
            return "";
        }
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel != null) {
            try {
                String s = ChatClient.create(chatModel)
                        .prompt()
                        .system("Summarize the following bank complaint-response letter for the customer in 1-2 short, "
                                + "plain-text sentences. Focus on what was decided and the deadline. No preamble, no markdown.")
                        .user(reply)
                        .call()
                        .content();
                if (s != null && !s.isBlank()) {
                    return s.strip();
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
        fields.put("ack_due", compliance.ackDue());
        fields.put("resolution_due", compliance.resolutionDue());
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
