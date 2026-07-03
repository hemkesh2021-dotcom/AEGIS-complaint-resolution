package com.aegis.api.service;

import com.aegis.api.dto.ComplaintResponse.Prediction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Phase 2 — calls the Python DistilBERT service over plain HTTP using the JDK's
 * {@link HttpClient}, so there are no framework request-body quirks (RestClient's
 * body serialization changed across Spring Boot versions and kept dropping the
 * payload). Falls back to a keyword heuristic if the service is unset or
 * unreachable, so the pipeline always returns a prediction.
 */
@Service
public class ClassifierClient {

    private static final Logger log = LoggerFactory.getLogger(ClassifierClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String url;
    // Pin HTTP/1.1: the default (HTTP/2) attempts an h2c upgrade over cleartext
    // that uvicorn doesn't support, which drops the request body (FastAPI then
    // sees an empty body and returns 422).
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public ClassifierClient(@Value("${classifier.url:}") String url) {
        this.url = url == null ? "" : url.trim();
    }

    public Prediction classify(String text) {
        if (!url.isBlank()) {
            try {
                String payload = MAPPER.writeValueAsString(Map.of("text", text));
                HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/classify"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 == 2) {
                    JsonNode n = MAPPER.readTree(resp.body());
                    String label = n.path("label").asText(null);
                    if (label != null && !label.isBlank()) {
                        return new Prediction(label, n.path("confidence").asDouble());
                    }
                }
                log.warn("Classifier returned HTTP {} — using heuristic fallback.", resp.statusCode());
            } catch (Exception e) {
                log.warn("Classifier call failed [{}: {}] at {} — using heuristic fallback.",
                        e.getClass().getSimpleName(), e.getMessage(), url);
            }
        }
        return heuristic(text);
    }

    // CFPB product categories — mirror of the Python service's fallback.
    private static final Map<String, List<String>> KEYWORDS = Map.ofEntries(
            Map.entry("Checking or savings account", List.of("checking account", "savings account", "overdraft", "debit card", "account fee", "deposit", "withdrawal")),
            Map.entry("Credit card", List.of("credit card", "annual fee", "apr", "interest rate", "late fee", "statement", "billing", "rewards")),
            Map.entry("Credit reporting or other personal consumer reports", List.of("credit report", "credit score", "equifax", "experian", "transunion", "inaccurate", "reporting")),
            Map.entry("Debt collection", List.of("debt collector", "collection agency", "collector", "i owe", "garnish", "repossess", "validation")),
            Map.entry("Debt or credit management", List.of("debt management", "debt settlement", "credit repair", "credit counseling", "consolidation")),
            Map.entry("Money transfer, virtual currency, or money service", List.of("money transfer", "wire transfer", "remittance", "western union", "paypal", "venmo", "crypto", "bitcoin")),
            Map.entry("Mortgage", List.of("mortgage", "escrow", "foreclosure", "loan modification", "refinance", "servicer", "home loan")),
            Map.entry("Payday loan, title loan, personal loan, or advance loan", List.of("payday loan", "title loan", "personal loan", "installment loan", "advance loan", "cash advance")),
            Map.entry("Prepaid card", List.of("prepaid card", "reloadable card", "prepaid account", "card balance", "gift card")),
            Map.entry("Student loan", List.of("student loan", "tuition loan", "sallie mae", "navient", "forbearance", "deferment")),
            Map.entry("Vehicle loan or lease", List.of("auto loan", "car loan", "vehicle loan", "auto lease", "repossession", "auto finance")));

    private Prediction heuristic(String text) {
        String t = text == null ? "" : text.toLowerCase();
        String best = "Credit reporting or other personal consumer reports";
        int bestScore = 0;
        for (var e : KEYWORDS.entrySet()) {
            int score = 0;
            for (String kw : e.getValue()) {
                if (t.contains(kw)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        double confidence = bestScore > 0 ? Math.min(0.55 + 0.1 * bestScore, 0.95) : 0.35;
        return new Prediction(best, confidence);
    }
}
