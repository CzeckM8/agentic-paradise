package io.github.nickm980.smallville.api.v1.dto;

/**
 * Lightweight agent state for delta updates - only essential data
 * Reduces payload size compared to full AgentStateResponse
 */
public class AgentDeltaStateResponse {
    private String name;
    private String location;
    private String currentAction;
    private String emoji;
    private String object;
    private String targetLocation;
    private double stressLevel;
    private String mentalState;
    private double x;
    private double y;

    public AgentDeltaStateResponse() {}

    public AgentDeltaStateResponse(String name, String location, String currentAction, 
                                   String emoji, double stressLevel, String mentalState,
                                   double x, double y) {
        this.name = name;
        this.location = location;
        this.currentAction = currentAction;
        this.emoji = emoji;
        this.stressLevel = stressLevel;
        this.mentalState = mentalState;
        this.x = x;
        this.y = y;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCurrentAction() {
        return currentAction;
    }

    public void setCurrentAction(String currentAction) {
        this.currentAction = currentAction;
    }

    public String getEmoji() {
        return emoji;
    }

    public void setEmoji(String emoji) {
        this.emoji = emoji;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public String getTargetLocation() {
        return targetLocation;
    }

    public void setTargetLocation(String targetLocation) {
        this.targetLocation = targetLocation;
    }

    public double getStressLevel() {
        return stressLevel;
    }

    public void setStressLevel(double stressLevel) {
        this.stressLevel = stressLevel;
    }

    public String getMentalState() {
        return mentalState;
    }

    public void setMentalState(String mentalState) {
        this.mentalState = mentalState;
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
}
