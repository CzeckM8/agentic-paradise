package io.github.nickm980.smallville;

import io.github.nickm980.smallville.entities.AgentBeliefModel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AgentBeliefModel — Feature 2.2 (Theory of Mind).
 *
 * AgentBeliefModel holds what one agent believes about a specific other agent,
 * derived from direct observations and hearsay. These tests verify:
 *  - Initial state and construction
 *  - updateFromObservation() confidence weighting
 *  - addHearsay() note storage, cap enforcement, and confidence blending
 *  - setInferredGoal() round-trip
 *  - toNarrative() output covering all content branches
 *  - Long-string truncation in narrative
 *  - Null / blank safety for hearsay
 */
class AgentBeliefModelTest {

    private AgentBeliefModel model;

    @BeforeEach
    void setUp() {
        model = new AgentBeliefModel("John");
    }

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    void constructor_setsTargetNameAndLowInitialConfidence() {
        assertEquals("John", model.getTargetName());
        assertTrue(model.getConfidence() < 0.5,
            "Initial confidence should be low (< 0.5). Got: " + model.getConfidence());
        assertNull(model.getLastObservedVerb());
        assertNull(model.getLastObservedPayload());
        assertNull(model.getInferredGoal());
        assertTrue(model.getHearsayNotes().isEmpty());
        assertNotNull(model.getUpdatedAt());
    }

    // ── updateFromObservation ─────────────────────────────────────────────────

    @Test
    void updateFromObservation_storesVerbAndPayload() {
        model.updateFromObservation("carry", "KeyCard");

        assertEquals("carry", model.getLastObservedVerb());
        assertEquals("KeyCard", model.getLastObservedPayload());
    }

    @Test
    void updateFromObservation_increasesConfidence() {
        double before = model.getConfidence();
        model.updateFromObservation("speak", "Hello");
        assertTrue(model.getConfidence() > before,
            "Direct observation must increase confidence. Before=" + before
            + ", After=" + model.getConfidence());
    }

    @Test
    void updateFromObservation_nullPayloadAccepted() {
        assertDoesNotThrow(() -> model.updateFromObservation("wait", null));
        assertEquals("wait", model.getLastObservedVerb());
        assertNull(model.getLastObservedPayload());
    }

    @Test
    void updateFromObservation_overwritesPreviousVerb() {
        model.updateFromObservation("carry", "KeyCard");
        model.updateFromObservation("speak", "Hi there");

        assertEquals("speak", model.getLastObservedVerb());
        assertEquals("Hi there", model.getLastObservedPayload());
    }

    @Test
    void updateFromObservation_confidenceDoesNotExceedOne() {
        for (int i = 0; i < 20; i++) {
            model.updateFromObservation("carry", "item");
        }
        assertTrue(model.getConfidence() <= 1.0,
            "Confidence must not exceed 1.0 after many observations. Got: " + model.getConfidence());
    }

    // ── addHearsay ────────────────────────────────────────────────────────────

