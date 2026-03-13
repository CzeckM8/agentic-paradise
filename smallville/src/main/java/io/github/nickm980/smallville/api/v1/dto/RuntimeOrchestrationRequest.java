package io.github.nickm980.smallville.api.v1.dto;

public class RuntimeOrchestrationRequest {
    private Double playerX;
    private Double playerY;
    private double awarenessRadius = 180.0;
    private boolean forceDayStart = false;

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
}
