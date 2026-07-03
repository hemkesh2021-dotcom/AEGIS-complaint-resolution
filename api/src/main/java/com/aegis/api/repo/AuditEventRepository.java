package com.aegis.api.repo;

import com.aegis.api.entity.AuditEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByComplaintIdOrderByCreatedAtAsc(String complaintId);
}
