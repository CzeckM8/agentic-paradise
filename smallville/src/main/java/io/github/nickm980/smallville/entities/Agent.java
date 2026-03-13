package io.github.nickm980.smallville.entities;

import java.util.List;

import io.github.nickm980.smallville.memory.Characteristic;
import io.github.nickm980.smallville.memory.MemoryStream;

public class Agent {

    private MemoryStream memories;
    private String name;
    private ActionHistory currentAction;
    private Location location;
    private String targetLocation;
    private String traits;
    private double x = 0.0;
    private double y = 0.0;
    private boolean hasBeenOrchestrated = false; // Prevents movement on first turn after creation
    
    public Agent(String name, List<Characteristic> characteristics, String currentAction, Location location) {
	this.name = name;
	this.memories = new MemoryStream();
	this.memories.addAll(characteristics);
	this.location = location;
	this.currentAction = new ActionHistory(currentAction);
	// Initialize to center of location
	if (location != null) {
	    this.x = location.getCenterX();
	    this.y = location.getCenterY();
	}
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
}