package com.aegis.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A follow-up message sent to the customer AFTER the original reply.
 *
 * <p>Sent communications are immutable — corrections and updates are appended as
 * new messages (this entity), never edited into the original. The customer sees
 * the full thread; the audit trail records every send.
 */
@Entity
@Table(name = "case_messages")
public class CaseMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String complaintId;

    @Column(columnDefinition = "text")
    private String subject;

    @Column(columnDefinition = "text")
    private String body;

    private Instant createdAt;

    protected CaseMessage() {
    }

    public CaseMessage(String complaintId, String subject, String body) {
        this.complaintId = complaintId;
        this.subject = subject;
        this.body = body;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getComplaintId() { return complaintId; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
}
