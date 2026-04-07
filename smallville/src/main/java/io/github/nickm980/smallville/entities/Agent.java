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
     * What this agent believes to be true about the world.
     * Populated only from PerceptionChannel and BeliefCorrections —
     * never from raw Chronicle data.
     */
    private final EpistemicMemory epistemicMemory = new EpistemicMemory();
    
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

    public double getAggression() {
	return aggression;
    }

    public double getFearfulness() {
	return fearfulness;
    }

    public double getLoyalty() {
	return loyalty;
    }

    public double getImpulsivity() {
	return impulsivity;
    }

    public double getCompassion() {
	return compassion;
    }

    public double getRiskTolerance() {
	return riskTolerance;
    }

    public double getSocialDominance() {
	return socialDominance;
    }
    
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

    // ── Epistemic memory ─────────────────────────────────────────────────────

    public EpistemicMemory getEpistemicMemory() {
        return epistemicMemory;
    }
}
