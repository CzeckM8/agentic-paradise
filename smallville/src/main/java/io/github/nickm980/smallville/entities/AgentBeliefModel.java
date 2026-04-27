package io.github.nickm980.smallville.entities;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.ArrayList;

/**
 * What one agent believes about a specific other agent.
 *
 * This is an approximation — it may be wrong, stale, or based on hearsay.
 * The gap between this model and ground truth is what makes deception,
 * surprise, and social dynamics possible.
 *
 * Populated exclusively from:
 *  - ObservedEvents in EpistemicMemory (direct witness — high confidence)
 *  - Hearsay entries in EpistemicMemory (secondhand — lower confidence)
 *
 * The confidence score is a running weighted average that decays toward 0.5
 * (uncertain) as new conflicting information arrives.
 */
public class AgentBeliefModel {

    private static final int MAX_HEARSAY_NOTES = 5;

    /** Agent name this belief is about. */
    private final String targetName;

    /**
     * Last verb this agent was observed performing (e.g. "carry", "speak").
     * Null if never observed directly.
     */
    private String lastObservedVerb;

    /**
     * Payload of the last observed event (e.g. speech text, item name).
     * Null if the event had no payload.
     */
    private String lastObservedPayload;

    /**
     * What this agent infers the target is trying to accomplish.
     * Derived from hearsay + observed action sequences.
     * May be null if insufficient evidence.
     */
    private String inferredGoal;

    /**
     * Confidence in the current belief model (0.0 = no idea, 1.0 = certain).
     * Direct observations contribute weight 0.9; hearsay 0.5.
     */
    private double confidence;

    /**
     * Recent hearsay notes about this agent, newest-first.
     * Capped at MAX_HEARSAY_NOTES.
     */
    private final Deque<String> hearsayNotes = new ArrayDeque<>();

    /** Timestamp of the most recent update to this model. */
    private LocalDateTime updatedAt;

    public AgentBeliefModel(String targetName) {
        this.targetName = targetName;
        this.confidence = 0.3; // start with low confidence until first observation
        this.updatedAt = LocalDateTime.now();
    }

    // ── Update methods ────────────────────────────────────────────────────────

    /**
     * Update from a directly observed event.
     * Direct observations carry high confidence (0.9 weight).
     *
     * @param verb    what the target was doing
     * @param payload context (speech text, item name, etc.) — may be null
     */
    public void updateFromObservation(String verb, String payload) {
        this.lastObservedVerb = verb;
        this.lastObservedPayload = payload;
        // Weight new direct observation heavily
        this.confidence = Math.min(1.0, confidence * 0.4 + 0.9 * 0.6);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Add a hearsay note about this agent.
     * Hearsay carries lower confidence than direct observation.
     *
     * @param note    what was heard (e.g. "Maria said: 'I lost my key'")
     * @param noteConfidence  confidence the source placed on this information (0–1)
     */
    public void addHearsay(String note, double noteConfidence) {
        if (note == null || note.isBlank()) return;
        hearsayNotes.addFirst(note); // newest first
        if (hearsayNotes.size() > MAX_HEARSAY_NOTES) hearsayNotes.pollLast();
        // Blend confidence: hearsay moves it moderately
        this.confidence = Math.min(1.0, confidence * 0.7 + noteConfidence * 0.3);
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Set the inferred goal for this agent.
     * Typically derived from a sequence of observed actions (e.g. carry + move_to = trying to deliver).
     */
    public void setInferredGoal(String inferredGoal) {
        this.inferredGoal = inferredGoal;
        this.updatedAt = LocalDateTime.now();
    }

    // ── Narrative generation for LLM prompt ──────────────────────────────────

    /**
     * Returns a single human-readable sentence suitable for LLM prompt injection.
     *
     * Examples:
     *   "John: last seen carrying KeyCard; may be trying to unlock the storage room"
     *   "Maria: overheard saying 'I lost my key' [low confidence]"
     *   "Bob: (no observations yet)"
     */
    public String toNarrative() {
        StringBuilder sb = new StringBuilder(targetName).append(": ");

        if (lastObservedVerb != null) {
            sb.append("last seen ").append(lastObservedVerb);
            if (lastObservedPayload != null && !lastObservedPayload.isBlank()) {
                sb.append(" (").append(truncate(lastObservedPayload, 60)).append(")");
            }
        } else if (!hearsayNotes.isEmpty()) {
            sb.append("overheard — ").append(truncate(hearsayNotes.peekFirst(), 80));
        } else {
            sb.append("(no observations yet)");
        }

        if (inferredGoal != null && !inferredGoal.isBlank()) {
            sb.append("; may be trying to ").append(truncate(inferredGoal, 60));
        }

        if (!hearsayNotes.isEmpty() && lastObservedVerb != null) {
            // Both: append one hearsay note
            sb.append("; also heard: ").append(truncate(hearsayNotes.peekFirst(), 60));
        }

        if (confidence < 0.4) {
            sb.append(" [uncertain]");
        }

        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getTargetName()          { return targetName; }
    public String getLastObservedVerb()    { return lastObservedVerb; }
    public String getLastObservedPayload() { return lastObservedPayload; }
    public String getInferredGoal()        { return inferredGoal; }
    public double getConfidence()          { return confidence; }
    public LocalDateTime getUpdatedAt()    { return updatedAt; }

    public List<String> getHearsayNotes() {
        return new ArrayList<>(hearsayNotes);
    }
}
