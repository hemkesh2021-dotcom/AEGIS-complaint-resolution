package com.aegis.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only trace — one row per phase, per case. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String complaintId;
    private String phase;

    @Column(columnDefinition = "text")
    private String detail;

    private Instant createdAt;

    protected AuditEvent() {
    }

    public AuditEvent(String complaintId, String phase, String detail) {
        this.complaintId = complaintId;
        this.phase = phase;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getComplaintId() { return complaintId; }
    public String getPhase() { return phase; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
