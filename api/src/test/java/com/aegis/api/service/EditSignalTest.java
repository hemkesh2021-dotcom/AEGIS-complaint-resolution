package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The learning-loop signal must order edits sensibly: none > light > rewrite. */
class EditSignalTest {

    @Test
    void identicalTextScoresOne() {
        assertEquals(1.0, EditSignal.similarity("Dear customer, we fixed it.", "Dear customer, we fixed it."));
    }

    @Test
    void completeRewriteScoresNearZero() {
        assertTrue(EditSignal.similarity("alpha beta gamma", "delta epsilon zeta") == 0.0);
    }

    @Test
    void lightEditScoresBetween() {
        String draft = "We have opened an investigation into the disputed charge and will respond by August.";
        String light = "We have opened an investigation into the disputed charge and will reply by August.";
        double s = EditSignal.similarity(draft, light);
        assertTrue(s > 0.8 && s < 1.0, "light edit should score high but not 1.0: " + s);
    }

    @Test
    void heavierEditScoresLowerThanLighter() {
        String draft = "We have opened an investigation into the disputed charge.";
        String light = "We have opened an investigation into this disputed charge.";
        String heavy = "Your case was reviewed; a provisional credit is on the way, expect news soon.";
        assertTrue(EditSignal.similarity(draft, light) > EditSignal.similarity(draft, heavy));
    }

    @Test
    void nullSafe() {
        assertEquals(1.0, EditSignal.similarity(null, null));
        assertEquals(0.0, EditSignal.similarity("text", null));
        assertEquals(1.0, EditSignal.similarity("", ""));
    }
}
