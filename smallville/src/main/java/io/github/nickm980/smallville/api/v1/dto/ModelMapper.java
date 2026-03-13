package io.github.nickm980.smallville.api.v1.dto;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.entities.Conversation;
import io.github.nickm980.smallville.entities.Dialog;
import io.github.nickm980.smallville.entities.Location;
import io.github.nickm980.smallville.memory.Characteristic;
import io.github.nickm980.smallville.memory.Memory;
import io.github.nickm980.smallville.memory.Plan;
import io.github.nickm980.smallville.memory.Reflection;
import io.github.nickm980.smallville.memory.TemporalMemory;

public class ModelMapper {

    public AgentStateResponse fromAgent(Agent agent) {
	AgentStateResponse result = new AgentStateResponse();
	result.setAction(agent.getCurrentActivity());
	result.setEmoji(agent.getEmoji());
	if (agent.getLocation() != null) {
	    result.setLocation(agent.getLocation().getFullPath());
	}
	result.setName(agent.getFullName());
	result.setX(agent.getX());
	result.setY(agent.getY());
	return result;
    }

    public AgentDeltaStateResponse fromAgentDelta(Agent agent) {
	AgentDeltaStateResponse result = new AgentDeltaStateResponse();
	result.setName(agent.getFullName());
	if (agent.getLocation() != null) {
	    result.setLocation(agent.getLocation().getFullPath());
	}
	result.setCurrentAction(agent.getCurrentActivity());
	result.setEmoji(agent.getEmoji());
	result.setStressLevel(agent.getStressLevel());
	result.setMentalState(agent.getMentalState());
	return result;
    }

    public LocationStateResponse fromLocation(Location location) {
	LocationStateResponse result = new LocationStateResponse();
	result.setName(location.getFullPath());
	result.setState(location.getState());
	result.setType(location.getType());
	result.setMinX(location.getMinX());
	result.setMaxX(location.getMaxX());
	result.setMinY(location.getMinY());
	result.setMaxY(location.getMaxY());
	return result;
    }

    public MemoryResponse fromMemory(Memory memory) {
	MemoryResponse result = new MemoryResponse();

	result.setDescription(memory.getDescription());
	result.setType("Observation");
	result.setImportance(memory.getImportance());

	if (memory instanceof Plan) {
	    result.setType("Plan");
	}

	if (memory instanceof Characteristic) {
	    result.setType("Characteristic");
	}

	if (memory instanceof Reflection) {
	    result.setType("Reflection");
	}

	if (memory instanceof TemporalMemory) {
	    result.setTime(((TemporalMemory) memory).getTime().format(DateTimeFormatter.ofPattern("hh:mm a")));
	}

	return result;
    }

    public List<ConversationResponse> fromConversation(Conversation conversation) {
	List<ConversationResponse> result = new ArrayList<ConversationResponse>();

	for (Dialog dialog : conversation.getDialog()) {
	    ConversationResponse response = new ConversationResponse();

	    response.setName(dialog.getName());
	    response.setMessage(dialog.getMessage());
	    result.add(response);
	}

	return result;
    }
}
