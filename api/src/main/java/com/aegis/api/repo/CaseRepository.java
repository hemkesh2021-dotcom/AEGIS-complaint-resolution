package com.aegis.api.repo;

import com.aegis.api.entity.CaseRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<CaseRecord, String> {

    List<CaseRecord> findTop50ByOrderByCreatedAtDesc();

    List<CaseRecord> findByEscalateTrueOrderByCreatedAtDesc();

    Optional<CaseRecord> findByTrackingToken(String trackingToken);

    long deleteByCreatedAtBefore(Instant cutoff);
}
