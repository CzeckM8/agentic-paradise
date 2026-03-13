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
	private String[] inventory = new String[0];
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

	public String[] getInventory() {
		return inventory;
	}

	public void setInventory(String[] inventory) {
		this.inventory = inventory;
	}

	public void addItem(String item) {
		String[] newInventory = new String[inventory.length + 1];
		System.arraycopy(inventory, 0, newInventory, 0, inventory.length);
		newInventory[inventory.length] = item;
		this.inventory = newInventory;
	}

	public void removeItem(String item) {
		String[] newInventory = new String[Math.max(0, inventory.length - 1)];
		int index = 0;
		for (String i : inventory) {
			if (!i.equals(item)) {
				if (index < newInventory.length) {
					newInventory[index++] = i;
				}
			}
		}
		this.inventory = newInventory;
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
}
