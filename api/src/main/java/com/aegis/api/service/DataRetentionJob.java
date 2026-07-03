package com.aegis.api.service;

import com.aegis.api.repo.AuditEventRepository;
import com.aegis.api.repo.CaseMessageRepository;
import com.aegis.api.repo.CaseRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data-retention purge: complaints contain PII, so they must not live forever.
 *
 * <p>Runs daily and deletes cases (and their audit events) older than
 * {@code aegis.retention-days}. Default {@code 0} = disabled (dev). Set
 * {@code AEGIS_RETENTION_DAYS} to your policy (e.g., 365) for any real deployment.
 */
@Component
public class DataRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);

    private final CaseRepository cases;
    private final AuditEventRepository events;
    private final CaseMessageRepository messages;
    private final int retentionDays;

    public DataRetentionJob(CaseRepository cases, AuditEventRepository events,
                            CaseMessageRepository messages,
                            @Value("${aegis.retention-days:0}") int retentionDays) {
        this.cases = cases;
        this.events = events;
        this.messages = messages;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpired() {
        if (retentionDays <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        long purgedCases = cases.deleteByCreatedAtBefore(cutoff);
        long purgedEvents = events.deleteByCreatedAtBefore(cutoff);
        long purgedMessages = messages.deleteByCreatedAtBefore(cutoff);
        if (purgedCases > 0 || purgedEvents > 0 || purgedMessages > 0) {
            log.info("retention purge: removed {} cases, {} audit events, {} follow-ups older than {} days",
                    purgedCases, purgedEvents, purgedMessages, retentionDays);
        }
    }
}
