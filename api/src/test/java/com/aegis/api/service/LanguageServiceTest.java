package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

/** Detection must be right before translation can be — script blocks + stopword voting. */
class LanguageServiceTest {

    private final LanguageService svc = new LanguageService(new ObjectProvider<>() {
        @Override
        public ChatModel getObject() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChatModel getIfAvailable() {
            return null;
        }
    }, new PiiRedactor());

    @Test
    void detectsHindiByScript() {
        assertEquals("hi", svc.detect(
                "मेरे क्रेडिट कार्ड पर $420 का अनधिकृत शुल्क लगाया गया है और बैंक ने कोई जवाब नहीं दिया।").code());
    }

    @Test
    void detectsKannadaByScript() {
        assertEquals("kn", svc.detect(
                "ನನ್ನ ಖಾತೆಯಿಂದ ಅನಧಿಕೃತ ಹಣ ವರ್ಗಾವಣೆ ಆಗಿದೆ ಮತ್ತು ಬ್ಯಾಂಕ್ ಉತ್ತರಿಸುತ್ತಿಲ್ಲ.").code());
    }

    @Test
    void detectsSpanishByStopwords() {
        assertEquals("es", svc.detect(
                "Hay un cargo en mi tarjeta de crédito que nunca autoricé y el banco no me responde. "
                + "Quiero que me devuelvan el dinero de la cuenta.").code());
    }

    @Test
    void detectsGermanByStopwords() {
        assertEquals("de", svc.detect(
                "Auf meinem Konto ist eine Abbuchung, die ich nie autorisiert habe, und die Bank "
                + "antwortet nicht. Das Geld wurde nicht zurückgebucht und ich bin sehr unzufrieden.").code());
    }

    @Test
    void englishStaysEnglish() {
        assertEquals("en", svc.detect(
                "There is an unauthorized charge of $420 on my credit card and the bank has not responded.").code());
        assertEquals("en", svc.detect("").code());
        assertEquals("en", svc.detect(null).code());
    }

    @Test
    void translationWithoutModelFailsSoft() {
        assertEquals(null, svc.translateToEnglish("hola", new LanguageService.Detection("es", "Spanish")));
    }
}