    @Test
    void addHearsay_storesNote() {
        model.addHearsay("Maria said: \"John lost the key\"", 0.7);

        List<String> notes = model.getHearsayNotes();
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("John lost the key"));
    }

    @Test
    void addHearsay_newestNoteFirst() {
        model.addHearsay("first note", 0.5);
        model.addHearsay("second note", 0.5);

        List<String> notes = model.getHearsayNotes();
        assertTrue(notes.get(0).contains("second note"),
            "Most recent hearsay must be first. Got: " + notes);
    }

    @Test
    void addHearsay_capsAtMaxNotes() {
        for (int i = 0; i < 10; i++) {
            model.addHearsay("note " + i, 0.5);
        }
        // MAX_HEARSAY_NOTES = 5
        assertTrue(model.getHearsayNotes().size() <= 5,
            "Hearsay notes must be capped. Got: " + model.getHearsayNotes().size());
    }

    @Test
    void addHearsay_blankNoteIsIgnored() {
        model.addHearsay("   ", 0.8);
        model.addHearsay("", 0.8);

        assertTrue(model.getHearsayNotes().isEmpty(),
            "Blank hearsay must not be stored");
    }

    @Test
    void addHearsay_nullNoteIsIgnored() {
        assertDoesNotThrow(() -> model.addHearsay(null, 0.8));
        assertTrue(model.getHearsayNotes().isEmpty(),
            "Null hearsay must not be stored");
    }

    @Test
    void addHearsay_blendConfidenceUpward() {
        double before = model.getConfidence();
        model.addHearsay("some note", 1.0); // high-confidence hearsay
        assertTrue(model.getConfidence() >= before,
            "High-confidence hearsay must not reduce confidence. Before=" + before
            + ", After=" + model.getConfidence());
    }

    @Test
    void getHearsayNotes_returnsDefensiveCopy() {
        model.addHearsay("test note", 0.5);
        List<String> copy = model.getHearsayNotes();
        assertDoesNotThrow(() -> copy.add("injected"),
            "Modifying the returned list must not throw");
        // Verify the internal list was not affected
        assertEquals(1, model.getHearsayNotes().size(),
            "Internal hearsay list must not be modified through the returned view");
    }

    // ── setInferredGoal ───────────────────────────────────────────────────────

    @Test
    void setInferredGoal_roundTrip() {
        model.setInferredGoal("unlock the storage room");
        assertEquals("unlock the storage room", model.getInferredGoal());
    }

    @Test
    void setInferredGoal_canBeOverwritten() {
        model.setInferredGoal("first goal");
        model.setInferredGoal("second goal");
        assertEquals("second goal", model.getInferredGoal());
    }

    // ── toNarrative ───────────────────────────────────────────────────────────

    @Test
    void toNarrative_includesTargetName() {
        String narrative = model.toNarrative();
        assertTrue(narrative.startsWith("John:"),
            "Narrative must start with target name. Got: " + narrative);
    }

    @Test
    void toNarrative_noObservationsYetMessage() {
        // Fresh model — no observations, no hearsay
        String narrative = model.toNarrative();
        assertTrue(narrative.contains("no observations yet"),
            "Narrative for empty model must indicate no observations. Got: " + narrative);
    }

    @Test
    void toNarrative_includesLastObservedVerb() {
        model.updateFromObservation("carry", "KeyCard");
        String narrative = model.toNarrative();
        assertTrue(narrative.contains("carry"),
            "Narrative must include the observed verb. Got: " + narrative);
        assertTrue(narrative.contains("KeyCard"),
            "Narrative must include the observed payload. Got: " + narrative);
    }

    @Test
    void toNarrative_includesInferredGoalWhenSet() {
        model.updateFromObservation("carry", "KeyCard");
        model.setInferredGoal("unlock the storage room");
        String narrative = model.toNarrative();
        assertTrue(narrative.contains("unlock the storage room"),
            "Narrative must include inferred goal. Got: " + narrative);
    }

    @Test
    void toNarrative_uncertainTagWhenConfidenceLow() {
        // No observations → low confidence
        String narrative = model.toNarrative();
        assertTrue(narrative.contains("[uncertain]"),
            "Low-confidence model must be marked [uncertain]. Got: " + narrative);
    }

    @Test
    void toNarrative_noUncertainTagAfterDirectObservation() {
        // Many observations raise confidence above 0.4 threshold
        for (int i = 0; i < 3; i++) {
            model.updateFromObservation("carry", "item");
        }
        String narrative = model.toNarrative();
        assertFalse(narrative.contains("[uncertain]"),
            "High-confidence model must not be marked [uncertain]. Got: " + narrative);
    }

    @Test
    void toNarrative_fallsBackToHearsayWhenNoDirectObservation() {
        model.addHearsay("overheard: John is angry", 0.6);
        String narrative = model.toNarrative();
        assertTrue(narrative.contains("overheard") || narrative.contains("John is angry"),
            "Narrative must use hearsay when no direct observation. Got: " + narrative);
    }

    @Test
    void toNarrative_longPayloadIsTruncated() {
        String longPayload = "x".repeat(200);
        model.updateFromObservation("speak", longPayload);
        String narrative = model.toNarrative();
        // Payload should be truncated — narrative must not contain the full 200-char string
        assertFalse(narrative.contains(longPayload),
            "Long payload must be truncated in narrative. Length=" + narrative.length());
        assertTrue(narrative.contains("…"),
            "Truncated narrative must contain ellipsis. Got: " + narrative);
    }
}
