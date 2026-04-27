package io.github.nickm980.smallville.api.v1;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses LLM-generated sub-goal plan text into an ordered list of {@link ParsedStep}s.
 *
 * Expected input format (one numbered step per line):
 * <pre>
 *   1. carry(KeyCard) — pick up the keycard first
 *   2. unlock(StorageRoom) — use the key on the door
 *   3. speak(John) — tell John it's open
 * </pre>
 *
 * Steps referencing actions not found in the provided legal action list are silently
 * dropped. The caller is responsible for supplying the current legal action list so
 * the parser can filter hallucinated verbs.
 *
 * At most {@link #MAX_STEPS} steps are returned regardless of how many the LLM writes.
 */
class SubGoalParser {

    static final int MAX_STEPS = 3;

    /**
     * Parse LLM response text into an ordered list of verified steps.
     *
     * @param raw          raw LLM response (may include preamble or garbage lines)
     * @param legalActions current legal action list in "verb(Target) [context]" format
     * @return ordered list of parsed steps; empty list if nothing parseable/legal was found
     */
    static List<ParsedStep> parse(String raw, List<String> legalActions) {
        List<ParsedStep> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;

        for (String line : raw.split("\n")) {
            line = line.trim();
            // Only process numbered lines: "1. ...", "2. ...", etc.
            if (!line.matches("^[1-9]\\..*")) continue;
            line = line.replaceFirst("^[1-9]\\. *", "").trim();

            // Split on " — " (em-dash) or " - " (regular dash) separator
            String actionPart;
            String reason = "";
            int sepIdx = line.indexOf(" \u2014 "); // em-dash
            if (sepIdx == -1) sepIdx = line.indexOf(" - ");
            if (sepIdx > 0) {
                actionPart = line.substring(0, sepIdx).trim();
                reason = line.substring(sepIdx + 3).trim();
            } else {
                actionPart = line.trim();
            }

            // Parse "verb(target)" or bare "verb"
            String verb;
            String targetName = null;
            int parenOpen = actionPart.indexOf('(');
            int parenClose = actionPart.lastIndexOf(')');
            if (parenOpen > 0 && parenClose > parenOpen) {
                verb = actionPart.substring(0, parenOpen).trim();
                targetName = actionPart.substring(parenOpen + 1, parenClose).trim();
                if (targetName.isBlank()) targetName = null;
            } else {
                verb = actionPart.trim();
            }

            if (verb.isBlank()) continue;

            // Verify the action exists in the legal action list before accepting it
            if (!isLegal(verb, targetName, legalActions)) continue;

            String desc = reason.isBlank()
                ? (verb + (targetName != null ? " " + targetName : ""))
                : reason;

            result.add(new ParsedStep(verb, targetName, desc));
            if (result.size() >= MAX_STEPS) break;
        }

        return result;
    }

    /**
     * Returns true if the given verb (and optional target) matches any entry in
     * the legal actions list.  Matching is case-insensitive.  The "[context]"
     * suffix appended by {@link ActionResolver#legalActionsFor} is stripped before
     * comparison.
     *
     * @param verb        the action verb (e.g. "carry")
     * @param targetName  the target name inside parentheses, or null for bare verbs
     * @param legalActions list in "verb(Target) [context]" or "wait [always available]" format
     */
    static boolean isLegal(String verb, String targetName, List<String> legalActions) {
        if (legalActions == null || legalActions.isEmpty()) return false;
        String verbL = verb.toLowerCase();
        String targetL = targetName != null ? targetName.toLowerCase() : null;

        for (String action : legalActions) {
            // Strip " [context]" suffix if present
            String a = action.toLowerCase();
            int bracketIdx = a.indexOf('[');
            if (bracketIdx > 0) a = a.substring(0, bracketIdx).trim();

            if (targetL != null) {
                // "carry(keycard)" — match prefix to tolerate partial target names
                if (a.startsWith(verbL + "(" + targetL)) return true;
            } else {
                // bare verb: exact match or "verb(" (i.e. the verb is available for some target)
                if (a.equals(verbL) || a.startsWith(verbL + "(")) return true;
            }
        }
        return false;
    }
}
