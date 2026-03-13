package io.github.nickm980.smallville.entities;

import java.util.ArrayList;
import java.util.List;

public class Location {

    /**
     * Seperates the location name by the colon delimitted parts. For example, 'Red
     * House: Bedroom: Bed' would all be included as individual elements in this array
     */
    private List<String> parts;
    /**
     * The location name without any seperation. Includes all colons.
     */
    private String original;
    private String state;
    
    // Spatial properties
    private String type;  // "park", "home", "mall", "street", etc.
    private double minX = 0.0;
    private double maxX = 100.0;
    private double minY = 0.0;
    private double maxY = 100.0;

    public Location(String name) {
	if (name.length() > 50) {
	    throw new IllegalArgumentException("Cannot have a location name greater than 50 characters");
	}

	parts = new ArrayList<String>();

	for (String s : name.split(":")) {
	    parts.add(s.trim());
	}
	
	this.original = name;
	this.type = "generic";
    }

    public void setState(String state) {
	this.state = state;
    }

    public String getState() {
	return state;
    }
    
    public String getFullPath() {
	return original;
    }
    
    public List<String> getAll() {
	return parts;
    }

    public String getType() {
	return type;
    }

    public void setType(String type) {
	this.type = type;
    }

    public double getMinX() {
	return minX;
    }

    public void setMinX(double minX) {
	this.minX = minX;
    }

    public double getMaxX() {
	return maxX;
    }

    public void setMaxX(double maxX) {
	this.maxX = maxX;
    }

    public double getMinY() {
	return minY;
    }

    public void setMinY(double minY) {
	this.minY = minY;
    }

    public double getMaxY() {
	return maxY;
    }

    public void setMaxY(double maxY) {
	this.maxY = maxY;
    }

    /**
     * Get center coordinates of location
     */
    public double getCenterX() {
	return (minX + maxX) / 2.0;
    }

    public double getCenterY() {
	return (minY + maxY) / 2.0;
    }

    /**
     * Check if coordinates are within location bounds
     */
    public boolean isWithinBounds(double x, double y) {
	return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }
}
