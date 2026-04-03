package io.github.nickm980.smallville.api.v1.dto;

import java.util.List;

public class AgentStateResponse {
    private String name;
    private String action;
    private String location;
    private String emoji;
    private String object;
    private double x;
    private double y;
<<<<<<< HEAD
<<<<<<< HEAD
    private String targetLocation;

=======
=======
>>>>>>> 09822c1 (Add queued action system for agents)
    private AgentActionStateResponse activeAction;
    private List<AgentActionStateResponse> queuedActions;
    
>>>>>>> 09822c1 (Add queued action system for agents)
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

<<<<<<< HEAD
<<<<<<< HEAD
    public String getTargetLocation() {
        return targetLocation;
    }

    public void setTargetLocation(String targetLocation) {
        this.targetLocation = targetLocation;
=======
=======
>>>>>>> 09822c1 (Add queued action system for agents)
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
<<<<<<< HEAD
>>>>>>> 09822c1 (Add queued action system for agents)
=======
>>>>>>> 09822c1 (Add queued action system for agents)
    }
}

