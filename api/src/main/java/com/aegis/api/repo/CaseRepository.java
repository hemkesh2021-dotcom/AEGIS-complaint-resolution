package com.aegis.api.repo;

import com.aegis.api.entity.CaseRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<CaseRecord, String> {

    List<CaseRecord> findTop50ByOrderByCreatedAtDesc();

    List<CaseRecord> findByEscalateTrueOrderByCreatedAtDesc();
}
