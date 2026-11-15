package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.env.MockEnvironment;
import java.util.List;

class ProviderChatModelTest {
    HttpServer server;
    String base;
    AtomicReference<String> body = new AtomicReference<>(), auth = new AtomicReference<>(), version = new AtomicReference<>();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }
    @AfterEach void stop() { server.stop(0); }
    MockEnvironment env(String provider) {
        return new MockEnvironment().withProperty("AEGIS_LLM_PROVIDER", provider)
            .withProperty("AEGIS_LLM_MODEL", "test-model").withProperty("AEGIS_LLM_BASE_URL", base);
    }
    void reply(String path, int code, String payload) {
        server.createContext(path, exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (auth.get() == null) auth.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
    }
    @Test void anthropicUsesNativeMessagesAndSystemField() throws Exception {
        reply("/v1/messages", 200, "{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"},{\"type\":\"text\",\"text\":\" world\"}]}");
        var model = new ProviderChatModel(env("anthropic").withProperty("ANTHROPIC_API_KEY", "claude-test"));
        assertEquals("Hello world", model.call(new Prompt(List.of(new SystemMessage("Be precise"), new UserMessage("Hello")))).getResult().getOutput().getText());
        var data = new ObjectMapper().readTree(body.get());
        assertEquals("Be precise\n", data.path("system").asText());
        assertEquals(1, data.path("messages").size());
        assertEquals(2048, data.path("max_tokens").asInt());
        assertEquals("claude-test", auth.get()); assertEquals("2023-06-01", version.get());
        assertFalse(model.status().toString().contains("claude-test"));
    }
    @Test void compatibleProviderUsesOnlyItsOwnKey() {
        reply("/v1/chat/completions", 200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
        var model = new ProviderChatModel(env("groq").withProperty("GROQ_API_KEY", "groq-test").withProperty("OPENAI_API_KEY", "wrong-key"));
        assertEquals("OK", model.call(new Prompt("Hello")).getResult().getOutput().getText());
        assertEquals("Bearer groq-test", auth.get());
        assertEquals(1L, model.status().get("successfulCalls"));
    }
    @Test void missingKeyDoesNotUseAnotherProvidersCredential() {
        var model = new ProviderChatModel(env("anthropic").withProperty("OPENAI_API_KEY", "wrong-key"));
        assertFalse(model.configured());
        assertThrows(IllegalStateException.class, () -> model.call(new Prompt("Hello")));
        assertNull(body.get());
    }
    @Test void remoteErrorsAreSanitizedAndCounted() {
        reply("/v1/chat/completions", 401, "secret echoed credential and complaint");
        var model = new ProviderChatModel(env("openai").withProperty("OPENAI_API_KEY", "test"));
        var error = assertThrows(IllegalStateException.class, () -> model.call(new Prompt("private")));
        assertFalse(error.getMessage().contains("secret")); assertNull(error.getCause());
        assertEquals(1L, model.status().get("failedCalls"));
    }
    @Test void truncatedAndEmptyResponsesFailClosed() {
        reply("/v1/messages", 200, "{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}");
        var model = new ProviderChatModel(env("anthropic").withProperty("ANTHROPIC_API_KEY", "test"));
        assertThrows(IllegalStateException.class, () -> model.call(new Prompt("Hello")));
    }
    @Test void unsafeConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderChatModel(env("unknown")));
        assertThrows(IllegalArgumentException.class, () -> new ProviderChatModel(env("custom").withProperty("AEGIS_LLM_BASE_URL", "http://remote.example/v1")));
        assertThrows(IllegalArgumentException.class, () -> new ProviderChatModel(env("custom").withProperty("AEGIS_LLM_BASE_URL", "https://key@remote.example/v1")));
    }
    @Test void templateModeAndLegacyNimDefault() {
        assertFalse(new ProviderChatModel(env("template")).configured());
        var model = new ProviderChatModel(new MockEnvironment().withProperty("NIM_API_KEY", "nim-test").withProperty("AEGIS_LLM_MODEL", ""));
        assertTrue(model.configured()); assertTrue(model.source().startsWith("nim /"));
    }
    @Test void realAdapterRunsThroughDraftServiceAndFallsBackOnFailure() throws Exception {
        reply("/v1/messages", 200, "{\"content\":[{\"type\":\"text\",\"text\":\"Subject: Update\\nDear {CUSTOMER_NAME}, we are reviewing your complaint.\"}]}");
        var model=new ProviderChatModel(env("anthropic").withProperty("ANTHROPIC_API_KEY","test"));
        var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("chat",model);
        var service=new DraftService(factory.getBeanProvider(org.springframework.ai.chat.model.ChatModel.class),new PiiRedactor());
        service.load();
        var compliance=new com.aegis.api.dto.ComplaintResponse.Compliance("Credit card","Reg Z","2026-12-01T12:00","2026-12-15T12:00",List.of(),false);
        var draft=service.draft("Jane Example","CMP-TEST","Credit card","Jane Example reported a billing error",compliance,null);
        assertTrue(draft.source().startsWith("anthropic /"));assertTrue(draft.body().contains("Jane Example"));
        assertFalse(body.get().contains("Jane Example"));
        server.removeContext("/v1/messages");reply("/v1/messages",503,"private-error");
        assertEquals("Template engine",service.draft("Jane Example","CMP-TEST","Credit card","billing error",compliance,null).source());
    }
}
