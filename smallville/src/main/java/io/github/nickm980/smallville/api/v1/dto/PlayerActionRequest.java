package io.github.nickm980.smallville.api.v1.dto;

public class PlayerActionRequest {
    private String playerId;
    private String actionType;  // "move", "interact", "speak", "defend"
    private String targetLocation;  // For move actions
    private String targetAgent;     // For interact/speak actions
    private String actionDescription;  // Free text description of action
    private double intensity;  // 0.0 to 1.0 - intensity of action (for aggressive actions)
    private String item; // e.g. "knife", "gun"
    private long requestedDurationSeconds; // optional requested duration in seconds
    private long timestamp; // epoch millis optional
    private String flair; // optional short narrative flourish
    private String speakText; // optional speech text for interact/speak
    private double playerX;  // player's x coordinate within current/target location
    private double playerY;  // player's y coordinate within current/target location

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getTargetLocation() {
        return targetLocation;
    }

    public void setTargetLocation(String targetLocation) {
        this.targetLocation = targetLocation;
    }

    public String getTargetAgent() {
        return targetAgent;
    }

    public void setTargetAgent(String targetAgent) {
        this.targetAgent = targetAgent;
    }

    public String getActionDescription() {
        return actionDescription;
    }

    public void setActionDescription(String actionDescription) {
        this.actionDescription = actionDescription;
    }

    public double getIntensity() {
        return intensity;
    }

    public void setIntensity(double intensity) {
        this.intensity = Math.max(0.0, Math.min(1.0, intensity));
    }

    public String getItem() {
        return item;
    }

    public void setItem(String item) {
        this.item = item;
    }

    public long getRequestedDurationSeconds() {
        return requestedDurationSeconds;
    }

    public void setRequestedDurationSeconds(long requestedDurationSeconds) {
        this.requestedDurationSeconds = requestedDurationSeconds;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getFlair() {
        return flair;
    }

    public void setFlair(String flair) {
        this.flair = flair;
    }

    public String getSpeakText() {
        return speakText;
    }

    public void setSpeakText(String speakText) {
        this.speakText = speakText;
    }

    public double getPlayerX() {
        return playerX;
    }

    public void setPlayerX(double playerX) {
        this.playerX = playerX;
    }

    public double getPlayerY() {
        return playerY;
    }

    public void setPlayerY(double playerY) {
        this.playerY = playerY;
    }
}
