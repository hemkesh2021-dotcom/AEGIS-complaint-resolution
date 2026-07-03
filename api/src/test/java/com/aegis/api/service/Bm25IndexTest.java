package com.aegis.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aegis.api.service.Bm25Index.Passage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The keyword half of hybrid retrieval must rank exact regulatory vocabulary. */
class Bm25IndexTest {

    private Bm25Index indexed() {
        Bm25Index idx = new Bm25Index();
        idx.index(List.of(
                new Passage("reg-z", "Credit card",
                        "Regulation Z and the Fair Credit Billing Act govern billing error disputes on credit cards. "
                        + "The disputed amount need not be paid during investigation."),
                new Passage("respa", "Mortgage",
                        "RESPA Regulation X governs mortgage servicing, escrow analysis statements and notices of error."),
                new Passage("fdcpa", "Debt collection",
                        "The FDCPA restricts debt collector conduct: harassment, repeated calls, and threats are prohibited; "
                        + "debt validation must be provided on request.")));
        return idx;
    }

    @Test
    void exactVocabularyRanksTheRightPassageFirst() {
        var hits = indexed().search("escrow analysis statement missing on my mortgage", 3);
        assertFalse(hits.isEmpty());
        assertEquals("respa", hits.get(0).passage().id());
    }

    @Test
    void billingDisputeFindsRegZ() {
        var hits = indexed().search("billing error dispute on my credit card charge", 2);
        assertEquals("reg-z", hits.get(0).passage().id());
    }

    @Test
    void irrelevantQueryReturnsEmpty() {
        assertTrue(indexed().search("zebra quantum sandwich", 3).isEmpty());
        assertTrue(indexed().search("", 3).isEmpty());
        assertTrue(indexed().search(null, 3).isEmpty());
    }

    @Test
    void loadsTheRealKnowledgeBaseFromClasspath() throws Exception {
        Bm25Index idx = new Bm25Index();
        idx.load();
        var hits = idx.search("escrow analysis on my mortgage servicing", 3);
        assertFalse(hits.isEmpty(), "real kb.json should answer a mortgage query");
        assertEquals("Mortgage", hits.get(0).passage().category());
    }
}
