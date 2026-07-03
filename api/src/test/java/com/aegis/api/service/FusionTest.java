package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** RRF must reward agreement: found-by-both beats found-by-one. */
class FusionTest {

    @Test
    void itemInBothRankingsWins() {
        Map<String, Double> fused = Fusion.rrf(60, List.of(
                List.of("a", "b", "c"),     // semantic ranking
                List.of("b", "d", "a")));   // keyword ranking
        assertTrue(fused.get("b") > fused.get("a"), "b is ranked 2nd+1st, a is 1st+3rd");
        assertTrue(fused.get("a") > fused.get("c"));
        assertTrue(fused.get("a") > fused.get("d"));
    }

    @Test
    void singleRankingPreservesOrder() {
        Map<String, Double> fused = Fusion.rrf(60, List.of(List.of("x", "y", "z")));
        assertTrue(fused.get("x") > fused.get("y") && fused.get("y") > fused.get("z"));
    }

    @Test
    void emptyRankingsFuseToEmpty() {
        assertEquals(0, Fusion.rrf(60, List.of(List.of(), List.of())).size());
    }
}
