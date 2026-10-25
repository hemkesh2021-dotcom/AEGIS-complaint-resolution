package com.aegis.api.service;

import com.aegis.api.dto.ComplaintResponse;
import com.aegis.api.entity.AuditEvent;
import com.aegis.api.entity.CaseMessage;
import com.aegis.api.entity.CaseRecord;
import com.aegis.api.repo.AuditEventRepository;
import com.aegis.api.repo.CaseCreationRepository;
import com.aegis.api.repo.CaseMessageRepository;
import com.aegis.api.repo.CaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline insert-only intake tests with real database constraints and transaction boundaries. */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@Import({AuditService.class, CaseCreationRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IntakeCreationTest {
    @Autowired AuditService audit;
    @Autowired CaseRepository cases;
    @Autowired CaseMessageRepository messages;
    @MockitoSpyBean AuditEventRepository events;
    @MockitoBean CaseIntelligenceService intelligence;

    @BeforeEach
    void clean() {
        messages.deleteAll();
        events.deleteAll();
        cases.deleteAll();
        when(intelligence.embedJson(anyString())).thenReturn("[]");
    }

    @Test
    void duplicateCannotOverwriteSentCaseTokenCustomerReplyAuditOrThread() {
        CaseRecord original = new CaseRecord();
        original.setComplaintId("CMP-existing");
        original.setTrackingToken("TRK-original-secret");
        original.setCustomerName("Original customer");
        original.setCustomerEmail("original@example.invalid");
        original.setComplaintText("Original complaint");
        original.setCreatedAt(Instant.parse("2026-10-25T00:00:00Z"));
        original.setStatus("SENT");
        original.setFinalSubject("Approved subject");
        original.setFinalBody("Immutable approved reply");
        original.setFinalSummary("Approved summary");
        original.setDraftBody("Original draft");
        original = cases.saveAndFlush(original);
        AuditEvent priorAudit = events.saveAndFlush(new AuditEvent(original.getComplaintId(), "approved", "Original approval"));
        priorAudit = events.findById(priorAudit.getId()).orElseThrow();
        CaseMessage priorMessage = messages.saveAndFlush(new CaseMessage(original.getComplaintId(), "Update", "Original follow-up"));
        priorMessage = messages.findById(priorMessage.getId()).orElseThrow();

        assertThatThrownBy(() -> record("CMP-existing", "Replacement complaint"))
                .isInstanceOf(ComplaintConflictException.class);

        assertThat(cases.findById(original.getComplaintId()).orElseThrow())
                .usingRecursiveComparison().isEqualTo(original);
        assertThat(cases.findByTrackingToken("TRK-original-secret")).isPresent();
        assertThat(cases.count()).isEqualTo(1);
        assertThat(events.findByComplaintIdOrderByCreatedAtAsc(original.getComplaintId()))
                .singleElement().usingRecursiveComparison().isEqualTo(priorAudit);
        assertThat(messages.findByComplaintIdOrderByCreatedAtAsc(original.getComplaintId()))
                .singleElement().usingRecursiveComparison().isEqualTo(priorMessage);
    }

    @Test
    void newIdentifierCreatesCaseAndCompleteAudit() {
        record("CMP-new", "A new complaint");
        CaseRecord saved = cases.findById("CMP-new").orElseThrow();
        assertThat(saved.getComplaintText()).isEqualTo("A new complaint");
        assertThat(saved.getStatus()).isEqualTo("IN_REVIEW");
        assertThat(saved.getTrackingToken()).matches("TRK-[0-9A-F]{32}");
        assertThat(saved.getCustomerName()).isEqualTo("New customer");
        assertThat(events.findByComplaintIdOrderByCreatedAtAsc("CMP-new"))
                .extracting(AuditEvent::getPhase)
                .containsExactly("classify", "compliance", "retrieve", "draft", "verification", "escalation");
    }

    @Test
    void concurrentSameIdentifierHasOneWinnerAndOneConflict() throws Exception {
        CyclicBarrier bothPreparing = new CyclicBarrier(2);
        when(intelligence.embedJson(anyString())).thenAnswer(invocation -> {
            bothPreparing.await(10, TimeUnit.SECONDS);
            return "[]";
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tryRecord("First complaint"));
            var second = executor.submit(() -> tryRecord("Second complaint"));
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(cases.count()).isEqualTo(1);
        assertThat(cases.findById("CMP-race").orElseThrow().getComplaintText())
                .isIn("First complaint", "Second complaint");
        assertThat(events.findByComplaintIdOrderByCreatedAtAsc("CMP-race")).hasSize(6);
    }

    @Test
    void auditFailureRollsBackCaseAndEarlierAuditRows() {
        doThrow(new IllegalStateException("Simulated audit persistence failure"))
                .when(events).save(argThat(event -> event != null && "draft".equals(event.getPhase())));
        assertThatThrownBy(() -> record("CMP-rollback", "A new complaint"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Simulated audit persistence failure");
        assertThat(cases.findById("CMP-rollback")).isEmpty();
        assertThat(events.findByComplaintIdOrderByCreatedAtAsc("CMP-rollback")).isEmpty();
    }

    private boolean tryRecord(String text) {
        try {
            record("CMP-race", text);
            return true;
        } catch (ComplaintConflictException expected) {
            return false;
        }
    }

    private void record(String id, String text) {
        audit.record(response(id), text, "New customer", "new@example.invalid", List.of(), "[]");
    }

    static ComplaintResponse response(String id) {
        return new ComplaintResponse(id,
                new ComplaintResponse.Prediction("billing", 0.9),
                new ComplaintResponse.Compliance("billing", "Clause", "Tomorrow", "Next week", List.of(), false),
                new ComplaintResponse.Draft("Draft subject", "Draft body", "template"), List.of(),
                new ComplaintResponse.Escalation(false, "NORMAL", "No escalation"),
                "NORMAL", "IN_REVIEW", "WEB", "English");
    }
}
