package io.github.nickm980.smallville.api.v1.dto;

/**
 * Represents the player character's current state as sent to clients
 */
public class PlayerStateResponse {
	private String name;
	private String location;
	private String activity;
	private double stress;
	private String[] inventory;
	private double x;  // x position within location
	private double y;  // y position within location

	public PlayerStateResponse() {
	}

	public PlayerStateResponse(String name, String location, String activity, double stress) {
		this.name = name;
		this.location = location;
		this.activity = activity;
		this.stress = stress;
		this.inventory = new String[0];
		this.x = 0;
		this.y = 0;
	}

	// Getters and Setters
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

	public String getActivity() {
		return activity;
	}

	public void setActivity(String activity) {
		this.activity = activity;
	}

	public double getStress() {
		return stress;
	}

	public void setStress(double stress) {
		this.stress = stress;
	}

	public String[] getInventory() {
		return inventory;
	}

	public void setInventory(String[] inventory) {
		this.inventory = inventory;
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
