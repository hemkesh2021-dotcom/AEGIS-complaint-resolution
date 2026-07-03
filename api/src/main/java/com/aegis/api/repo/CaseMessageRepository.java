package com.aegis.api.repo;

import com.aegis.api.entity.CaseMessage;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseMessageRepository extends JpaRepository<CaseMessage, Long> {

    List<CaseMessage> findByComplaintIdOrderByCreatedAtAsc(String complaintId);

    long deleteByCreatedAtBefore(Instant cutoff);
}
