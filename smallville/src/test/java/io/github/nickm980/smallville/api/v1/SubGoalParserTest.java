package io.github.nickm980.smallville.api.v1;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SubGoalParser — verifies parsing of LLM sub-goal plan text.
 * These tests run without any LLM or Spring context.
 */
class SubGoalParserTest {

    /** Legal action list in the format produced by ActionResolver.legalActionsFor(). */
    private final List<String> legal = List.of(
        "carry(KeyCard) [0.8 tiles, carriable]",
        "speak(John) [in range]",
        "unlock(StorageRoom) [1.2 tiles, you hold the matching key]",
        "write(Whiteboard) [0.5 tiles, you hold a writing utensil]",
        "open(MainDoor) [1.0 tiles, closed]",
        "wait [always available]"
    );

    // ── Happy-path parsing ────────────────────────────────────────────────────

    @Test
    void parsesThreeStepsCorrectly() {
        String raw =
            "1. carry(KeyCard) \u2014 pick up the keycard\n" +
            "2. unlock(StorageRoom) \u2014 use the key on the door\n" +
            "3. speak(John) \u2014 tell John the room is open";

        List<ParsedStep> steps = SubGoalParser.parse(raw, legal);

        assertEquals(3, steps.size());
        assertEquals("carry", steps.get(0).verb);
        assertEquals("KeyCard", steps.get(0).targetName);
        assertEquals("pick up the keycard", steps.get(0).reason);

        assertEquals("unlock", steps.get(1).verb);
        assertEquals("StorageRoom", steps.get(1).targetName);

        assertEquals("speak", steps.get(2).verb);
        assertEquals("John", steps.get(2).targetName);
    }

    @Test
    void parsesRegularDashSeparatorAsAlternative() {
        String raw = "1. carry(KeyCard) - grab the key";
        List<ParsedStep> steps = SubGoalParser.parse(raw, legal);
        assertEquals(1, steps.size());
        assertEquals("grab the key", steps.get(0).reason);
    }

    @Test
    void parsesSingleStep() {
        String raw = "1. speak(John) \u2014 say hello";
        List<ParsedStep> steps = SubGoalParser.parse(raw, legal);
        assertEquals(1, steps.size());
        assertEquals("speak", steps.get(0).verb);
        assertEquals("John", steps.get(0).targetName);
    }

    @Test
    void parsesStepWithoutTarget() {
        String raw = "1. wait \u2014 take a moment to observe";
        List<ParsedStep> steps = SubGoalParser.parse(raw, legal);
        assertEquals(1, steps.size());
        assertEquals("wait", steps.get(0).verb);
        assertNull(steps.get(0).targetName);
        assertEquals("take a moment to observe", steps.get(0).reason);
    }

    @Test
    void reasonDefaultsToVerbAndTargetWhenMissing() {
        String raw = "1. carry(KeyCard)";
        List<ParsedStep> steps = SubGoalParser.parse(raw, legal);
        assertEquals(1, steps.size());
        // When no reason is given, reason = "verb targetName"
        assertEquals("carry KeyCard", steps.get(0).reason);
    }

    // ── Filtering illegal actions ─────────────────────────────────────────────

    @Test
    void filtersStepsWithIllegalVerb() {
        String raw =
            "1. fly(Roof) \u2014 this verb does not exist\n" +
            "2. speak(John) \u2014 this is legal";

        List<ParsedStep> steps = SubGoalParser.parse(raw, legal);
        assertEquals(1, steps.size());
        assertEquals("speak", steps.get(0).verb);
    }

    @Test
    void filtersStepsWithLegalVerbButIllegalTarget() {
        String raw =
            "1. carry(Dragon) \u2014 Dragon is not in the legal list\n" +
            "2. carry(KeyCard) \u2014 KeyCard is legal";

        List<ParsedStep> steps = SubGoalParser.parse(raw, legal);
        assertEquals(1, steps.size());
        assertEquals("KeyCard", steps.get(0).targetName);
    }

    @Test
    void returnsEmptyWhenAllStepsAreIllegal() {
        String raw =
            "1. teleport(Moon) \u2014 illegal\n" +
            "2. explode(World) \u2014 also illegal";

        assertTrue(SubGoalParser.parse(raw, legal).isEmpty());
    }

