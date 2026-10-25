package com.aegis.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Best-effort notifications: never publish uncommitted/rolled-back communications. */
@Service
public class AfterCommitDelivery {
    private static final Logger log = LoggerFactory.getLogger(AfterCommitDelivery.class);
    private final TransactionTemplate deliveryTransaction;

    public AfterCommitDelivery(PlatformTransactionManager manager) {
        deliveryTransaction = new TransactionTemplate(manager);
        // afterCommit still has the old transaction's resources attached. Email audit
        // writes need their own transaction; joining the completed one loses writes.
        deliveryTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void schedule(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("notification requires an approval transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    deliveryTransaction.executeWithoutResult(status -> action.run());
                } catch (RuntimeException e) {
                    // The approval is already committed; do not encourage an HTTP retry
                    // or claim that it rolled back when only notification failed.
                    log.warn("Post-commit notification failed", e);
                }
            }
        });
    }
}
