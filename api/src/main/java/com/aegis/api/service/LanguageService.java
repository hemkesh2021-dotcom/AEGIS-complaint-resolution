package com.aegis.api.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Multilingual intake. Customers complain in their own language; the pipeline
 * (DistilBERT, risk keywords, BM25, regulation KB) works in English.
 *
 * <p>Detection is deterministic and dependency-free: non-Latin scripts are
 * identified by Unicode block, Latin-script languages by stopword voting.
 * Translation uses the (redacted) LLM path; if no model is available the
 * pipeline proceeds on the original text — degraded, never broken.
 */
@Service
public class LanguageService {

    private static final Logger log = LoggerFactory.getLogger(LanguageService.class);

    public record Detection(String code, String name) {
    }

    private static final Detection ENGLISH = new Detection("en", "English");

    /** Unicode-script languages (a single confident char range is enough). */
    private static final Map<String, Detection> SCRIPTS = Map.of(
            "devanagari", new Detection("hi", "Hindi"),
            "kannada", new Detection("kn", "Kannada"),
            "tamil", new Detection("ta", "Tamil"),
            "telugu", new Detection("te", "Telugu"),
            "bengali", new Detection("bn", "Bengali"),
            "arabic", new Detection("ar", "Arabic"),
            "cjk", new Detection("zh", "Chinese"),
            "hangul", new Detection("ko", "Korean"),
            "cyrillic", new Detection("ru", "Russian"));

    private static final Map<Detection, Set<String>> STOPWORDS = Map.of(
            new Detection("es", "Spanish"),
            words("el la los las una que de en mi con por para pero como esta hay fue banco cuenta tarjeta dinero pago nunca autoricé cargo"),
            new Detection("fr", "French"),
            words("le la les une des que je ne pas est dans mon avec pour mais compte banque carte argent jamais autorisé"),
            new Detection("de", "German"),
            words("der die das und ist nicht ein eine ich mein mit für aber konto bank karte geld nie autorisiert wurde"),
            new Detection("pt", "Portuguese"),
            words("o os uma que não em meu com para mas conta banco cartão dinheiro nunca autorizei cobrança foi"));

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final PiiRedactor redactor;

    public LanguageService(ObjectProvider<ChatModel> chatModelProvider, PiiRedactor redactor) {
        this.chatModelProvider = chatModelProvider;
        this.redactor = redactor;
    }

    public Detection detect(String text) {
        if (text == null || text.isBlank()) {
            return ENGLISH;
        }
        // 1 · script detection: count chars per relevant block
        int devanagari = 0, kannada = 0, tamil = 0, telugu = 0, bengali = 0,
            arabic = 0, cjk = 0, hangul = 0, cyrillic = 0, letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetter(ch)) {
                letters++;
            }
            if (ch >= 0x0900 && ch <= 0x097F) devanagari++;
            else if (ch >= 0x0C80 && ch <= 0x0CFF) kannada++;
            else if (ch >= 0x0B80 && ch <= 0x0BFF) tamil++;
            else if (ch >= 0x0C00 && ch <= 0x0C7F) telugu++;
            else if (ch >= 0x0980 && ch <= 0x09FF) bengali++;
            else if (ch >= 0x0600 && ch <= 0x06FF) arabic++;
            else if (ch >= 0x4E00 && ch <= 0x9FFF) cjk++;
            else if (ch >= 0xAC00 && ch <= 0xD7AF) hangul++;
            else if (ch >= 0x0400 && ch <= 0x04FF) cyrillic++;
        }
        if (letters == 0) {
            return ENGLISH;
        }
        double t = Math.max(3, letters * 0.15); // a modest share of the text decides
        if (devanagari > t) return SCRIPTS.get("devanagari");
        if (kannada > t) return SCRIPTS.get("kannada");
        if (tamil > t) return SCRIPTS.get("tamil");
        if (telugu > t) return SCRIPTS.get("telugu");
        if (bengali > t) return SCRIPTS.get("bengali");
        if (arabic > t) return SCRIPTS.get("arabic");
        if (cjk > t) return SCRIPTS.get("cjk");
        if (hangul > t) return SCRIPTS.get("hangul");
        if (cyrillic > t) return SCRIPTS.get("cyrillic");

        // 2 · Latin-script stopword voting
        Set<String> tokens = new HashSet<>(Arrays.asList(
                text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")));
        Detection best = ENGLISH;
        int bestHits = 2; // need at least 3 distinct stopwords to beat English
        for (var e : STOPWORDS.entrySet()) {
            int hits = 0;
            for (String w : e.getValue()) {
                if (tokens.contains(w)) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = e.getKey();
            }
        }
        return best;
    }

    /** Translate a complaint to English for the pipeline (redacted first; fail-soft). */
    public String translateToEnglish(String text, Detection from) {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null || text == null || text.isBlank()) {
            return null;
        }
        try {
            String out = ChatClient.create(model)
                    .prompt()
                    .system("You are a precise translator. Translate the user's text from "
                            + from.name() + " to English. Preserve all amounts, dates, and reference "
                            + "numbers exactly. Output ONLY the translation — no preamble, no notes.")
                    .user(redactor.redact(text))
                    .call()
                    .content();
            return out == null || out.isBlank() ? null : out.strip();
        } catch (Exception e) {
            log.warn("translation from {} failed ({}); pipeline proceeds on original text.",
                    from.code(), e.getMessage());
            return null;
        }
    }

    private static Set<String> words(String s) {
        return Set.of(s.split(" "));
    }
}
