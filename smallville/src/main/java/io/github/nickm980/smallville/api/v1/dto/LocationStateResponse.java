package io.github.nickm980.smallville.api.v1.dto;

public class LocationStateResponse {

    private String name;
    private String state;
    private String type;
    private double minX;
    private double maxX;
    private double minY;
    private double maxY;

    public String getName() {
	return name;
    }

    public void setName(String name) {
	this.name = name;
    }

    public String getState() {
	return state;
    }

    public void setState(String state) {
	this.state = state;
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
}
