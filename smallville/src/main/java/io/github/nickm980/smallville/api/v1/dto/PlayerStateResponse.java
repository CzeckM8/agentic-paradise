package io.github.nickm980.smallville.api.v1.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents the player character's current state as sent to clients
 */
public class PlayerStateResponse {
	private String name;
	private String location;
	private String activity;
	private double stress;
	private String[] inventory;
	/** Full object data for each carried world-object ID in inventory. */
	private List<Map<String, Object>> inventoryObjects;
	private double x;
	private double y;

	public PlayerStateResponse() {
	}

	public PlayerStateResponse(String name, String location, String activity, double stress) {
		this.name = name;
		this.location = location;
		this.activity = activity;
		this.stress = stress;
		this.inventory = new String[0];
		this.inventoryObjects = Collections.emptyList();
		this.x = 0;
		this.y = 0;
	}

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
	public String getLocation() { return location; }
	public void setLocation(String location) { this.location = location; }
	public String getActivity() { return activity; }
	public void setActivity(String activity) { this.activity = activity; }
	public double getStress() { return stress; }
	public void setStress(double stress) { this.stress = stress; }
	public String[] getInventory() { return inventory; }
	public void setInventory(String[] inventory) { this.inventory = inventory; }
	public List<Map<String, Object>> getInventoryObjects() { return inventoryObjects; }
	public void setInventoryObjects(List<Map<String, Object>> inventoryObjects) { this.inventoryObjects = inventoryObjects; }
	public double getX() { return x; }
	public void setX(double x) { this.x = x; }
	public double getY() { return y; }
	public void setY(double y) { this.y = y; }
}
