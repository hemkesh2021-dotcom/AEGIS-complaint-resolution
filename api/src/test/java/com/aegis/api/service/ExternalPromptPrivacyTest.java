package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aegis.api.dto.ComplaintResponse.Compliance;
import com.aegis.api.dto.ComplaintResponse.Draft;
import com.aegis.api.service.RagRetriever.RetrievedContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/** Exercises the real ChatClient boundary with a purely in-memory model: no API calls. */
class ExternalPromptPrivacyTest {

    private static final String IDENTIFIERS =
            " email person@example.test phone 212-555-0199 SSN 123-45-6789"
            + " card 4111 1111 1111 1111 account 1234567890";

    static Stream<Arguments> names() {
        return Stream.of(
                Arguments.of("张伟", "我是张伟，我要投诉银行。"),
                Arguments.of("A. Rao", "A. RAO disputes a bank charge."));
    }

    @ParameterizedTest
    @MethodSource("names")
    void translationRedactsBeforeCallingTheModel(String name, String narrative) {
        CapturingModel model = new CapturingModel("Translated {CUSTOMER_NAME} complaint.");
        LanguageService service = new LanguageService(provider(model), new PiiRedactor());

        String translated = service.translateToEnglish(narrative + IDENTIFIERS,
                new LanguageService.Detection("zh", "Chinese"), name);

        assertEquals("Translated {CUSTOMER_NAME} complaint.", translated,
                "translation must use the fake model rather than a fail-soft fallback");
        assertRedacted(model, name);
    }

    @ParameterizedTest
    @MethodSource("names")
    void draftingRedactsEveryUserPromptFieldAndRestoresNameOnlyLocally(String name, String narrative) {
        CapturingModel model = new CapturingModel(
                "Subject: Complaint update\nDear {CUSTOMER_NAME}, we will review your complaint.");
        DraftService service = new DraftService(provider(model), new PiiRedactor());
        Compliance compliance = new Compliance("Credit card", "Clause: " + narrative + IDENTIFIERS,
                "2026-07-10T10:00", "2026-08-14T10:00", List.of(), false);
        RetrievedContext context = new RetrievedContext("Context: " + narrative + IDENTIFIERS,
                List.of(), List.of(), List.of());

        Draft draft = service.draft(name, "CMP-TEST " + narrative, "Category: " + narrative,
                narrative + IDENTIFIERS, compliance, context);

        assertEquals("NVIDIA NIM (RAG)", draft.source(), "fallback must not hide a broken model path");
        assertEquals("Complaint update", draft.subject());
        assertEquals("Dear " + name + ", we will review your complaint.", draft.body());
        assertRedacted(model, name);
    }

    @ParameterizedTest
    @MethodSource("names")
    void summarizationUsesTheSameNamePolicyAndRestoresNameOnlyLocally(String name, String narrative) {
        CapturingModel model = new CapturingModel("We will update {CUSTOMER_NAME} tomorrow.");
        DraftService service = new DraftService(provider(model), new PiiRedactor());

        String summary = service.summarize(narrative + IDENTIFIERS, name);

        assertEquals("We will update " + name + " tomorrow.", summary,
                "summary must come from the fake model, not the original-text fallback");
        assertRedacted(model, name);
    }

    private static void assertRedacted(CapturingModel model, String name) {
        assertEquals(1, model.prompts.size(), "exactly one in-memory model invocation expected");
        String sent = model.prompts.get(0).getInstructions().stream()
                .map(message -> message.getText()).collect(Collectors.joining("\n"));
        assertFalse(sent.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)), sent);
        assertTrue(sent.contains("{CUSTOMER_NAME}"), sent);
        for (String identifier : List.of("person@example.test", "212-555-0199", "123-45-6789",
                "4111 1111 1111 1111", "1234567890")) {
            assertFalse(sent.contains(identifier), "identifier leaked to model: " + identifier);
        }
    }

    private static ObjectProvider<ChatModel> provider(ChatModel model) {
        return new ObjectProvider<>() {
            @Override
            public ChatModel getObject() {
                return model;
            }

            @Override
            public ChatModel getIfAvailable() {
                return model;
            }
        };
    }

    private static final class CapturingModel implements ChatModel {
        private final List<Prompt> prompts = new ArrayList<>();
        private final String response;

        private CapturingModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }
    }
}
