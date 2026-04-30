package io.github.nickm980.smallville.entities;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import io.github.nickm980.smallville.memory.Characteristic;
import io.github.nickm980.smallville.memory.MemoryStream;

import java.util.HashMap;
import java.util.Map;

public class Agent {

    private MemoryStream memories;
    private String name;
    private ActionHistory currentAction;
    private Location location;
    private String targetLocation;
    private String traits;
    private double aggression;
    private double fearfulness;
    private double loyalty;
    private double impulsivity;
    private double compassion;
    private double riskTolerance;
    private double socialDominance;

    /**
     * Mutable trait adjustments accumulated from end-of-day reflection.
     * Each entry is bounded to [-0.3, +0.3] to prevent runaway drift.
     * Individual per-reflection signals are capped at ±0.1.
     * The effective trait value = clamp01(base + delta).
     */
    private final Map<String, Double> traitDeltas = new HashMap<>();
    private int health = 100;
    private static final int MAX_HEALTH = 100;
    private double x = 0.0;
    private double y = 0.0;
    private boolean hasBeenOrchestrated = false; // Prevents movement on first turn after creation
    private final Deque<AgentAction> actionQueue = new ArrayDeque<>();
    private AgentAction activeAction;
    private boolean deferScriptedActivityPresentation;

    /**
     * Typed inventory: itemId → InventoryItem.
     * ActionResolver reads this to enforce grant-gated verbs.
     */
    private final Map<String, InventoryItem> inventory = new HashMap<>();

    /**
     * Human-readable names of world objects currently in the agent's legacy inventory.
     * Kept in sync by SimulationService so LLM prompts can reference what the agent carries.
     */
    private List<String> carriedItemNames = new ArrayList<>();

    /**
     * Transient: legal actions available to this agent right now.
     * Set by SimulationService before each LLM planning call via ActionResolver.
     * Not persisted; cleared after use.
     */
    private List<String> legalActions = new ArrayList<>();

    /**
     * Transient: narrative summary of what this agent believes about other agents.
     * Set by SimulationService.refreshBeliefModels() before each LLM planning call.
     * Empty string when no beliefs have been established yet.
     * Not persisted; regenerated each turn from AgenticRuntimeState.beliefModels.
     */
    private String beliefSummary = "";

    /** Visual description generated at agent creation — shown to other agents via scan_nearby. */
    private String physicalDescription = null;

    /** The location this agent considers home — used for evening return hints. */
    private String homeLocation = null;

    /** Formatted daily schedule (morning/noon/evening anchors) — injected into situation briefing. */
    private String dailySchedule = null;

    /**
     * Pending intent injected into the next turn's LLM briefing by speech consequence processing.
     * Set when someone speaks a threat, greeting, or key information to this agent.
     * Consumed (cleared) by buildAgentTurnContext the turn after it is set.
     */
    private String pendingDialogueIntent = null;

    /**
     * Persistent grudges: targetName → simulation turn the grudge expires.
     * Injected into every turn's situation briefing until expiry.
     * Uses max-merge so later attacks extend rather than reset the grudge.
     */
    private final Map<String, Integer> grudges = new java.util.concurrent.ConcurrentHashMap<>();

    public void addGrudge(String targetName, int expiryTurn) {
        if (targetName == null || targetName.isBlank()) return;
        grudges.merge(targetName, expiryTurn, Math::max);
    }

    /** Returns active grudge targets, pruning expired entries in place. */
    public List<String> getActiveGrudgeTargets(int currentTurn) {
        grudges.entrySet().removeIf(e -> e.getValue() <= currentTurn);
        return new ArrayList<>(grudges.keySet());
    }

    /**
     * What this agent believes to be true about the world.
     * Populated only from PerceptionChannel and BeliefCorrections —
     * never from raw Chronicle data.
     */
    private final EpistemicMemory epistemicMemory = new EpistemicMemory();

