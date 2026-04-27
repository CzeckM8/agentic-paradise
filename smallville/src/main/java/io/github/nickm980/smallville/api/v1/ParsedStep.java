package io.github.nickm980.smallville.api.v1;

/**
 * One parsed step from a sub-goal plan response.
 * Converted to AgenticGoal by SimulationService after target resolution.
 */
class ParsedStep {
    final String verb;
    final String targetName;  // null if the step has no explicit target
    final String reason;      // human-readable description used as actionDescription

    ParsedStep(String verb, String targetName, String reason) {
        this.verb = verb;
        this.targetName = targetName;
        this.reason = reason;
    }
}
