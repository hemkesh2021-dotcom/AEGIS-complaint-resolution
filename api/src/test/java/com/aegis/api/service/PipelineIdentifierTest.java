package com.aegis.api.service;

import com.aegis.api.dto.ComplaintRequest;
import com.aegis.api.dto.ComplaintResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Identifier generation exercised without any model, retrieval, email, or database calls. */
class PipelineIdentifierTest {
    private PipelineService pipeline;
    private AuditService audit;

    @BeforeEach
    void setup() {
        ClassifierClient classifier = mock(ClassifierClient.class);
        ComplianceEngine compliance = mock(ComplianceEngine.class);
        RagRetriever retriever = mock(RagRetriever.class);
        DraftService drafting = mock(DraftService.class);
        EscalationDecider escalation = mock(EscalationDecider.class);
        UrgencyRouter urgency = mock(UrgencyRouter.class);
        LanguageService language = mock(LanguageService.class);
        audit = mock(AuditService.class);
        var comp = new ComplaintResponse.Compliance("billing", "Clause", "Tomorrow", "Next week", List.of(), false);
        when(language.detect(anyString())).thenReturn(new LanguageService.Detection("en", "English"));
        when(classifier.classify(anyString())).thenReturn(new ComplaintResponse.Prediction("billing", 0.9));
        when(compliance.compute(eq("billing"), isNull(), anyString())).thenReturn(comp);
        when(drafting.draft(anyString(), anyString(), eq("billing"), anyString(), eq(comp), isNull(), isNull()))
                .thenReturn(new ComplaintResponse.Draft("Reply", "We are reviewing your complaint.", "template"));
        when(escalation.decide(anyList(), anyDouble()))
                .thenReturn(new ComplaintResponse.Escalation(false, "NORMAL", "No escalation"));
        when(urgency.tier(anyList(), anyDouble(), anyBoolean())).thenReturn("NORMAL");
        pipeline = new PipelineService(classifier, compliance, retriever, drafting, new DraftVerifier(),
                escalation, urgency, audit, language);
    }

    @Test
    void generatedIdentifiersContainFullRandomUuid() {
        var request = new ComplaintRequest("Complaint", "Customer", null, null, null, null);
        String first = pipeline.run(request, "WEB").complaintId();
        String second = pipeline.run(request, "WEB").complaintId();
        assertThat(first).matches("CMP-[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}");
        assertThat(UUID.fromString(first.substring(4)).version()).isEqualTo(4);
        assertThat(second).isNotEqualTo(first);
        verify(audit, times(2)).record(any(), eq("Complaint"), eq("Customer"), isNull(), anyList(), anyString());
    }

    @Test
    void authenticatedIntakeMayRetainCallerIdentifierForInsertOnlyPersistence() {
        var request = new ComplaintRequest("Complaint", "Customer", null, "REF-console", null, null);
        assertThat(pipeline.run(request, "CONSOLE").complaintId()).isEqualTo("REF-console");
        verify(audit).record(argThat(response -> "REF-console".equals(response.complaintId())),
                eq("Complaint"), eq("Customer"), isNull(), anyList(), anyString());
    }
}
