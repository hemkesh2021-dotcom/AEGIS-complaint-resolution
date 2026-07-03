package com.aegis.api.service;

import com.aegis.api.entity.AuditEvent;
import com.aegis.api.entity.CaseRecord;
import com.aegis.api.repo.AuditEventRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Customer notifications — branded HTML emails (with a plain-text alternative
 * part for old clients): acknowledgement, response, and follow-up.
 *
 * <p>Design rules: email must NEVER break the pipeline (all sends are
 * best-effort with failures logged + audited), and it only fires when
 * {@code aegis.mail.enabled=true} AND the customer supplied an address.
 * Locally, docker-compose routes everything into Mailpit (http://localhost:8025);
 * in production point spring.mail.* at a real SMTP relay.
 *
 * <p>HTML is table-based with inline styles (the only layout that survives
 * Gmail/Outlook), light background for readability, AEGIS dark-gold header.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final ObjectProvider<JavaMailSender> senderProvider;
    private final AuditEventRepository events;
    private final boolean enabled;
    private final String from;
    private final String baseUrl;

    public EmailService(ObjectProvider<JavaMailSender> senderProvider,
                        AuditEventRepository events,
                        @Value("${aegis.mail.enabled:false}") boolean enabled,
                        @Value("${aegis.mail.from:AEGIS Customer Care <care@aegis.local>}") String from,
                        @Value("${aegis.public-base-url:http://localhost:8088}") String baseUrl) {
        this.senderProvider = senderProvider;
        this.events = events;
        this.enabled = enabled;
        this.from = from;
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    // ── the three notifications ─────────────────────────────────────────────

    public void sendAcknowledgement(CaseRecord c) {
        String plain = "Dear " + name(c) + ",\n\n"
                + "Thank you for contacting us. Your complaint has been received and is under review "
                + "by a specialist.\n\n"
                + "Reference:      " + c.getComplaintId() + "\n"
                + "Respond by:     " + DraftService.humanDate(c.getResolutionDue()) + "\n"
                + "Tracking code:  " + c.getTrackingToken() + "\n\n"
                + "Track progress any time: " + baseUrl + "/portal.html\n\n"
                + "Regards,\nCustomer Care Team";

        String html = chip("#5BBF92", "✓ &nbsp;Complaint received")
                + h1("We're on it, " + esc(name(c)) + ".")
                + p("Thank you for contacting us. Your complaint has been received and is now "
                    + "under review by a specialist. Here's everything you need to follow it:")
                + kvCard(new String[][]{
                        {"Reference", esc(c.getComplaintId())},
                        {"We'll respond by", esc(DraftService.humanDate(c.getResolutionDue()))},
                        {"Category", esc(nz(c.getCategory()))}})
                + tokenBox(c.getTrackingToken())
                + button("Track your complaint", baseUrl + "/portal.html")
                + fine("Your page updates live the moment a specialist responds — no refresh needed.");

        send(c, "We received your complaint — " + c.getComplaintId(), plain,
                wrap("Your complaint " + esc(c.getComplaintId()) + " was received", html),
                "acknowledgement");
    }

    public void sendResponse(CaseRecord c) {
        String subject = c.getFinalSubject() != null ? c.getFinalSubject() : "Response to your complaint";
        String summary = nz(c.getFinalSummary());
        String body = nz(c.getFinalBody());

        String plain = (summary.isBlank() ? "" : "Summary: " + summary
                + "\n\n----------------------------------------\n\n") + body;

        String html = chip("#DDB35A", "★ &nbsp;Our response")
                + h1("A specialist has answered your complaint.")
                + (summary.isBlank() ? "" : summaryBox(esc(summary)))
                + letterBox(esc(subject), esc(body).replace("\n", "<br>"))
                + button("View the full thread", baseUrl + "/portal.html")
                + fine("Reply directly to this email if you have any further questions or documents to share.");

        send(c, subject, plain, wrap("We've responded to your complaint", html), "response");
    }

    public void sendFollowUp(CaseRecord c, String subject, String body) {
        String plain = body + "\n\n--\nThis is an update to our earlier response. "
                + "View the full thread: " + baseUrl + "/portal.html";

        String html = chip("#7EC5DE", "↻ &nbsp;Update")
                + h1("An update on your complaint.")
                + letterBox(esc(subject), esc(body).replace("\n", "<br>"))
                + button("View the full thread", baseUrl + "/portal.html")
                + fine("This message is an addition to our earlier response — nothing about the "
                    + "original reply has changed.");

        send(c, subject, plain, wrap("An update on your complaint", html), "follow-up");
    }

    // ── delivery ────────────────────────────────────────────────────────────

    private void send(CaseRecord c, String subject, String plain, String html, String kind) {
        String to = c.getCustomerEmail();
        if (to == null || to.isBlank()) {
            return; // customer chose not to leave an address
        }
        if (!enabled) {
            events.save(new AuditEvent(c.getComplaintId(), "email",
                    kind + " skipped (mail disabled) → " + mask(to)));
            return;
        }
        JavaMailSender sender = senderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("aegis.mail.enabled=true but no JavaMailSender configured (set spring.mail.host)");
            return;
        }
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(plain, html); // multipart/alternative: plain + HTML
            sender.send(msg);
            events.save(new AuditEvent(c.getComplaintId(), "email",
                    kind + " email sent → " + mask(to)));
        } catch (Exception e) {
            log.warn("email ({}) to {} failed: {}", kind, mask(to), e.getMessage());
            events.save(new AuditEvent(c.getComplaintId(), "email",
                    kind + " email FAILED → " + mask(to) + " (" + e.getMessage() + ")"));
        }
    }

    // ── HTML building blocks (inline-styled, table-based, client-safe) ─────

    private String wrap(String preheader, String content) {
        return "<!doctype html><html><body style=\"margin:0;padding:0;background:#F2ECDF;\">"
            + "<div style=\"display:none;max-height:0;overflow:hidden;mso-hide:all;\">" + preheader + "</div>"
            + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            +   "style=\"background:#F2ECDF;padding:32px 14px;\"><tr><td align=\"center\">"
            + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
            +   "style=\"max-width:560px;width:100%;\">"

            // header band
            + "<tr><td style=\"background:#14110B;border-radius:16px 16px 0 0;padding:22px 30px;\">"
            + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
            + "<td style=\"width:36px;height:36px;background:#DDB35A;"
            +   "background-image:linear-gradient(135deg,#F0CF8E,#B8862F);border-radius:10px;"
            +   "text-align:center;vertical-align:middle;font:700 17px Georgia,serif;color:#2A1C04;\">&#9670;</td>"
            + "<td style=\"padding-left:13px;font:700 20px 'Segoe UI',Helvetica,Arial,sans-serif;"
            +   "color:#F2EDE4;letter-spacing:.06em;\">AEGIS"
            + "<span style=\"font:400 12px 'Segoe UI',Arial;color:#9A917F;letter-spacing:.02em;\">"
            +   " &nbsp;·&nbsp; Customer Care</span></td>"
            + "</tr></table></td></tr>"

            // gold hairline
            + "<tr><td style=\"height:3px;background:#DDB35A;"
            +   "background-image:linear-gradient(90deg,#B8862F,#F0CF8E,#B8862F);\"></td></tr>"

            // body
            + "<tr><td style=\"background:#FFFFFF;padding:34px 34px 28px;"
            +   "font:400 15px/1.7 'Segoe UI',Helvetica,Arial,sans-serif;color:#3E382C;\">"
            + content
            + "</td></tr>"

            // footer
            + "<tr><td style=\"background:#FAF6EC;border-radius:0 0 16px 16px;padding:18px 30px;"
            +   "border-top:1px solid #EBE1CC;font:400 11.5px/1.7 'Segoe UI',Arial,sans-serif;color:#9A917F;\">"
            + "Keep your tracking code private — anyone who has it can view our response.<br>"
            + "AEGIS is a portfolio/reference project; this message is illustrative and not financial or legal advice."
            + "</td></tr>"

            + "</table></td></tr></table></body></html>";
    }

    private static String chip(String color, String label) {
        return "<div style=\"display:inline-block;border:1px solid " + color + ";color:" + color + ";"
            + "border-radius:20px;padding:4px 14px;font:700 11px 'Segoe UI',Arial;letter-spacing:.08em;"
            + "text-transform:uppercase;margin-bottom:16px;\">" + label + "</div>";
    }

    private static String h1(String text) {
        return "<div style=\"font:700 21px/1.35 Georgia,'Times New Roman',serif;color:#221C10;"
            + "margin:0 0 12px;\">" + text + "</div>";
    }

    private static String p(String text) {
        return "<p style=\"margin:0 0 18px;\">" + text + "</p>";
    }

    private static String kvCard(String[][] rows) {
        StringBuilder sb = new StringBuilder(
                "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
              + "style=\"background:#FAF6EC;border:1px solid #EBE1CC;border-radius:12px;margin:0 0 14px;\">");
        for (int i = 0; i < rows.length; i++) {
            String border = i < rows.length - 1 ? "border-bottom:1px solid #EFE6D2;" : "";
            sb.append("<tr>")
              .append("<td style=\"padding:11px 18px;").append(border)
              .append("font:400 12.5px 'Segoe UI',Arial;color:#8D8470;\">").append(rows[i][0]).append("</td>")
              .append("<td align=\"right\" style=\"padding:11px 18px;").append(border)
              .append("font:600 13.5px 'Segoe UI',Arial;color:#2E2818;\">").append(rows[i][1]).append("</td>")
              .append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    private static String tokenBox(String token) {
        return "<div style=\"background:#14110B;border-radius:12px;padding:14px 18px;margin:0 0 22px;\">"
            + "<div style=\"font:700 10px 'Segoe UI',Arial;color:#9A917F;letter-spacing:.12em;"
            + "text-transform:uppercase;margin-bottom:5px;\">Your tracking code</div>"
            + "<div style=\"font:600 15px 'Courier New',monospace;color:#F0CF8E;letter-spacing:.04em;"
            + "word-break:break-all;\">" + esc(token) + "</div></div>";
    }

    private static String summaryBox(String summary) {
        return "<div style=\"background:#FBF3DF;border:1px solid #E7CF9B;border-radius:12px;"
            + "padding:16px 20px;margin:0 0 16px;\">"
            + "<div style=\"font:700 10px 'Segoe UI',Arial;color:#A8801F;letter-spacing:.12em;"
            + "text-transform:uppercase;margin-bottom:6px;\">In short</div>"
            + "<div style=\"font:500 14.5px/1.6 'Segoe UI',Arial;color:#4A3E22;\">" + summary + "</div></div>";
    }

    private static String letterBox(String subject, String bodyHtml) {
        return "<div style=\"border:1px solid #E8E0CE;border-radius:12px;margin:0 0 22px;\">"
            + "<div style=\"padding:13px 20px;border-bottom:1px solid #EFE8D8;"
            + "font:600 14.5px 'Segoe UI',Arial;color:#221C10;background:#FDFBF5;"
            + "border-radius:12px 12px 0 0;\">" + subject + "</div>"
            + "<div style=\"padding:18px 20px;font:400 14px/1.75 'Segoe UI',Arial;color:#443E30;\">"
            + bodyHtml + "</div></div>";
    }

    private String button(String label, String url) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:0 0 14px;\"><tr>"
            + "<td style=\"background:#DDB35A;background-image:linear-gradient(135deg,#F0CF8E,#B8862F);"
            + "border-radius:11px;\">"
            + "<a href=\"" + url + "\" style=\"display:inline-block;padding:13px 30px;"
            + "font:700 14px 'Segoe UI',Helvetica,Arial,sans-serif;color:#2A1C04;text-decoration:none;\">"
            + label + " &nbsp;&rarr;</a></td></tr></table>";
    }

    private static String fine(String text) {
        return "<div style=\"font:400 12px/1.6 'Segoe UI',Arial;color:#9A917F;\">" + text + "</div>";
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** a***@example.com — the audit trail must not leak the full address. */
    private static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0))
                : email.charAt(0) + "***" + email.substring(at);
    }

    private static String name(CaseRecord c) {
        return c.getCustomerName() == null || c.getCustomerName().isBlank()
                ? "Customer" : c.getCustomerName();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
