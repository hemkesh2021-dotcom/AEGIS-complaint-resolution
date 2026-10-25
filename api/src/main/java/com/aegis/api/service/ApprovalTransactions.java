package com.aegis.api.service;

import com.aegis.api.entity.CaseRecord;
import com.aegis.api.repo.CaseRepository;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serializes review mutations across API replicas, without locking during AI calls. */
@Service
public class ApprovalTransactions {
    private final CaseRepository cases;

    public ApprovalTransactions(CaseRepository cases) {
        this.cases = cases;
    }

    @Transactional
    public ResponseEntity<Object> withLockedCase(String id,
            Function<CaseRecord, ResponseEntity<Object>> action) {
        return cases.findByIdForUpdate(id).map(action)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
