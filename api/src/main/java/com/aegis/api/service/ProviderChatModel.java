package com.aegis.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Single server-configured destination. Credentials never cross provider boundaries. */
@Component
public class ProviderChatModel implements ChatModel {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final String provider, model, key, version;
    private final URI endpoint;
    private final Duration timeout;
    private final AtomicLong successes = new AtomicLong(), failures = new AtomicLong();
    private volatile String lastOutcome = "not_called";

    public ProviderChatModel(Environment env) {
        provider = env.getProperty("AEGIS_LLM_PROVIDER", "nim").trim().toLowerCase(Locale.ROOT);
        String[] config = switch (provider) {
            case "nim" -> new String[]{"NIM_API_KEY", "https://integrate.api.nvidia.com/v1", env.getProperty("NIM_CHAT_MODEL", "openai/gpt-oss-120b")};
            case "anthropic" -> new String[]{"ANTHROPIC_API_KEY", "https://api.anthropic.com/v1", ""};
            case "openai" -> new String[]{"OPENAI_API_KEY", "https://api.openai.com/v1", ""};
            case "gemini" -> new String[]{"GEMINI_API_KEY", "https://generativelanguage.googleapis.com/v1beta/openai", ""};
            case "groq" -> new String[]{"GROQ_API_KEY", "https://api.groq.com/openai/v1", ""};
            case "openrouter" -> new String[]{"OPENROUTER_API_KEY", "https://openrouter.ai/api/v1", ""};
            case "ollama" -> new String[]{"OLLAMA_API_KEY", "http://localhost:11434/v1", ""};
            case "custom" -> new String[]{"AEGIS_LLM_API_KEY", "", ""};
            case "template" -> new String[]{"AEGIS_UNUSED_KEY", "https://localhost/v1", ""};
            default -> throw new IllegalArgumentException("Unsupported AEGIS_LLM_PROVIDER");
        };
        String requestedModel = env.getProperty("AEGIS_LLM_MODEL", "").trim();
        model = requestedModel.isBlank() ? config[2] : requestedModel;
        key = env.getProperty(config[0], "").trim();
        version = env.getProperty("ANTHROPIC_VERSION", "2023-06-01");
        String base = env.getProperty("AEGIS_LLM_BASE_URL", "").trim();
        if (base.isEmpty()) base = provider.equals("nim") ? env.getProperty("NIM_BASE_URL", config[1]) : config[1];
        base = base.replaceAll("/+$", "");
        if (provider.equals("nim") && !base.endsWith("/v1")) base += "/v1";
        if (base.isBlank()) throw new IllegalArgumentException("AEGIS_LLM_BASE_URL is required for custom providers");
        URI root = URI.create(base);
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(root.getHost() == null ? "" : root.getHost());
        if (root.getHost() == null || root.getUserInfo() != null || root.getQuery() != null || root.getFragment() != null
                || !("https".equals(root.getScheme()) || ("http".equals(root.getScheme())
                && (local || env.getProperty("AEGIS_LLM_ALLOW_HTTP", Boolean.class, false))))) {
            throw new IllegalArgumentException("Provider base URL must use HTTPS (HTTP requires a local host or explicit opt-in)");
        }
        endpoint = URI.create(base + (provider.equals("anthropic") ? "/messages" : "/chat/completions"));
        int seconds = env.getProperty("AEGIS_LLM_TIMEOUT_SECONDS", Integer.class, 30);
        if (seconds < 1 || seconds > 120) throw new IllegalArgumentException("LLM timeout must be 1-120 seconds");
        timeout = Duration.ofSeconds(seconds);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(seconds, 10)))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public boolean configured() {
        return !provider.equals("template") && !model.isBlank()
                && (provider.equals("ollama") || (!key.isBlank() && !key.equals("nvapi-not-set")));
    }

    public String source() { return provider + " / " + model + " (RAG)"; }

    public Map<String, Object> status() {
        return Map.of("provider", provider, "model", model, "configured", configured(),
                "successfulCalls", successes.get(), "failedCalls", failures.get(), "lastOutcome", lastOutcome,
                "timeoutSeconds", timeout.toSeconds(), "fallback", "Template engine");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (!configured()) throw new IllegalStateException("AI provider is not configured; template fallback active");
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            StringBuilder system = new StringBuilder();
            for (var message : prompt.getInstructions()) {
                String role = message.getMessageType().getValue();
                if (provider.equals("anthropic") && role.equals("system")) system.append(message.getText()).append('\n');
                else messages.add(Map.of("role", role, "content", message.getText()));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model); body.put("messages", messages);
            body.put(provider.equals("openai") ? "max_completion_tokens" : "max_tokens", 2048);
            if (provider.equals("anthropic")) body.put("system", system.toString());
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json");
            if (provider.equals("anthropic")) request.header("x-api-key", key).header("anthropic-version", version);
            else if (!key.isBlank()) request.header("Authorization", "Bearer " + key);
            var response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("Provider HTTP " + response.statusCode());
            JsonNode result = json.readTree(response.body());
            String text;
            if (provider.equals("anthropic")) {
                if ("max_tokens".equals(result.path("stop_reason").asText())) throw new IllegalStateException("Truncated response");
                StringBuilder out = new StringBuilder();
                for (var block : result.path("content")) if ("text".equals(block.path("type").asText())) out.append(block.path("text").asText());
                text = out.toString();
            } else {
                var choice = result.path("choices").path(0);
                if ("length".equals(choice.path("finish_reason").asText())) throw new IllegalStateException("Truncated response");
                text = choice.path("message").path("content").asText("");
            }
            if (text.isBlank()) throw new IllegalStateException("Empty provider response");
            successes.incrementAndGet(); lastOutcome = "success";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            failures.incrementAndGet(); lastOutcome = "failed";
            // Never expose a remote error body, credential, or prompt to logs/callers.
            throw new IllegalStateException("AI provider request failed; template fallback active");
        }
    }
}
