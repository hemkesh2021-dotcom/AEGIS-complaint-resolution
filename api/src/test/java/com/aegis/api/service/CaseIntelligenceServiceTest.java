package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Cosine is the heart of similar-case search — exact values, no surprises. */
class CaseIntelligenceServiceTest {

    @Test
    void identicalVectorsScoreOne() {
        float[] v = {0.3f, -0.5f, 0.8f};
        assertEquals(1.0, CaseIntelligenceService.cosine(v, v), 1e-9);
    }

    @Test
    void orthogonalVectorsScoreZero() {
        assertEquals(0.0, CaseIntelligenceService.cosine(
                new float[]{1, 0, 0}, new float[]{0, 1, 0}), 1e-9);
    }

    @Test
    void oppositeVectorsScoreMinusOne() {
        assertEquals(-1.0, CaseIntelligenceService.cosine(
                new float[]{1, 2, 3}, new float[]{-1, -2, -3}), 1e-9);
    }

    @Test
    void similarBeatsDissimilar() {
        float[] a = {1, 1, 0, 0};
        float[] close = {0.9f, 1.1f, 0.1f, 0};
        float[] far = {0, 0.1f, 1, 1};
        assertTrue(CaseIntelligenceService.cosine(a, close) > CaseIntelligenceService.cosine(a, far));
    }

    @Test
    void zeroVectorIsSafe() {
        assertEquals(0.0, CaseIntelligenceService.cosine(new float[]{0, 0}, new float[]{1, 2}), 1e-9);
    }
}
