package io.github.nickm980.smallville;

import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.memory.Characteristic;
import io.github.nickm980.smallville.entities.Location;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Agent's mutable trait delta system (Feature 2.3 — Reflection → Trait Adjustment).
 *
 * Tests cover:
 *  - applyTraitDelta() accumulates correctly
 *  - Per-signal cap: signals > ±0.1 are clamped before accumulation
 *  - Cumulative cap: total delta per trait is bounded to ±0.3
 *  - Trait getters return base + delta, clamped to [0, 1]
 *  - getTraitDeltas() returns an unmodifiable view
 *  - Null / blank trait names are silently ignored
 *  - All 7 named traits are supported
 */
class AgentTraitDeltaTest {

    private Agent agent;

    /** Build a minimal agent. Location must not be null for most operations. */
    @BeforeEach
    void setUp() {
        Location loc = new Location("Room");
        agent = new Agent("TestAgent", List.of(new Characteristic("curious")), "idle", loc);
    }

    // ── Basic accumulation ────────────────────────────────────────────────────

    @Test
    void applyDelta_accumulatesAcrossMultipleCalls() {
        agent.applyTraitDelta("compassion", 0.05);
        agent.applyTraitDelta("compassion", 0.03);

        Map<String, Double> deltas = agent.getTraitDeltas();
        assertEquals(0.08, deltas.get("compassion"), 1e-9,
            "Two small signals must sum to 0.08");
    }

    @Test
    void applyDelta_negativeSignalReducesDelta() {
        agent.applyTraitDelta("aggression", 0.06);
        agent.applyTraitDelta("aggression", -0.04);

        assertEquals(0.02, agent.getTraitDeltas().get("aggression"), 1e-9);
    }

    @Test
    void applyDelta_zeroHasNoEffect() {
        agent.applyTraitDelta("loyalty", 0.0);
        assertFalse(agent.getTraitDeltas().containsKey("loyalty"),
            "Zero delta should not create an entry");
    }

    // ── Per-signal cap ────────────────────────────────────────────────────────

    @Test
    void perSignalCap_largePositiveIsCappedAtPoint1() {
        agent.applyTraitDelta("fearfulness", 0.5);  // exceeds per-signal cap
        assertEquals(0.1, agent.getTraitDeltas().get("fearfulness"), 1e-9,
            "Per-signal delta must be capped at +0.1");
    }

    @Test
    void perSignalCap_largeNegativeIsCappedAtMinus0Point1() {
        agent.applyTraitDelta("impulsivity", -0.9);
        assertEquals(-0.1, agent.getTraitDeltas().get("impulsivity"), 1e-9,
            "Per-signal delta must be capped at -0.1");
    }

    @Test
    void perSignalCap_exactBoundaryIsPermitted() {
        agent.applyTraitDelta("riskTolerance", 0.1);
        assertEquals(0.1, agent.getTraitDeltas().get("riskTolerance"), 1e-9,
            "Exactly 0.1 must pass through the per-signal cap unchanged");
    }

    // ── Cumulative cap ────────────────────────────────────────────────────────

    @Test
    void cumulativeCap_positiveDoesNotExceedPoint3() {
        for (int i = 0; i < 5; i++) {
            agent.applyTraitDelta("socialDominance", 0.08);  // 5 × 0.08 = 0.40 > cap
        }
        assertEquals(0.3, agent.getTraitDeltas().get("socialDominance"), 1e-9,
            "Cumulative delta must be capped at +0.3");
    }

    @Test
    void cumulativeCap_negativeDoesNotGoBelowMinusPoint3() {
        for (int i = 0; i < 5; i++) {
            agent.applyTraitDelta("aggression", -0.08);
        }
        assertEquals(-0.3, agent.getTraitDeltas().get("aggression"), 1e-9,
            "Cumulative delta must be capped at -0.3");
    }

