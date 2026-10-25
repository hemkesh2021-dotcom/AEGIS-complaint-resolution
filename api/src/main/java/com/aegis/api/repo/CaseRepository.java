package com.aegis.api.repo;

import com.aegis.api.entity.CaseRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<CaseRecord, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CaseRecord c where c.complaintId = :id")
    Optional<CaseRecord> findByIdForUpdate(@Param("id") String id);

    List<CaseRecord> findTop50ByOrderByCreatedAtDesc();

    List<CaseRecord> findByEscalateTrueOrderByCreatedAtDesc();

    Optional<CaseRecord> findByTrackingToken(String trackingToken);

    List<CaseRecord> findByStatusOrderByCreatedAtDesc(String status);

    List<CaseRecord> findTop500ByOrderByCreatedAtDesc();

    long countByCustomerEmailIgnoreCase(String customerEmail);

    long deleteByCreatedAtBefore(Instant cutoff);
}

