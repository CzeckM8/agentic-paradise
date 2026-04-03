package io.github.nickm980.smallville.api.v1.dto;

public class RuntimeOrchestrationRequest {
    private Double playerX;
    private Double playerY;
    private double awarenessRadius = 180.0;
    private boolean forceDayStart = false;
    private java.util.List<String> pinnedAgents = new java.util.ArrayList<>();
    private java.util.List<java.util.Map<String, Object>> npcPositions = new java.util.ArrayList<>();

    public java.util.List<String> getPinnedAgents() {
        return pinnedAgents;
    }

    public void addPinnedAgent(String agentName) {
        if (agentName != null && !agentName.isBlank()) {
            pinnedAgents.add(agentName);
        }
    }

    public boolean isPinned(String agentName) {
        return pinnedAgents != null && pinnedAgents.contains(agentName);
    }

    public Double getPlayerX() {
        return playerX;
    }

    public void setPlayerX(Double playerX) {
        this.playerX = playerX;
    }

    public Double getPlayerY() {
        return playerY;
    }

    public void setPlayerY(Double playerY) {
        this.playerY = playerY;
    }

    public double getAwarenessRadius() {
        return awarenessRadius;
    }

    public void setAwarenessRadius(double awarenessRadius) {
        this.awarenessRadius = awarenessRadius;
    }

    public boolean isForceDayStart() {
        return forceDayStart;
    }

    public void setForceDayStart(boolean forceDayStart) {
        this.forceDayStart = forceDayStart;
    }

    public java.util.List<java.util.Map<String, Object>> getNpcPositions() {
        return npcPositions;
    }

    public void setNpcPositions(java.util.List<java.util.Map<String, Object>> npcPositions) {
        this.npcPositions = npcPositions;
    }
}
