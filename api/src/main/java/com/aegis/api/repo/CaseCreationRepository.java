package com.aegis.api.repo;

import com.aegis.api.entity.CaseRecord;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Insert-only persistence for intake; updates belong to the review workflow. */
@Repository
public class CaseCreationRepository {
    private final EntityManager entityManager;

    public CaseCreationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(CaseRecord record) {
        entityManager.persist(record);
        // Force uniqueness validation before writing audit events or returning a token.
        // The database primary key is the arbiter, not an exists-then-save check.
        entityManager.flush();
    }
}
