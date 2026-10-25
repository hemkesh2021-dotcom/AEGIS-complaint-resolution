package com.aegis.api.controller;

import com.aegis.api.ApiKeyFilter;
import com.aegis.api.entity.CaseRecord;
import com.aegis.api.entity.AuditEvent;
import com.aegis.api.repo.AuditEventRepository;
import com.aegis.api.repo.CaseMessageRepository;
import com.aegis.api.repo.CaseRepository;
import com.aegis.api.service.AfterCommitDelivery;
import com.aegis.api.service.ApprovalTransactions;
import com.aegis.api.service.CaseEventsPublisher;
import com.aegis.api.service.DraftService;
import com.aegis.api.service.DraftVerifier;
import com.aegis.api.service.EmailService;
import com.aegis.api.service.RagRetriever;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real database transactions/row locks, with every external integration replaced by a mock. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@Import({AuditController.class, ApprovalTransactions.class, AfterCommitDelivery.class, DraftVerifier.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApprovalControlsTest {
    @Autowired AuditController controller;
    @Autowired CaseRepository cases;
    @Autowired CaseMessageRepository messages;
    @Autowired AuditEventRepository events;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean DraftVerifier verifier;
    @MockitoBean DraftService drafting;
    @MockitoBean RagRetriever retriever;
    @MockitoBean EmailService email;
    @MockitoBean CaseEventsPublisher caseEvents;

    @BeforeEach
    void resetData() {
        messages.deleteAll();
        events.deleteAll();
        cases.deleteAll();
        when(drafting.summarize(anyString(), anyString())).thenReturn("Summary");
    }

    @ParameterizedTest
    @CsvSource({"CRITICAL,false", "NORMAL,true"})
    void operatorCannotApproveHighStakesEvenWithForce(String urgency, boolean escalated) {
        CaseRecord c = createCase("IN_REVIEW", urgency, escalated);
        var result = controller.approve(c.getComplaintId(),
                new AuditController.ApproveRequest("Reply", "Refund $999", true), operator());
        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertThat(cases.findById(c.getComplaintId()).orElseThrow().getStatus()).isEqualTo("IN_REVIEW");
        assertRefusal(c);
        verifyNoInteractions(email, caseEvents, drafting, retriever);
    }

    @ParameterizedTest
    @CsvSource({"CRITICAL,false", "NORMAL,true"})
    void operatorCannotSendHighStakesFollowUpEvenWithForce(String urgency, boolean escalated) {
        CaseRecord c = createCase("SENT", urgency, escalated);
        var result = controller.followUp(c.getComplaintId(),
                new AuditController.FollowUpRequest("Update", "Refund $999", true), operator());
        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertThat(messages.findByComplaintIdOrderByCreatedAtAsc(c.getComplaintId())).isEmpty();
        assertThat(cases.findById(c.getComplaintId()).orElseThrow().getFinalBody()).isEqualTo("Original reply");
        assertRefusal(c);
        verifyNoInteractions(email, caseEvents, drafting, retriever);
    }

    @ParameterizedTest
    @CsvSource({"CRITICAL,false", "NORMAL,true"})
    void supervisorCanSendHighStakesFollowUpAndOverrideIsAudited(String urgency, boolean escalated) {
        CaseRecord c = createCase("SENT", urgency, escalated);
        var result = controller.followUp(c.getComplaintId(),
                new AuditController.FollowUpRequest("Update", "Refund $999", true), supervisor());
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(messages.findByComplaintIdOrderByCreatedAtAsc(c.getComplaintId())).hasSize(1);
        assertThat(events.findByComplaintIdOrderByCreatedAtAsc(c.getComplaintId()))
                .anySatisfy(e -> assertThat(e.getDetail()).contains("OVERRIDE"));
        verify(email).sendFollowUp(any(CaseRecord.class), eq("Update"), eq("Refund $999"));
        verify(caseEvents).publish(c.getTrackingToken(), "update");
    }

    @ParameterizedTest
    @CsvSource({"CRITICAL,false", "NORMAL,true"})
    void supervisorCanApproveHighStakesCase(String urgency, boolean escalated) {
        CaseRecord c = createCase("IN_REVIEW", urgency, escalated);
        assertThat(controller.approve(c.getComplaintId(), null, supervisor()).getStatusCode().value()).isEqualTo(200);
        verify(email).sendResponse(any(CaseRecord.class));
    }

    @Test
    void deliveryFailureCannotReopenCommittedApprovalOrCauseRetryDelivery() {
        CaseRecord c = createCase("IN_REVIEW", "NORMAL", false);
        doThrow(new IllegalStateException("Simulated delivery failure")).when(email).sendResponse(any(CaseRecord.class));
        assertThat(controller.approve(c.getComplaintId(), null, operator()).getStatusCode().value()).isEqualTo(200);
        assertThat(cases.findById(c.getComplaintId()).orElseThrow().getStatus()).isEqualTo("SENT");
        assertThat(controller.approve(c.getComplaintId(), null, operator()).getStatusCode().value()).isEqualTo(409);
        verify(email, times(1)).sendResponse(any(CaseRecord.class));
    }

    @Test
    void supervisorStillPassesGroundingGateUnlessExplicitlyOverridden() {
        CaseRecord c = createCase("SENT", "CRITICAL", false);
        assertThat(controller.followUp(c.getComplaintId(),
                new AuditController.FollowUpRequest("Update", "Refund $999", false), supervisor())
                .getStatusCode().value()).isEqualTo(422);
        assertThat(messages.count()).isZero();
        verifyNoInteractions(email, caseEvents);
    }

    @Test
    void normalFollowUpRemainsAvailableToOperator() {
        CaseRecord c = createCase("SENT", "NORMAL", false);
        assertThat(controller.followUp(c.getComplaintId(),
                new AuditController.FollowUpRequest("Update", "We are reviewing your complaint.", false), operator())
                .getStatusCode().value()).isEqualTo(200);
        verify(email).sendFollowUp(any(CaseRecord.class), eq("Update"), eq("We are reviewing your complaint."));
    }

    @Test
    void concurrentApprovalsCommitOneImmutableReplyAndDeliverOnlyOnce() throws Exception {
        CaseRecord c = createCase("IN_REVIEW", "NORMAL", false);
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstHasLock.countDown();
            assertThat(releaseFirst.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(verifier).verify(eq("First"), eq("First reply"), any(String[].class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> controller.approve(c.getComplaintId(),
                    new AuditController.ApproveRequest("First", "First reply", false), operator()));
            try {
                assertThat(firstHasLock.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    return controller.approve(c.getComplaintId(),
                            new AuditController.ApproveRequest("Second", "Second reply", false), operator());
                });
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
                assertThat(second.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(409);
            } finally {
                releaseFirst.countDown();
            }
        }
        CaseRecord persisted = cases.findById(c.getComplaintId()).orElseThrow();
        assertThat(persisted.getFinalBody()).isEqualTo("First reply");
        assertThat(persisted.getFinalSubject()).isEqualTo("First");
        assertThat(persisted.getStatus()).isEqualTo("SENT");
        assertThat(events.findByComplaintIdOrderByCreatedAtAsc(c.getComplaintId())
                .stream().filter(e -> "approved".equals(e.getPhase())).count()).isEqualTo(1);
        verify(email, times(1)).sendResponse(any(CaseRecord.class));
        verify(caseEvents, times(1)).publish(c.getTrackingToken(), "update");
        verify(drafting, times(2)).summarize(anyString(), anyString());
    }

    @Test
    void slowNetworkPreparationDoesNotHoldTheCaseLock() throws Exception {
        CaseRecord c = createCase("IN_REVIEW", "NORMAL", false);
        CountDownLatch firstPreparing = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(drafting.summarize(eq("First reply"), anyString())).thenAnswer(invocation -> {
            firstPreparing.countDown();
            assertThat(releaseFirst.await(10, TimeUnit.SECONDS)).isTrue();
            return "Summary";
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> controller.approve(c.getComplaintId(),
                    new AuditController.ApproveRequest("First", "First reply", false), operator()));
            try {
                assertThat(firstPreparing.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> controller.approve(c.getComplaintId(),
                        new AuditController.ApproveRequest("Second", "Second reply", false), operator()));
                assertThat(second.get(5, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            } finally {
                releaseFirst.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(409);
        }
        assertThat(cases.findById(c.getComplaintId()).orElseThrow().getFinalBody()).isEqualTo("Second reply");
        verify(email, times(1)).sendResponse(any(CaseRecord.class));
    }

    @Test
    void changedDraftDuringPreparationCannotBeApprovedFromStaleSnapshot() {
        CaseRecord c = createCase("IN_REVIEW", "NORMAL", false);
        when(drafting.summarize(anyString(), anyString())).thenAnswer(invocation -> {
            CaseRecord changed = cases.findById(c.getComplaintId()).orElseThrow();
            changed.setDraftBody("A newer draft requiring review");
            cases.saveAndFlush(changed);
            return "Stale summary";
        });
        assertThat(controller.approve(c.getComplaintId(), null, operator()).getStatusCode().value()).isEqualTo(409);
        CaseRecord persisted = cases.findById(c.getComplaintId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo("IN_REVIEW");
        assertThat(persisted.getDraftBody()).isEqualTo("A newer draft requiring review");
        assertThat(persisted.getFinalBody()).isNull();
        verifyNoInteractions(email, caseEvents);
    }

    @Test
    void escalationDuringPreparationIsRecheckedBeforeApproval() {
        CaseRecord c = createCase("IN_REVIEW", "NORMAL", false);
        when(drafting.summarize(anyString(), anyString())).thenAnswer(invocation -> {
            CaseRecord changed = cases.findById(c.getComplaintId()).orElseThrow();
            changed.setEscalate(true);
            cases.saveAndFlush(changed);
            return "Summary";
        });
        assertThat(controller.approve(c.getComplaintId(),
                new AuditController.ApproveRequest("Reply", "Refund $999", true), operator())
                .getStatusCode().value()).isEqualTo(403);
        assertRefusal(c);
        verifyNoInteractions(email, caseEvents);
    }

    @Test
    void escalationDuringRetrievalIsRecheckedBeforeFollowUp() {
        CaseRecord c = createCase("SENT", "NORMAL", false);
        when(retriever.retrieve(anyString(), eq(4))).thenAnswer(invocation -> {
            CaseRecord changed = cases.findById(c.getComplaintId()).orElseThrow();
            changed.setUrgency("CRITICAL");
            cases.saveAndFlush(changed);
            return null;
        });
        assertThat(controller.followUp(c.getComplaintId(),
                new AuditController.FollowUpRequest("Update", "Refund $999", true), operator())
                .getStatusCode().value()).isEqualTo(403);
        assertRefusal(c);
        assertThat(messages.count()).isZero();
        verifyNoInteractions(email, caseEvents);
    }

    @Test
    void rollbackDoesNotDeliverOrPersistApproval() {
        CaseRecord c = createCase("IN_REVIEW", "NORMAL", false);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(controller.approve(c.getComplaintId(), null, operator()).getStatusCode().value()).isEqualTo(200);
            verifyNoInteractions(email, caseEvents);
            tx.setRollbackOnly();
        });
        assertThat(cases.findById(c.getComplaintId()).orElseThrow().getStatus()).isEqualTo("IN_REVIEW");
        assertThat(events.count()).isZero();
        verifyNoInteractions(email, caseEvents);
    }

    @Test
    void rollbackDoesNotDeliverOrPersistFollowUp() {
        CaseRecord c = createCase("SENT", "NORMAL", false);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(controller.followUp(c.getComplaintId(),
                    new AuditController.FollowUpRequest("Update", "Updated reply", false), operator())
                    .getStatusCode().value()).isEqualTo(200);
            verifyNoInteractions(email, caseEvents);
            tx.setRollbackOnly();
        });
        assertThat(messages.count()).isZero();
        assertThat(events.count()).isZero();
        verifyNoInteractions(email, caseEvents);
    }

    @Test
    void deliveryObservesCommittedApprovalAndAuditFromIndependentTransaction() {
        CaseRecord c = createCase("IN_REVIEW", "NORMAL", false);
        doAnswer(invocation -> {
            TransactionTemplate independent = new TransactionTemplate(transactionManager);
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(tx -> {
                assertThat(cases.findById(c.getComplaintId()).orElseThrow().getStatus()).isEqualTo("SENT");
                assertThat(events.findByComplaintIdOrderByCreatedAtAsc(c.getComplaintId()))
                        .anySatisfy(e -> assertThat(e.getPhase()).isEqualTo("approved"));
            });
            return null;
        }).when(email).sendResponse(any(CaseRecord.class));
        assertThat(controller.approve(c.getComplaintId(), null, operator()).getStatusCode().value()).isEqualTo(200);
        verify(email).sendResponse(any(CaseRecord.class));
    }

    @Test
    void deliveryAuditWritesCommitInTheirOwnTransaction() {
        CaseRecord c = createCase("IN_REVIEW", "NORMAL", false);
        doAnswer(invocation -> {
            events.save(new AuditEvent(c.getComplaintId(), "email", "Simulated delivery recorded"));
            return null;
        }).when(email).sendResponse(any(CaseRecord.class));
        assertThat(controller.approve(c.getComplaintId(), null, operator()).getStatusCode().value()).isEqualTo(200);
        assertThat(events.findByComplaintIdOrderByCreatedAtAsc(c.getComplaintId()))
                .anySatisfy(e -> {
                    assertThat(e.getPhase()).isEqualTo("email");
                    assertThat(e.getDetail()).isEqualTo("Simulated delivery recorded");
                });
    }

    private void assertRefusal(CaseRecord c) {
        assertThat(events.findByComplaintIdOrderByCreatedAtAsc(c.getComplaintId()))
                .singleElement().satisfies(e -> {
                    assertThat(e.getPhase()).isEqualTo("maker-checker");
                    assertThat(e.getDetail()).contains("operator-test", "REFUSED");
                });
    }

    private CaseRecord createCase(String status, String urgency, boolean escalated) {
        CaseRecord c = new CaseRecord();
        c.setComplaintId(UUID.randomUUID().toString());
        c.setTrackingToken(UUID.randomUUID().toString());
        c.setCreatedAt(Instant.now());
        c.setCustomerName("Test Customer");
        c.setComplaintText("Please investigate this complaint.");
        c.setDraftSubject("Reply");
        c.setDraftBody("We are reviewing your complaint.");
        c.setFinalBody("SENT".equals(status) ? "Original reply" : null);
        c.setStatus(status);
        c.setUrgency(urgency);
        c.setEscalate(escalated);
        return cases.saveAndFlush(c);
    }

    private MockHttpServletRequest operator() {
        return request("operator-test", ApiKeyFilter.ROLE_OPERATOR);
    }

    private MockHttpServletRequest supervisor() {
        return request("supervisor-test", ApiKeyFilter.ROLE_SUPERVISOR);
    }

    private MockHttpServletRequest request(String actor, String role) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute(ApiKeyFilter.ATTR_ACTOR, actor);
        http.setAttribute(ApiKeyFilter.ATTR_ROLES, Set.of(role));
        return http;
    }
}
