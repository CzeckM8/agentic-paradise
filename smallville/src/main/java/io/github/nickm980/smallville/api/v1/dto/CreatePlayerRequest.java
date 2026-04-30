package io.github.nickm980.smallville.api.v1.dto;

/**
 * Request to create or update a player character
 */
public class CreatePlayerRequest {
	private String name;
	private String location;
	private String activity;
	private String[] memories;

	public CreatePlayerRequest() {
	}

	public CreatePlayerRequest(String name, String location, String activity) {
		this.name = name;
		this.location = location;
		this.activity = activity;
		this.memories = new String[0];
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

	public String[] getMemories() {
		return memories;
	}

	public void setMemories(String[] memories) {
		this.memories = memories;
	}

	private String physicalDescription;
	public String getPhysicalDescription() { return physicalDescription; }
	public void setPhysicalDescription(String physicalDescription) { this.physicalDescription = physicalDescription; }
}
