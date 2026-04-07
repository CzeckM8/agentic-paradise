package io.github.nickm980.smallville.entities;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * An append-only record of something that actually happened in the world.
 *
 * The Chronicle is the ground truth. It is written by TurnEngine after
 * phase_commit and is NEVER read directly by NPC LLM prompts.
 *
 * NPC cognition reads only EpistemicMemory, which is populated from
 * PerceptionChannel — a per-agent filtered view derived from Chronicle
 * events where the agent appears in witnessIds.
 *
 * Invariant: LLM prompts for NPC X may not read Chronicle rows that were
 * not admitted through PerceptionChannel for X.
 */
public class ChronicleEvent {

    private String id;
    private int turnNumber;
    private String actorId;
    private String actorType;       // "player" | "agent"
    private String verb;
    private String targetId;
    private String targetType;      // "agent" | "player" | "object" | "tile"
    private String payload;         // spoken text, written text, item transferred, etc.
    private double actorX;
    private double actorY;
    private double targetX;
    private double targetY;

    /**
     * All actor/agent ids that were within perception radius at the time of
     * the event. Only these entities receive an ObservedEvent from
     * PerceptionChannel this turn.
     */
    private Set<String> witnessIds = new HashSet<>();

    private LocalDateTime wallTime;

    public ChronicleEvent() {
        this.id = UUID.randomUUID().toString();
        this.wallTime = LocalDateTime.now();
    }

    public ChronicleEvent(int turnNumber, String actorId, String actorType,
                          String verb, String targetId, String targetType,
                          String payload, double actorX, double actorY,
                          double targetX, double targetY, Set<String> witnessIds) {
        this.id = UUID.randomUUID().toString();
        this.turnNumber = turnNumber;
        this.actorId = actorId;
        this.actorType = actorType;
        this.verb = verb;
        this.targetId = targetId;
        this.targetType = targetType;
        this.payload = payload;
        this.actorX = actorX;
        this.actorY = actorY;
        this.targetX = targetX;
        this.targetY = targetY;
        this.witnessIds = new HashSet<>(witnessIds);
        this.wallTime = LocalDateTime.now();
    }

    /** True if the given entity was within perception range when this event occurred. */
    public boolean wasWitnessedBy(String entityId) {
        return witnessIds.contains(entityId);
    }

    public void addWitness(String entityId) {
        witnessIds.add(entityId);
    }

    /** Human-readable summary for debug/dashboard. Not used in LLM prompts. */
    public String toSummary() {
        return String.format("[T%d] %s %s %s '%s' (witnesses: %s)",
                turnNumber, actorId, verb, targetId,
                payload != null ? payload : "",
                String.join(", ", witnessIds));
    }

    // ── Getters / setters ────────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public int getTurnNumber() { return turnNumber; }
    public void setTurnNumber(int turnNumber) { this.turnNumber = turnNumber; }

    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }

    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }

    public String getVerb() { return verb; }
    public void setVerb(String verb) { this.verb = verb; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public double getActorX() { return actorX; }
    public void setActorX(double actorX) { this.actorX = actorX; }

    public double getActorY() { return actorY; }
    public void setActorY(double actorY) { this.actorY = actorY; }

    public double getTargetX() { return targetX; }
    public void setTargetX(double targetX) { this.targetX = targetX; }

    public double getTargetY() { return targetY; }
    public void setTargetY(double targetY) { this.targetY = targetY; }

    public Set<String> getWitnessIds() { return Collections.unmodifiableSet(witnessIds); }
    public void setWitnessIds(Set<String> witnessIds) { this.witnessIds = new HashSet<>(witnessIds); }

    public LocalDateTime getWallTime() { return wallTime; }
    public void setWallTime(LocalDateTime wallTime) { this.wallTime = wallTime; }
}
