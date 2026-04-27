package io.github.nickm980.smallville.api.v1;

import io.github.nickm980.smallville.llm.tools.ToolCall;

import java.util.List;

/**
 * The result of one agent's tool-loop turn.
 * Produced by AgentTurnRunner on the async cognition thread.
 * Applied to world state by SimulationService.applyTurnResult() on the main thread.
 */
public class TurnResult {

    /** True if commit_action was called and ActionResolver permitted the action. */
    public final boolean committed;

    /** The verb that was committed (null if uncommitted). */
    public final String verb;

    /** The target id of the committed action (null if uncommitted). */
    public final String targetId;

    /** The target type: "agent" | "player" | "object" (null if uncommitted). */
    public final String targetType;

    /** Optional payload (speech text, item name, etc.) — may be null. */
    public final String payload;

    /** Rejection explanation if committed=false and ActionResolver ran. Null on iteration timeout. */
    public final String rejectExplanation;

    /** All tool calls made during the loop (for observability/logging). */
    public final List<ToolCall> toolCallsMade;

    /** True when the loop exhausted maxIterations or deadline without commit_action. */
    public final boolean wasIterationFallback;

    private TurnResult(boolean committed, String verb, String targetId, String targetType,
                       String payload, String rejectExplanation,
                       List<ToolCall> toolCallsMade, boolean wasIterationFallback) {
        this.committed = committed;
        this.verb = verb;
        this.targetId = targetId;
        this.targetType = targetType;
        this.payload = payload;
        this.rejectExplanation = rejectExplanation;
        this.toolCallsMade = toolCallsMade;
        this.wasIterationFallback = wasIterationFallback;
    }

    public static TurnResult committed(String verb, String targetId, String targetType,
                                       String payload, List<ToolCall> calls) {
        return new TurnResult(true, verb, targetId, targetType, payload, null, calls, false);
    }

    public static TurnResult rejected(String verb, String targetId, String rejectExplanation,
                                      List<ToolCall> calls) {
        return new TurnResult(false, verb, targetId, null, null, rejectExplanation, calls, false);
    }

    public static TurnResult iterationFallback(List<ToolCall> calls) {
        return new TurnResult(false, null, null, null, null, null, calls, true);
    }
}