    // ── Capacity cap ─────────────────────────────────────────────────────────

    @Test
    void capsAtThreeSteps() {
        String raw =
            "1. carry(KeyCard) \u2014 step 1\n" +
            "2. speak(John) \u2014 step 2\n" +
            "3. wait \u2014 step 3\n" +
            "4. write(Whiteboard) \u2014 step 4 — should be ignored";

        List<ParsedStep> steps = SubGoalParser.parse(raw, legal);
        assertEquals(3, steps.size(), "Must cap at MAX_STEPS=" + SubGoalParser.MAX_STEPS);
    }

    // ── Null / blank / garbage input ─────────────────────────────────────────

    @Test
    void returnsEmptyForNullInput() {
        assertTrue(SubGoalParser.parse(null, legal).isEmpty());
    }

    @Test
    void returnsEmptyForBlankInput() {
        assertTrue(SubGoalParser.parse("   \n  ", legal).isEmpty());
        assertTrue(SubGoalParser.parse("", legal).isEmpty());
    }

    @Test
    void ignoresGarbagePreambleAndTrailingText() {
        String raw =
            "Here is my plan:\n" +
            "1. carry(KeyCard) \u2014 grab it\n" +
            "2. unlock(StorageRoom) \u2014 open the door\n" +
            "Done!";

        List<ParsedStep> steps = SubGoalParser.parse(raw, legal);
        assertEquals(2, steps.size());
    }

    @Test
    void returnsEmptyWhenLegalActionsListIsNull() {
        String raw = "1. carry(KeyCard) \u2014 pick up";
        assertTrue(SubGoalParser.parse(raw, null).isEmpty());
    }

    @Test
    void returnsEmptyWhenLegalActionsListIsEmpty() {
        String raw = "1. carry(KeyCard) \u2014 pick up";
        assertTrue(SubGoalParser.parse(raw, List.of()).isEmpty());
    }

    // ── isLegal helper ───────────────────────────────────────────────────────

    @Test
    void isLegalCaseInsensitive() {
        assertTrue(SubGoalParser.isLegal("CARRY", "KEYCARD", legal));
        assertTrue(SubGoalParser.isLegal("speak", "john", legal));
        assertTrue(SubGoalParser.isLegal("WAIT", null, legal));
    }

    @Test
    void isLegalReturnsFalseForUnknownVerb() {
        assertFalse(SubGoalParser.isLegal("attack", "John", legal));
        assertFalse(SubGoalParser.isLegal("jump", null, legal));
    }

    @Test
    void isLegalMatchesVerbWithAnyTargetWhenTargetIsNull() {
        // verb "carry" appears in legal list (as "carry(KeyCard)") — bare verb match
        assertTrue(SubGoalParser.isLegal("carry", null, legal));
    }

    @Test
    void isLegalStripsContextBracketBeforeMatching() {
        // "wait [always available]" should match bare "wait"
        assertTrue(SubGoalParser.isLegal("wait", null, legal));
        // "carry(KeyCard) [0.8 tiles, carriable]" — context stripped
        assertTrue(SubGoalParser.isLegal("carry", "KeyCard", legal));
    }

    // ── Edge-case formatting ──────────────────────────────────────────────────

    @Test
    void handlesEmptyParentheses() {
        // "open()" — empty target should be treated as null
        List<String> minimalLegal = List.of("open [always available]");
        String raw = "1. open() \u2014 open something";
        List<ParsedStep> steps = SubGoalParser.parse(raw, minimalLegal);
        // Empty target normalised to null; "open" matches "open" in legal list
        assertEquals(1, steps.size());
        assertNull(steps.get(0).targetName);
    }

    @Test
    void handlesMixedNumberingGaps() {
        // Non-sequential numbering should still be parsed
        String raw =
            "1. carry(KeyCard) \u2014 step one\n" +
            "3. speak(John) \u2014 step three (skipped 2)";

        List<ParsedStep> steps = SubGoalParser.parse(raw, legal);
        assertEquals(2, steps.size());
        assertEquals("carry", steps.get(0).verb);
        assertEquals("speak", steps.get(1).verb);
    }
}
