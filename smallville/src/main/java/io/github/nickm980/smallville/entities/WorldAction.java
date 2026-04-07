package io.github.nickm980.smallville.entities;

/**
 * The single primitive for all interactions in the world.
 *
 * Both player and NPC actions are represented as a WorldAction before
 * being passed to ActionResolver. The resolver is blind to actorType —
 * the same affordance, inventory, range, and conflict rules apply to all actors.
 *
 * Conversion paths:
 *   PlayerActionRequest  → WorldAction.fromPlayerAction(...)
 *   AgentAction          → WorldAction.fromAgentAction(...)
 *
 * Verbs (extensible):
 *   move, take, give, speak, write, push, sit, use,
 *   open, close, attack, inspect, place, carry, observe
 */
public class WorldAction {

    public enum ActorType { PLAYER, AGENT }

    public enum TargetType { AGENT, PLAYER, OBJECT, TILE }

    private String actorId;       // "Player" or agent name
    private ActorType actorType;
    private String verb;          // action verb — must match a known affordance verb
    private String targetId;      // object instanceId, agent name, or tile ref "x,y"
    private TargetType targetType;
    private String itemId;        // nullable — InventoryItem.id being used/consumed
    private String payload;       // verb-specific content: speech text, written text, direction
    private double actorX;
    private double actorY;
    private int turnNumber;

    private WorldAction() {}

    // ── Factory methods ──────────────────────────────────────────────────────

    /**
     * Convert a PlayerActionRequest (HTTP DTO) to a WorldAction at the controller boundary.
     * The HTTP API shape is unchanged; conversion is internal only.
     */
    public static WorldAction fromPlayerAction(
            String playerId,
            String verb,
            String targetId,
            TargetType targetType,
            String itemId,
            String payload,
            double actorX,
            double actorY,
            int turnNumber) {

        WorldAction a = new WorldAction();
        a.actorId = playerId;
        a.actorType = ActorType.PLAYER;
        a.verb = verb;
        a.targetId = targetId;
        a.targetType = targetType;
        a.itemId = itemId;
        a.payload = payload;
        a.actorX = actorX;
        a.actorY = actorY;
        a.turnNumber = turnNumber;
        return a;
    }

    /**
     * Convert an AgentAction (cognition output) to a WorldAction before resolver.
     */
    public static WorldAction fromAgentAction(
            String agentName,
            AgentAction agentAction,
            int turnNumber) {

        WorldAction a = new WorldAction();
        a.actorId = agentName;
        a.actorType = ActorType.AGENT;
        a.verb = agentAction.getType();
        a.payload = agentAction.getSpeakText() != null
                ? agentAction.getSpeakText()
                : agentAction.getDescription();
        a.itemId = agentAction.getItem();
        a.turnNumber = turnNumber;

        // Resolve target and targetType
        if (agentAction.getTargetAgent() != null && !agentAction.getTargetAgent().isBlank()) {
            a.targetId = agentAction.getTargetAgent();
            a.targetType = TargetType.AGENT;
        } else if (agentAction.getTargetLocation() != null && !agentAction.getTargetLocation().isBlank()) {
            a.targetId = agentAction.getTargetLocation();
            a.targetType = TargetType.TILE;
        } else if (agentAction.getTargetX() != null && agentAction.getTargetY() != null) {
            a.targetId = agentAction.getTargetX() + "," + agentAction.getTargetY();
            a.targetType = TargetType.TILE;
            a.actorX = agentAction.getTargetX();
            a.actorY = agentAction.getTargetY();
        }

        return a;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getActorId() { return actorId; }
    public ActorType getActorType() { return actorType; }
    public String getVerb() { return verb; }
    public String getTargetId() { return targetId; }
    public TargetType getTargetType() { return targetType; }
    public String getItemId() { return itemId; }
    public String getPayload() { return payload; }
    public double getActorX() { return actorX; }
    public double getActorY() { return actorY; }
    public int getTurnNumber() { return turnNumber; }

    // ── Setters (used by builder-style callers) ───────────────────────────────

    public void setActorId(String actorId) { this.actorId = actorId; }
    public void setActorType(ActorType actorType) { this.actorType = actorType; }
    public void setVerb(String verb) { this.verb = verb; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public void setTargetType(TargetType targetType) { this.targetType = targetType; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public void setPayload(String payload) { this.payload = payload; }
    public void setActorX(double actorX) { this.actorX = actorX; }
    public void setActorY(double actorY) { this.actorY = actorY; }
    public void setTurnNumber(int turnNumber) { this.turnNumber = turnNumber; }

    @Override
    public String toString() {
        return String.format("WorldAction{actor=%s(%s), verb=%s, target=%s(%s), item=%s, turn=%d}",
                actorId, actorType, verb, targetId, targetType, itemId, turnNumber);
    }
}
