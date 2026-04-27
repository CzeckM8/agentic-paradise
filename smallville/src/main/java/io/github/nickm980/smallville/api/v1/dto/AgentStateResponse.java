package io.github.nickm980.smallville.api.v1.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AgentStateResponse {
    private String name;
    private String action;
    private String location;
    private String emoji;
    private String object;
    private double x;
    private double y;
    private String targetLocation;
    private AgentActionStateResponse activeAction;
    private List<AgentActionStateResponse> queuedActions;
    /** Full property data for each carried world-object (mirrors PlayerStateResponse.inventoryObjects). */
    private List<Map<String, Object>> inventoryObjects = Collections.emptyList();
    private int health = 100;
    private boolean incapacitated = false;

    public String getObject() {
	return object;
    }

    public void setObject(String object) {
	this.object = object;
    }

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getAction() {
        return action;
    }
    public void setAction(String action) {
        this.action = action;
    }
    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getEmoji() {
        return emoji;
    }
    public void setEmoji(String emoji) {
        this.emoji = emoji;
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

    public String getTargetLocation() {
        return targetLocation;
    }

    public void setTargetLocation(String targetLocation) {
        this.targetLocation = targetLocation;
    }

    public AgentActionStateResponse getActiveAction() {
        return activeAction;
    }

    public void setActiveAction(AgentActionStateResponse activeAction) {
        this.activeAction = activeAction;
    }

    public List<AgentActionStateResponse> getQueuedActions() {
        return queuedActions;
    }

    public void setQueuedActions(List<AgentActionStateResponse> queuedActions) {
        this.queuedActions = queuedActions;
    }

    public List<Map<String, Object>> getInventoryObjects() {
        return inventoryObjects;
    }

    public void setInventoryObjects(List<Map<String, Object>> inventoryObjects) {
        this.inventoryObjects = inventoryObjects;
    }

    public int getHealth() { return health; }
    public void setHealth(int health) { this.health = health; }

    public boolean isIncapacitated() { return incapacitated; }
    public void setIncapacitated(boolean incapacitated) { this.incapacitated = incapacitated; }
}
