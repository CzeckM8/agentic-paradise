package io.github.nickm980.smallville.update;

import java.util.ArrayList;
import java.util.List;

import io.github.nickm980.smallville.World;
import io.github.nickm980.smallville.entities.AgentAction;
import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.entities.Location;
import io.github.nickm980.smallville.memory.Observation;
import io.github.nickm980.smallville.prompts.Prompts;
import io.github.nickm980.smallville.prompts.dto.CurrentActivity;

public class UpdateCurrentActivity extends AgentUpdate {

    @Override
    public boolean update(Prompts service, World world, Agent agent, UpdateInfo info) {
	LOG.info("[Activity] Updating current activity and emoji");

	CurrentActivity activity = service.getCurrentActivity(agent);
	LOG.debug(activity.getLocation());
	agent.setCurrentEmoji(activity.getEmoji());

	String desiredLocation = activity.getLocation();
	agent.replaceActionQueue(buildActions(agent, desiredLocation, activity.getActivity(), activity.getEmoji(), world));
	if (agent.getActiveAction() == null && !agent.getQueuedActions().isEmpty()) {
	    AgentAction next = agent.getQueuedActions().getFirst();
	    if (next.getDescription() != null && !next.getDescription().isBlank()) {
		agent.setCurrentActivity(next.getDescription());
	    }
	}

	if (activity.getLastActivity() != null && !activity.getLastActivity().isBlank()) {
	    agent.getMemoryStream().add(new Observation(activity.getLastActivity()));
	}

	return next(service, world, agent, info);
    }

    private List<AgentAction> buildActions(Agent agent, String desiredLocation, String activity, String emoji, World world) {
	List<AgentAction> actions = new ArrayList<>();
	Location currentLocation = agent.getLocation();
	Location destination = null;
	if (desiredLocation != null && !desiredLocation.isBlank()) {
	    destination = world.getLocation(desiredLocation).orElse(null);
	    if (destination == null) {
		LOG.warn("[Activity] Ignoring unknown destination location: {}", desiredLocation);
	    }
	}

	if (destination != null && (currentLocation == null || !destination.getFullPath().equals(currentLocation.getFullPath()))) {
	    AgentAction move = new AgentAction("move", "Going to " + destination.getFullPath());
	    move.setTargetLocation(destination.getFullPath());
	    move.setEmoji(emoji);
	    actions.add(move);
	}

	if (activity != null && !activity.isBlank()) {
	    AgentAction perform = new AgentAction("activity", activity);
	    perform.setEmoji(emoji);
	    if (destination != null) {
		perform.setTargetLocation(destination.getFullPath());
	    } else if (currentLocation != null) {
		perform.setTargetLocation(currentLocation.getFullPath());
	    }
	    actions.add(perform);
	}

	return actions;
    }
}
