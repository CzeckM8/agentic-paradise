package io.github.nickm980.smallville.prompts.dto;

import io.github.nickm980.smallville.World;
import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.entities.Location;

public class WorldModel {

    private String description;

    public String getDescription() {
	return description;
    }

    public void setDescription(String description) {
	this.description = description;
    }

    public static WorldModel fromWorld(String name, World world) {
	WorldModel result = new WorldModel();
	String description = "Available Locations: ";

	for (Location location : world.getLocations()) {
	    description += location.getFullPath() + "; ";
	}

	for (Agent agent : world.getAgents()) {
	    if (agent.getFullName().equals(name)) {
		continue;
	    }
	    String activity = agent.getCurrentActivity();
	    // Sanitize internal game-engine activity strings so they read as natural language
	    if (activity == null || activity.isBlank()
		    || activity.startsWith("agentic:") || activity.startsWith("Agentic")
		    || activity.contains("IDLE") || activity.contains("TOOL_ACTION")
		    || activity.contains("blocked")) {
		activity = "going about their business";
	    }
	    description += agent.getFullName() + " is " + activity + " at "
		    + agent.getLocation().getFullPath() + " ";
	}

	result.setDescription(description);

	return result;
    }
}
