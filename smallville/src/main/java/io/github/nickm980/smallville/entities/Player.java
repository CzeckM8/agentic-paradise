package io.github.nickm980.smallville.entities;

import java.util.ArrayList;
import java.util.List;

import io.github.nickm980.smallville.memory.Characteristic;
import io.github.nickm980.smallville.memory.MemoryStream;

/**
 * Represents the player character controlled by the user in the simulation
 * Players are like agents but controlled by user input rather than AI
 */
public class Player extends Agent {

	private double stress = 0.5;  // 0.0 (calm) to 1.0 (panicked)
	private int numInteractions = 0;
	
	public Player(String name, Location location) {
		super(name, new ArrayList<Characteristic>(), "idle", location);
		this.stress = 0.5;
	}

	public Player(String name, List<Characteristic> characteristics, String activity, Location location) {
		super(name, characteristics, activity, location);
		this.stress = 0.5;
	}

	public double getStress() {
		return stress;
	}

	public void setStress(double stress) {
		this.stress = Math.max(0.0, Math.min(1.0, stress));  // Clamp 0-1
	}

	public void addStress(double amount) {
		setStress(stress + amount);
	}

	public void removeStress(double amount) {
		setStress(stress - amount);
	}

	/**
	 * Add a legacy string item as a basic InventoryItem with no grants.
	 * Use addInventoryItem(InventoryItem) for full grant support.
	 */
	public void addItem(String itemTypeId) {
		addInventoryItem(new InventoryItem(itemTypeId, itemTypeId));
	}

	/**
	 * Remove the first inventory item matching the given typeId.
	 */
	public void removeItem(String itemTypeId) {
		getInventory().values().stream()
			.filter(i -> itemTypeId.equals(i.getTypeId()))
			.findFirst()
			.ifPresent(i -> removeInventoryItem(i.getId()));
	}

	public int getNumInteractions() {
		return numInteractions;
	}

	public void incrementInteractions() {
		this.numInteractions++;
	}

	public void resetInteractions() {
		this.numInteractions = 0;
	}

	public void setNumInteractions(int numInteractions) {
		this.numInteractions = Math.max(0, numInteractions);
	}
}
