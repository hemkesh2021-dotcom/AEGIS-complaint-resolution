package com.aegis.api.repo;

import com.aegis.api.entity.CaseRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityExistsException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
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
        try {
            entityManager.persist(record);
            // Validate uniqueness before audit writes or returning a tracking token.
            // The database primary key is the arbiter, not exists-then-save.
            entityManager.flush();
        } catch (EntityExistsException | ConstraintViolationException e) {
            // Normalize both persistence-context and database conflicts explicitly;
            // correctness must not depend on exception-translation proxy ordering.
            throw new DataIntegrityViolationException("Complaint reference conflicts", e);
        }
    }
}
