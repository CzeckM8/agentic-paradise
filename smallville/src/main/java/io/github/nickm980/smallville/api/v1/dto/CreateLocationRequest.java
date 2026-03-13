package io.github.nickm980.smallville.api.v1.dto;


public class CreateLocationRequest {

    private String name;
    private String type = "generic";  // optional: "park", "home", "mall", "street", etc.
    private double minX = 0.0;
    private double maxX = 100.0;
    private double minY = 0.0;
    private double maxY = 100.0;

    public String getName() {
	return name;
    }

    public void setName(String name) {
	this.name = name;
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
