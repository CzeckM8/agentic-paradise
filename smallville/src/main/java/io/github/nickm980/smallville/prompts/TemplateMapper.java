package io.github.nickm980.smallville.prompts;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.nickm980.smallville.entities.EpistemicMemory;
import io.github.nickm980.smallville.memory.Memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.nickm980.smallville.config.SmallvilleConfig;
import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.memory.MemoryStream;
import io.github.nickm980.smallville.memory.Plan;
import io.github.nickm980.smallville.memory.PlanType;

/**
 * Creates the prompts used by other prompts and converts objects to natural
 * language
 *
 */
public class TemplateMapper {
    private static final Logger LOG = LoggerFactory.getLogger(TemplateMapper.class);

    public String buildAgentSummary(Agent agent) {
	String prompt = SmallvilleConfig.getPrompts().getAgent().getSummary();
	MemoryStream stream = agent.getMemoryStream();
	Map<String, Object> data = new HashMap<String, Object>();
	data.put("agent.name", agent.getFullName());
	data.put("agent.locationName", agent.getLocation().getFullPath());
	data.put("agent.description", stream.getCharacteristics().stream().map(c -> c.getDescription()).collect(Collectors.toList()));
	data.put("agent.traits", agent.getTraits());

	return new TemplateEngine().format(prompt, data);
    }

    public Map<String, Object> fromAgent(Agent agent) {
	Map<String, Object> result = new HashMap<String, Object>();

	MemoryStream stream = agent.getMemoryStream();
	String desc = String.join("; ", stream.getCharacteristics().stream().map(c -> c.getDescription()).collect(Collectors.toList()));

	if (stream.getPlans() == null) {
	    LOG.error("no plans found!!!");
	}

	result.put("name", agent.getFullName());
	result.put("memories", agent.getMemoryStream().getMemories().stream()
	    .filter(m -> !m.getDescription().contains("tile coordinates")
	             && !m.getDescription().matches(".*\\(\\d+,\\s*\\d+\\).*"))
	    .limit(10).collect(Collectors.toList()));
	result.put("activity", agent.getCurrentActivity());
	result.put("lastActivity", agent.getLastActivity());
	result.put("summary", buildAgentSummary(agent));
	result.put("locationName", agent.getLocation().getFullPath());
	result.put("locationChildren", agent.getLocation().getFullPath());
	result.put("description", desc);
	result.put("plans", stream.getPlans());
	result.put("shortPlans", stream.getPlans(PlanType.SHORT_TERM));
	result.put("recentMemories", agent.getMemoryStream().getRecentMemories());
	/*
	 * agent.plansBlock is a number list of the upcoming plans with a block [...]
	 * between the current time and the next time.
	 */
	result.put("plansBlock", buildPlansBlock(agent.getFullName(), stream.getPlans()));

	// Inventory: what the agent is currently carrying (kept in sync by SimulationService)
	java.util.List<String> carried = agent.getCarriedItemNames();
	result.put("inventory", carried == null || carried.isEmpty() ? "nothing" : String.join(", ", carried));
	result.put("hasInventory", carried != null && !carried.isEmpty());

	// Legal actions: computed by ActionResolver before each LLM call
	java.util.List<String> legal = agent.getLegalActions();
	result.put("legalActions", legal == null || legal.isEmpty()
		? "wait"
		: String.join(", ", legal));
	result.put("hasLegalActions", legal != null && !legal.isEmpty());

	// Belief summary: theory-of-mind narrative, set by SimulationService.refreshBeliefModels()
	String belief = agent.getBeliefSummary();
	result.put("beliefSummary", belief == null || belief.isBlank() ? "" : belief);
	result.put("hasBeliefSummary", belief != null && !belief.isBlank());

	// Health state
	result.put("health", agent.getHealth());
	result.put("isInjured", agent.getHealth() < 70);

	return result;
    }

    public String buildPlansBlock(String name, List<Plan> plans) {
	String result = "";
	LocalDateTime time = LocalDateTime.now();

	boolean includeBlock = false;
	int index = 0;
	boolean hasBeenUpdated = false;

	for (Plan plan : plans) {
	    result += "- " + plan.getDescription() + System.lineSeparator();
	    boolean hasPlanPast = plan.getTime().compareTo(time) < 0;

	    if (!hasBeenUpdated
		    && ((hasPlanPast && includeBlock) || index == plans.size() && !result.contains("[...]"))) {
		result += "[...]" + System.lineSeparator();
		hasBeenUpdated = true;
	    }

	    if (hasPlanPast) {
		includeBlock = true;
	    }

	    index++;
	}

	if (plans == null || plans.isEmpty()) {
	    result = """
	    	- $name will wake up at 8:00 AM
	    	[...]
	    	- $name will get ready for bed at 10:00 PM
	    	""";
	}

	result.replace("$name", name);

	return result;
    }

    /**
     * Builds a plain-text block of high-impact events for injection into
     * dialogue prompts so agents can authentically recall what happened to them.
     * Pulls from three sources: EpistemicMemory attacks, recent hearsay, and
     * MemoryStream entries with importance >= 6.
     */
    public String buildEventContext(Agent agent) {
        StringBuilder sb = new StringBuilder();

        List<EpistemicMemory.ObservedEvent> attacks = agent.getEpistemicMemory()
            .recentObserved(20).stream()
            .filter(e -> "attack".equals(e.verb)
                && (agent.getFullName().equals(e.targetId) || agent.getFullName().equals(e.actorId)))
            .collect(Collectors.toList());
        if (!attacks.isEmpty()) {
            sb.append("Combat events you experienced:\n");
            for (EpistemicMemory.ObservedEvent evt : attacks) {
                sb.append("- ").append(evt.toNarrative()).append("\n");
            }
        }

        List<EpistemicMemory.Hearsay> heard = agent.getEpistemicMemory().recentHearsay(5);
        if (!heard.isEmpty()) {
            sb.append("Things you have been told:\n");
            for (EpistemicMemory.Hearsay h : heard) {
                sb.append("- ").append(h.toNarrative()).append("\n");
            }
        }

        List<Memory> highImpact = agent.getMemoryStream().getMemories().stream()
            .filter(m -> m.getImportance() >= 6)
            .sorted(Comparator.comparingDouble(m -> -m.getImportance()))
            .limit(5)
            .collect(Collectors.toList());
        if (!highImpact.isEmpty()) {
            sb.append("Significant things you remember:\n");
            for (Memory m : highImpact) {
                sb.append("- ").append(m.getDescription()).append("\n");
            }
        }

        return sb.toString().trim();
    }

    public String buildRelevantMemories(Agent agent, String observation) {
	List<String> memories = agent
	    .getMemoryStream()
	    .getRelevantMemories(observation)
	    .stream()
	    .map(item -> item.getDescription())
	    .collect(Collectors.toList());

	String result = String.join("; ", memories);
	
	LOG.debug(agent.getFullName() + "'s relevant memories (" + observation + "): " + result);

	return result;
    }
}
