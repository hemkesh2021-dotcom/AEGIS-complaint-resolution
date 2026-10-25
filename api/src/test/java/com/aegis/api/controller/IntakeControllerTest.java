package com.aegis.api.controller;

import com.aegis.api.dto.ComplaintRequest;
import com.aegis.api.dto.ComplaintResponse;
import com.aegis.api.entity.CaseRecord;
import com.aegis.api.service.ComplaintConflictException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.aegis.api.repo.CaseMessageRepository;
import com.aegis.api.repo.CaseRepository;
import com.aegis.api.service.CaseEventsPublisher;
import com.aegis.api.service.EmailService;
import com.aegis.api.service.PipelineService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntakeControllerTest {
    @Test
    void publicIntakeDiscardsCallerSuppliedIdentifierBeforePipeline() {
        PipelineService pipeline = mock(PipelineService.class);
        CaseRepository cases = mock(CaseRepository.class);
        EmailService email = mock(EmailService.class);
        IntakeController controller = new IntakeController(pipeline, cases,
                mock(CaseMessageRepository.class), email, mock(CaseEventsPublisher.class));
        ComplaintRequest supplied = new ComplaintRequest("Complaint", "Customer", "customer@example.invalid",
                "CMP-existing-victim", "2026-10-25T00:00", "WEB");
        ComplaintResponse generated = new ComplaintResponse("CMP-server-generated", null,
                new ComplaintResponse.Compliance("billing", "Clause", "Tomorrow", "Next week", List.of(), false),
                null, List.of(), null, "NORMAL", "IN_REVIEW", "WEB", "English");
        when(pipeline.run(any(ComplaintRequest.class), eq("WEB"))).thenReturn(generated);
        CaseRecord created = new CaseRecord();
        created.setComplaintId("CMP-server-generated");
        created.setTrackingToken("TRK-new-customer-only");
        when(cases.findById("CMP-server-generated")).thenReturn(Optional.of(created));

        var acknowledgement = controller.intake(supplied);

        ArgumentCaptor<ComplaintRequest> captured = ArgumentCaptor.forClass(ComplaintRequest.class);
        verify(pipeline).run(captured.capture(), eq("WEB"));
        assertThat(captured.getValue().complaintId()).isNull();
        assertThat(captured.getValue()).usingRecursiveComparison().ignoringFields("complaintId").isEqualTo(supplied);
        assertThat(acknowledgement.complaintId()).isEqualTo("CMP-server-generated");
        verify(cases, never()).findById("CMP-existing-victim");
        assertThat(acknowledgement.trackingToken()).isEqualTo("TRK-new-customer-only");
        verify(email).sendAcknowledgement(same(created));
        verifyNoMoreInteractions(email);
    }

    @Test
    void duplicateConsoleReferenceMapsToHttpConflictWithoutLeakingExistingCase() throws Exception {
        PipelineService pipeline = mock(PipelineService.class);
        when(pipeline.run(any(ComplaintRequest.class), eq("CONSOLE")))
                .thenThrow(new ComplaintConflictException(new IllegalStateException("TRK-existing-private-token")));
        var mvc = MockMvcBuilders.standaloneSetup(new ComplaintController(pipeline)).build();
        var response = mvc.perform(post("/api/complaints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Complaint\",\"complaintId\":\"CMP-existing\"}"))
                .andExpect(status().isConflict()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("TRK-existing-private-token", "CMP-existing");
        assertThat(response.getErrorMessage()).isNull();
    }

    @Test
    void failedPublicCreationCannotAcknowledgeAnExistingCaseOrSendMail() {
        PipelineService pipeline = mock(PipelineService.class);
        CaseRepository cases = mock(CaseRepository.class);
        EmailService email = mock(EmailService.class);
        IntakeController controller = new IntakeController(pipeline, cases,
                mock(CaseMessageRepository.class), email, mock(CaseEventsPublisher.class));
        when(pipeline.run(any(ComplaintRequest.class), eq("WEB")))
                .thenThrow(new ComplaintConflictException(new IllegalStateException("Duplicate reference")));
        var request = new ComplaintRequest("Complaint", "Customer", null, "CMP-victim", null, null);
        assertThatThrownBy(() -> controller.intake(request)).isInstanceOf(ComplaintConflictException.class);
        verifyNoInteractions(cases, email);
    }
}