    /**
     * Mental map: last-known position of every world object and agent.
     * Seeded at turn 0 with the full world state so agents know where things
     * are without having visited them. Updated by scan_nearby observations.
     * Stale entries are intentional — agents may act on outdated knowledge.
     *
     * Key: objectId or agentName.
     * Value: {id, name, type, x, y, tx, ty, location, turn}.
     */
    private final Map<String, Map<String, Object>> spatialKnowledge = new java.util.concurrent.ConcurrentHashMap<>();

    public Map<String, Map<String, Object>> getSpatialKnowledge() { return spatialKnowledge; }

    public void updateSpatialKnowledge(String id, String name, String type,
                                       double x, double y, String location, int turn) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("id", id);
        entry.put("name", name);
        entry.put("type", type);
        entry.put("tx", (int)(x / 32.0));
        entry.put("ty", (int)(y / 32.0));
        entry.put("location", location != null ? location : "unknown");
        entry.put("turn", turn);
        spatialKnowledge.put(id, entry);
    }

    public List<Map<String, Object>> querySpatialKnowledge(String query) {
        if (query == null || query.isBlank()) {
            return new java.util.ArrayList<>(spatialKnowledge.values());
        }
        String lower = query.toLowerCase();
        return spatialKnowledge.values().stream()
            .filter(e -> String.valueOf(e.getOrDefault("name", "")).toLowerCase().contains(lower)
                || String.valueOf(e.getOrDefault("id", "")).toLowerCase().contains(lower)
                || String.valueOf(e.getOrDefault("type", "")).toLowerCase().contains(lower)
                || String.valueOf(e.getOrDefault("location", "")).toLowerCase().contains(lower))
            .collect(java.util.stream.Collectors.toList());
    }
    
    public Agent(String name, List<Characteristic> characteristics, String currentAction, Location location) {
	this.name = name;
	this.memories = new MemoryStream();
	this.memories.addAll(characteristics);
	this.location = location;
	this.currentAction = new ActionHistory(currentAction);
    initializeTemperament(name);
	// Initialize to center of location
	if (location != null) {
	    this.x = location.getCenterX();
	    this.y = location.getCenterY();
	}
    }

    private void initializeTemperament(String seedName) {
    int seed = Math.abs(seedName.hashCode());
    this.aggression = scaledTrait(seed, 0);
    this.fearfulness = scaledTrait(seed, 1);
    this.loyalty = scaledTrait(seed, 2);
    this.impulsivity = scaledTrait(seed, 3);
    this.compassion = scaledTrait(seed, 4);
    this.riskTolerance = scaledTrait(seed, 5);
    this.socialDominance = scaledTrait(seed, 6);
    }

    private double scaledTrait(int seed, int salt) {
    int shifted = (seed >> (salt * 3)) ^ (seed << (salt + 1));
    int normalized = Math.abs(shifted % 101);
    return normalized / 100.0;
    }

    public String getFullName() {
	return name;
    }

    public String getCurrentActivity() {
	return currentAction.getActivity();
    }

    public String getLastActivity() {
	return currentAction.getLastActivity();
    }

    public void setCurrentActivity(String description) {
	this.currentAction.setActivity(description);
    }

    public Location getLocation() {
	return location;
    }

    public void setLocation(Location location) {
	this.location = location;
    }

    public String getTargetLocation() {
    	return targetLocation;
    }

    public void setTargetLocation(String targetLocation) {
	if (targetLocation == null || targetLocation.isBlank()) {
	    this.targetLocation = null;
	    return;
	}
	this.targetLocation = targetLocation;
    }

    public void setCurrentEmoji(String emoji) {
	this.currentAction.setEmoji(emoji);
    }

    public String getEmoji() {
	return currentAction.getEmoji();
    }

    public MemoryStream getMemoryStream() {
	return memories;
    }

    public void setTraits(String goal) {
	this.traits = goal;
    }

    public String getTraits() {
	return traits;
    }

    // ── Trait getters (effective = base + accumulated reflection delta) ─────────

    public double getAggression()     { return clamp01(aggression     + traitDeltas.getOrDefault("aggression",     0.0)); }
    public double getFearfulness()    { return clamp01(fearfulness    + traitDeltas.getOrDefault("fearfulness",    0.0)); }
    public double getLoyalty()        { return clamp01(loyalty        + traitDeltas.getOrDefault("loyalty",        0.0)); }
    public double getImpulsivity()    { return clamp01(impulsivity    + traitDeltas.getOrDefault("impulsivity",    0.0)); }
    public double getCompassion()     { return clamp01(compassion     + traitDeltas.getOrDefault("compassion",     0.0)); }
    public double getRiskTolerance()  { return clamp01(riskTolerance  + traitDeltas.getOrDefault("riskTolerance",  0.0)); }
    public double getSocialDominance(){ return clamp01(socialDominance+ traitDeltas.getOrDefault("socialDominance",0.0)); }

    /**
     * Apply a trait delta from end-of-day reflection.
     * Individual signals are capped at ±0.1; the running cumulative total per
     * trait is capped at ±0.3 to prevent extreme drift across many days.
     *
     * @param traitName one of: aggression, fearfulness, loyalty, impulsivity,
     *                  compassion, riskTolerance, socialDominance
     * @param delta     the suggested change (LLM output, typically ±0.02–0.10)
     */
    public void applyTraitDelta(String traitName, double delta) {
        if (traitName == null || traitName.isBlank()) return;
        if (delta == 0.0) return;                                     // no-op; don't pollute map
        double capped = Math.min(0.1, Math.max(-0.1, delta));        // per-signal cap
        double current = traitDeltas.getOrDefault(traitName, 0.0);
        double next = Math.min(0.3, Math.max(-0.3, current + capped)); // cumulative cap
        traitDeltas.put(traitName, next);
    }

    /** Returns an unmodifiable view of current trait deltas, keyed by trait name. */
    public Map<String, Double> getTraitDeltas() {
        return java.util.Collections.unmodifiableMap(traitDeltas);
    }

    private static double clamp01(double v) { return Math.min(1.0, Math.max(0.0, v)); }

    public int getHealth() { return health; }
    public boolean isIncapacitated() { return health <= 0; }
    public void applyDamage(int amount) { health = Math.max(0, health - amount); }
    public void recoverHealth(int amount) { health = Math.min(MAX_HEALTH, health + amount); }

    public double getStressLevel() {
        return this.currentAction.getStressLevel();
    }

    public String getMentalState() {
        return this.currentAction.getMentalState();
    }

    public void applyStressChange(double delta) {
        this.currentAction.applyStressChange(delta);
    }
    
    public double getX() {
	return x;
    }

    public void setX(double x) {
	this.x = x;
    }

    public double getY() {
	return y;
    }

    public void setY(double y) {
	this.y = y;
    }

    public void setPosition(double x, double y) {
	this.x = x;
	this.y = y;
    }

    /**
     * Calculate distance to another agent
     */
    public double distanceTo(Agent other) {
	if (other == null) return Double.MAX_VALUE;
	double dx = this.x - other.x;
	double dy = this.y - other.y;
	return Math.sqrt(dx * dx + dy * dy);
    }

    public boolean hasBeenOrchestrated() {
	return hasBeenOrchestrated;
    }

    public void setHasBeenOrchestrated(boolean orchestrated) {
	this.hasBeenOrchestrated = orchestrated;
    }

    public boolean isDeferScriptedActivityPresentation() {
	return deferScriptedActivityPresentation;
    }

    public void setDeferScriptedActivityPresentation(boolean deferScriptedActivityPresentation) {
	this.deferScriptedActivityPresentation = deferScriptedActivityPresentation;
    }

    public AgentAction getActiveAction() {
        return activeAction == null ? null : activeAction.copy();
    }

    public List<AgentAction> getQueuedActions() {
        return actionQueue.stream().map(AgentAction::copy).toList();
    }

    public List<AgentAction> getPendingActions() {
        List<AgentAction> actions = new ArrayList<>();
        if (activeAction != null) {
            actions.add(activeAction.copy());
        }
        actions.addAll(getQueuedActions());
        return actions;
    }

    public boolean hasPendingActions() {
        return activeAction != null || !actionQueue.isEmpty();
    }

    public void enqueueAction(AgentAction action) {
        if (action == null) {
            return;
        }
        action.setStatus("queued");
        actionQueue.addLast(action);
    }

    public void enqueueActions(List<AgentAction> actions) {
        if (actions == null) {
            return;
        }
        for (AgentAction action : actions) {
            enqueueAction(action);
        }
    }

    public void replaceActionQueue(List<AgentAction> actions) {
        activeAction = null;
        actionQueue.clear();
        targetLocation = null;
        deferScriptedActivityPresentation = false;
        enqueueActions(actions);
    }

    public AgentAction startNextAction() {
        if (activeAction != null) {
            return activeAction.copy();
        }
        activeAction = actionQueue.pollFirst();
        if (activeAction != null) {
            activeAction.setStatus("in_progress");
        }
        return getActiveAction();
    }

    public AgentAction completeActiveAction() {
        if (activeAction == null) {
            return null;
        }
        AgentAction completed = activeAction.copy();
        completed.setStatus("completed");
        activeAction = null;
        targetLocation = null;
        return completed;
    }

    public void clearActions() {
        activeAction = null;
        actionQueue.clear();
        targetLocation = null;
        deferScriptedActivityPresentation = false;
    }

    // ── Inventory ────────────────────────────────────────────────────────────

    public Map<String, InventoryItem> getInventory() {
        return inventory;
    }

    public void addInventoryItem(InventoryItem item) {
        inventory.put(item.getId(), item);
    }

    public InventoryItem removeInventoryItem(String itemId) {
        return inventory.remove(itemId);
    }

    public boolean hasInventoryItem(String itemId) {
        return inventory.containsKey(itemId);
    }

    public List<String> getCarriedItemNames() {
        return carriedItemNames;
    }

    public void setCarriedItemNames(List<String> names) {
        this.carriedItemNames = names == null ? new ArrayList<>() : names;
    }

    // ── Legal actions (transient — set by SimulationService before LLM calls) ──

    public List<String> getLegalActions() {
        return legalActions;
    }

    public void setLegalActions(List<String> legalActions) {
        this.legalActions = legalActions == null ? new ArrayList<>() : legalActions;
    }

    // ── Belief summary (theory of mind — transient) ───────────────────────────

    public String getBeliefSummary() {
        return beliefSummary;
    }

    public void setBeliefSummary(String beliefSummary) {
        this.beliefSummary = beliefSummary == null ? "" : beliefSummary;
    }

    // ── Physical description ──────────────────────────────────────────────────

    public String getPhysicalDescription() {
        return physicalDescription;
    }

    public void setPhysicalDescription(String physicalDescription) {
        this.physicalDescription = physicalDescription;
    }

    // ── Home location and daily schedule ─────────────────────────────────────

    public String getHomeLocation() {
        return homeLocation;
    }

    public void setHomeLocation(String homeLocation) {
        this.homeLocation = homeLocation;
    }

    public String getDailySchedule() {
        return dailySchedule;
    }

    public void setDailySchedule(String dailySchedule) {
        this.dailySchedule = dailySchedule;
    }

    // ── Pending dialogue intent (dialogue consequence system) ─────────────────

    public String getPendingDialogueIntent() {
        return pendingDialogueIntent;
    }

    public void setPendingDialogueIntent(String intent) {
        this.pendingDialogueIntent = intent;
    }

    // ── Epistemic memory ─────────────────────────────────────────────────────

    public EpistemicMemory getEpistemicMemory() {
        return epistemicMemory;
    }
}