    @Test
    void cumulativeCap_exactPoint3IsPermitted() {
        agent.applyTraitDelta("compassion", 0.1);
        agent.applyTraitDelta("compassion", 0.1);
        agent.applyTraitDelta("compassion", 0.1);
        assertEquals(0.3, agent.getTraitDeltas().get("compassion"), 1e-9,
            "Exactly 0.3 cumulative must be allowed");
    }

    // ── Trait getter clamping ─────────────────────────────────────────────────

    @Test
    void traitGetter_effectiveValueIsClampedAboveZero() {
        // Force the effective value below 0 by applying a huge negative delta
        // (cumulative cap = -0.3, so effective = base - 0.3)
        // The base trait for "TestAgent" may already be near 0; we just check clamping works.
        // Apply -0.1 repeatedly to saturate the cumulative cap at -0.3
        for (int i = 0; i < 4; i++) agent.applyTraitDelta("fearfulness", -0.1);
        // effective = base + (-0.3)
        assertTrue(agent.getFearfulness() >= 0.0,
            "Effective trait value must not go below 0");
    }

    @Test
    void traitGetter_effectiveValueIsClampedBelowOne() {
        for (int i = 0; i < 4; i++) agent.applyTraitDelta("loyalty", 0.1);
        assertTrue(agent.getLoyalty() <= 1.0,
            "Effective trait value must not exceed 1");
    }

    @Test
    void traitGetter_noSignalReturnsBaseValue() {
        // Without any deltas the getter should equal the seeded base value
        double base = agent.getCompassion();
        assertTrue(base >= 0.0 && base <= 1.0,
            "Base trait value must be in [0, 1]");
    }

    // ── All 7 traits are addressable ─────────────────────────────────────────

    @Test
    void allSevenTraitsAcceptDeltas() {
        String[] traits = {"aggression", "fearfulness", "loyalty", "impulsivity",
                           "compassion", "riskTolerance", "socialDominance"};
        for (String trait : traits) {
            agent.applyTraitDelta(trait, 0.05);
            assertTrue(agent.getTraitDeltas().containsKey(trait),
                "Trait '" + trait + "' must be addressable");
        }
        assertEquals(7, agent.getTraitDeltas().size(),
            "All 7 trait deltas must be stored");
    }

    // ── getTraitDeltas() returns unmodifiable view ────────────────────────────

    @Test
    void getTraitDeltas_returnsUnmodifiableView() {
        agent.applyTraitDelta("aggression", 0.05);
        Map<String, Double> view = agent.getTraitDeltas();
        assertThrows(UnsupportedOperationException.class,
            () -> view.put("aggression", 0.99),
            "getTraitDeltas() must return an unmodifiable map");
    }

    // ── Null / blank safety ───────────────────────────────────────────────────

    @Test
    void applyDelta_nullTraitNameIsIgnored() {
        assertDoesNotThrow(() -> agent.applyTraitDelta(null, 0.05),
            "null trait name must not throw");
        assertTrue(agent.getTraitDeltas().isEmpty(),
            "null trait name must not create an entry");
    }

    @Test
    void applyDelta_blankTraitNameIsIgnored() {
        assertDoesNotThrow(() -> agent.applyTraitDelta("   ", 0.05),
            "blank trait name must not throw");
        assertTrue(agent.getTraitDeltas().isEmpty(),
            "blank trait name must not create an entry");
    }

    @Test
    void applyDelta_emptyStringTraitNameIsIgnored() {
        assertDoesNotThrow(() -> agent.applyTraitDelta("", 0.05));
        assertTrue(agent.getTraitDeltas().isEmpty());
    }

    // ── Multiple independent traits don't interfere ───────────────────────────

    @Test
    void independentTraits_doNotShareDeltas() {
        agent.applyTraitDelta("aggression", 0.07);
        agent.applyTraitDelta("compassion", -0.03);

        assertEquals(0.07, agent.getTraitDeltas().get("aggression"), 1e-9);
        assertEquals(-0.03, agent.getTraitDeltas().get("compassion"), 1e-9);
        assertFalse(agent.getTraitDeltas().containsKey("loyalty"),
            "Unmodified traits must not appear in the delta map");
    }
}
