package com.aegis.api.service;

import com.aegis.api.entity.AuditEvent;
import com.aegis.api.entity.CaseRecord;
import com.aegis.api.repo.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Customer notifications — acknowledgement, response, and follow-up emails.
 *
 * <p>Design rules: email must NEVER break the pipeline (all sends are
 * best-effort with failures logged + audited), and it only fires when
 * {@code aegis.mail.enabled=true} AND the customer supplied an address.
 * Locally, docker-compose routes everything into MailHog (http://localhost:8025);
 * in production point spring.mail.* at a real SMTP relay.
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

    public void sendAcknowledgement(CaseRecord c) {
        send(c, "We received your complaint — " + c.getComplaintId(),
                "Dear " + name(c) + ",\n\n"
                + "Thank you for contacting us. Your complaint has been received and is under review "
                + "by a specialist.\n\n"
                + "Reference:      " + c.getComplaintId() + "\n"
                + "Respond by:     " + DraftService.humanDate(c.getResolutionDue()) + "\n"
                + "Tracking code:  " + c.getTrackingToken() + "\n\n"
                + "Track progress any time: " + baseUrl + "/portal.html\n"
                + "(Keep your tracking code private — anyone who has it can view our response.)\n\n"
                + "Regards,\nCustomer Care Team",
                "acknowledgement");
    }

    public void sendResponse(CaseRecord c) {
        String subject = c.getFinalSubject() != null ? c.getFinalSubject() : "Response to your complaint";
        String summary = (c.getFinalSummary() == null || c.getFinalSummary().isBlank())
                ? "" : "Summary: " + c.getFinalSummary() + "\n\n----------------------------------------\n\n";
        send(c, subject, summary + nz(c.getFinalBody()), "response");
    }

    public void sendFollowUp(CaseRecord c, String subject, String body) {
        send(c, subject, body + "\n\n--\nThis is an update to our earlier response. "
                + "View the full thread: " + baseUrl + "/portal.html", "follow-up");
    }

    private void send(CaseRecord c, String subject, String body, String kind) {
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
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(to);
            m.setSubject(subject);
            m.setText(body);
            sender.send(m);
            events.save(new AuditEvent(c.getComplaintId(), "email",
                    kind + " email sent → " + mask(to)));
        } catch (Exception e) {
            log.warn("email ({}) to {} failed: {}", kind, mask(to), e.getMessage());
            events.save(new AuditEvent(c.getComplaintId(), "email",
                    kind + " email FAILED → " + mask(to) + " (" + e.getMessage() + ")"));
        }
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
