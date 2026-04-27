package io.github.nickm980.smallville.api.v1;

import io.github.nickm980.smallville.entities.Agent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Immutable data carrier passed to AgentTurnRunner for one agent's turn.
 * Built by SimulationService before submitting the async cognition job.
 */
public class AgentTurnContext {

    public final Agent agent;

    /** Pre-computed legal actions for this agent this turn. */
    public final List<String> legalActions;

    /** Narrative summary of what this agent believes about other agents. */
    public final String beliefSummary;

    /** Snapshot of nearby entities: list of {id, type, distance, activity} maps. */
    public final List<Map<String, Object>> nearbyEntities;

    /** Simulation wall-clock turn number. */
    public final int turnNumber;

    /** Current simulation time. */
    public final LocalDateTime simulationTime;

    /** Maximum tool-call iterations before forced fallback. */
    public final int maxIterations;

    /** Wall-clock deadline in milliseconds from context creation. 0 = no deadline. */
    public final long deadlineMs;

    /**
     * Non-null when there is a priority combat directive for this turn (flee or retaliate).
     * Injected at the top of the situation briefing so the LLM tool loop acts on it.
     */
    public final String pendingIntent;

    public AgentTurnContext(Agent agent,
                            List<String> legalActions,
                            String beliefSummary,
                            List<Map<String, Object>> nearbyEntities,
                            int turnNumber,
                            LocalDateTime simulationTime,
                            int maxIterations,
                            long deadlineMs,
                            String pendingIntent) {
        this.agent = agent;
        this.legalActions = legalActions;
        this.beliefSummary = beliefSummary;
        this.nearbyEntities = nearbyEntities;
        this.turnNumber = turnNumber;
        this.simulationTime = simulationTime;
        this.maxIterations = maxIterations;
        this.deadlineMs = deadlineMs;
        this.pendingIntent = pendingIntent;
    }
}
