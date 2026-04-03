package io.github.nickm980.smallville.update;

import java.util.List;
import java.util.stream.Collectors;

import io.github.nickm980.smallville.World;
import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.entities.Conversation;
import io.github.nickm980.smallville.entities.SimulationTime;
import io.github.nickm980.smallville.memory.Commitment;
import io.github.nickm980.smallville.memory.CommitmentStatus;
import io.github.nickm980.smallville.memory.Observation;
import io.github.nickm980.smallville.memory.Plan;
import io.github.nickm980.smallville.memory.PlanType;
import io.github.nickm980.smallville.nlp.LocalNLP;
import io.github.nickm980.smallville.nlp.NLPCoreUtils;
import io.github.nickm980.smallville.prompts.ChatService;
import io.github.nickm980.smallville.prompts.Prompts;
import io.github.nickm980.smallville.prompts.dto.Reaction;

/**
 * The UpdateFuturePlans class is responsible for creating and updating the
 * short and long term plans of an agent based on reactable observations.
 */
public class UpdatePlans extends AgentUpdate {

    @Override
    public boolean update(Prompts converter, World world, Agent agent, UpdateInfo info) {
	LOG.info("[Plans] Updating plans...");
	boolean hasPlans = !agent.getMemoryStream().getPlans().isEmpty();
	boolean shouldUpdatePlans = !hasPlans;
	String observation = info.getObservation();
	
	if (observation != null && !observation.isEmpty()) {
	    LOG.info("starting reaction to an observation");
	    Reaction reaction = converter.shouldUpdatePlans(agent, observation);
	    String answer = reaction != null && reaction.getAnswer() != null ? reaction.getAnswer() : "";
	    String conversation = reaction != null && reaction.getConversation() != null ? reaction.getConversation() : "";
	    shouldUpdatePlans = answer.toLowerCase().contains("yes");
	    info.setShouldUpdateConversation(conversation.toLowerCase().contains("yes"));
	}

	if (shouldUpdatePlans) {
	    LOG.info("[Plans] Reacting to observation [" + info.getObservation() + "]");
	    agent.getMemoryStream().prunePlans(PlanType.LONG_TERM);
	    agent.getMemoryStream().prunePlans(PlanType.SHORT_TERM);
	    updatePlans(converter, agent, PlanType.LONG_TERM);
	    updatePlans(converter, agent, PlanType.SHORT_TERM);
	}

	if (agent.getMemoryStream().getPlans(PlanType.LONG_TERM).isEmpty()) {
	    updatePlans(converter, agent, PlanType.LONG_TERM);
	}
	
	if (agent.getMemoryStream().getPlans(PlanType.SHORT_TERM).isEmpty()) {
	    updatePlans(converter, agent, PlanType.SHORT_TERM);
	}

	// Generate commitment-based daily schedule when there are no active/pending
	// commitments left (day start, first run) or when plans just got replanned.
	boolean hasActiveCommitments = agent.getMemoryStream().getPlans(PlanType.COMMITMENT).stream()
	    .filter(p -> p instanceof Commitment)
	    .map(p -> (Commitment) p)
	    .anyMatch(c -> c.getStatus() != CommitmentStatus.COMPLETED && !c.isExpired(SimulationTime.now()));

	if (!hasActiveCommitments || shouldUpdatePlans) {
	    agent.getMemoryStream().prunePlans(PlanType.COMMITMENT);
	    List<Commitment> commitments = converter.getCommitments(agent);
	    agent.getMemoryStream().addAll(commitments);
	    LOG.info("[Plans] Generated {} commitments for {}", commitments.size(), agent.getFullName());
	}

	LOG.info("[Plans] Plans updated");

	info.setPlansUpdated(shouldUpdatePlans);
	return next(converter, world, agent, info);
    }

    private void updatePlans(Prompts converter, Agent agent, PlanType type) {
	List<Plan> plans = type == PlanType.LONG_TERM ? converter.getPlans(agent) : converter.getShortTermPlans(agent);

	for (Plan plan : plans) {
	    plan.convert(type);
	    LOG.debug("[Plans] " + plan.getType() + " " + plan.getDescription());
	}

	agent.getMemoryStream().setPlans(plans, type);

	LOG.info("[Plans] Updated " + type.toString() + " plans");
    }
}
