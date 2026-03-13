package io.github.nickm980.smallville.update;

import io.github.nickm980.smallville.Util;
import io.github.nickm980.smallville.World;
import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.memory.Observation;
import io.github.nickm980.smallville.prompts.Prompts;
import io.github.nickm980.smallville.prompts.dto.CurrentActivity;

public class UpdateCurrentActivity extends AgentUpdate {

    @Override
    public boolean update(Prompts service, World world, Agent agent, UpdateInfo info) {
	LOG.info("[Activity] Updating current activity and emoji");

	CurrentActivity activity = service.getCurrentActivity(agent);
	LOG.debug(activity.getLocation());
	agent.setCurrentActivity(activity.getActivity());
	agent.setCurrentEmoji(activity.getEmoji());

	String desiredLocation = activity.getLocation();
	if (desiredLocation != null && !desiredLocation.isBlank()) {
	    world.getLocation(desiredLocation).ifPresentOrElse(loc -> {
		agent.setTargetLocation(loc.getFullPath());
		if (agent.getLocation() != null && agent.getLocation().getFullPath().equals(loc.getFullPath())) {
		    agent.setTargetLocation(null);
		}
	    }, () -> LOG.warn("[Activity] Ignoring unknown destination location: {}", desiredLocation));
	}

	if (activity.getLastActivity() != null && !activity.getLastActivity().isBlank()) {
	    agent.getMemoryStream().add(new Observation(activity.getLastActivity()));
	}

	return next(service, world, agent, info);
    }
}
