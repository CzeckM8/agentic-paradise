package io.github.nickm980.smallville.prompts;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nickm980.smallville.Util;
import io.github.nickm980.smallville.World;
import io.github.nickm980.smallville.config.SmallvilleConfig;
import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.entities.Conversation;
import io.github.nickm980.smallville.entities.Dialog;
import io.github.nickm980.smallville.entities.SimulationTime;
import io.github.nickm980.smallville.llm.LLM;
import io.github.nickm980.smallville.memory.Commitment;
import io.github.nickm980.smallville.memory.Memory;
import io.github.nickm980.smallville.memory.Plan;
import io.github.nickm980.smallville.memory.Reflection;
import io.github.nickm980.smallville.nlp.LocalNLP;
import io.github.nickm980.smallville.prompts.dto.CurrentActivity;
import io.github.nickm980.smallville.prompts.dto.ObjectChangeResponse;
import io.github.nickm980.smallville.prompts.dto.Reaction;

public class ChatService implements Prompts {

    private final LLM chat;
	private final static Logger LOG = LoggerFactory.getLogger(ChatService.class);
    private final World world;

    public ChatService(World world, LLM chat) {
	this.chat = chat;
	this.world = world;
    }

    @Override
    public int[] getWeights(Agent agent) {
	PromptRequest prompt = new PromptBuilder()
	    .withAgent(agent)
	    .setPrompt(SmallvilleConfig.getPrompts().getMisc().getRankMemories())
	    .build();

	String response = chat.sendChat(prompt, .1);
	response = response.replace(",]", "]");

	ObjectMapper objectMapper = new ObjectMapper();
	int[] result = new int[0];

	if (!response.contains("[")) {
	    result = new int[1];
	    result[0] = Integer.parseInt(response);
	    return result;
	}

	try {
	    result = objectMapper.readValue(response, int[].class);
	} catch (JsonProcessingException e) {
	    LOG.error("Failed to parse json for memory ranking. Continuing anyways...");
	}

	return result;
    }

    @Override
    public String ask(Agent agent, String question) {
	PromptRequest prompt = new PromptBuilder()
	    .withObservation(question.replace("?", ""))
	    .withQuestion(question)
	    .withLocations(world.getLocations())
	    .withAgent(agent)
	    .setPrompt(SmallvilleConfig.getPrompts().getAgent().getAskQuestion())
	    .build();

	return chat.sendChat(prompt, .5);
    }

	public String sendRawPrompt(String prompt, double temperature) {
		return chat.sendChat(new PromptRequest.User(prompt), temperature);
	}

    @Override
    public List<Plan> getPlans(Agent agent) {
	PromptRequest prompt = new PromptBuilder()
	    .withLocations(world.getLocations())
	    .withObservation(agent.getMemoryStream().getLastObservation().getDescription())
	    .withAgent(agent)
	    .withWorld(world)
	    .setPrompt(SmallvilleConfig.getPrompts().getPlans().getLongTerm())
	    .build();

	String response = chat.sendChat(prompt, .6);
	return List.of(new Plan(response.replace("\n", " "), LocalDateTime.now()));
    }

    @Override
    public List<Plan> getShortTermPlans(Agent agent) {
	PromptRequest prompt = new PromptBuilder()
	    .withLocations(world.getLocations())
	    .withObservation(agent.getMemoryStream().getLastObservation().getDescription())
	    .withAgent(agent)
	    .withWorld(world)
	    .setPrompt(SmallvilleConfig.getPrompts().getPlans().getShortTerm())
	    .build();

	String response = chat.sendChat(prompt, .7);

	return parsePlans(response);
    }

    @Override
    public CurrentActivity getCurrentActivity(Agent agent) {
	PromptRequest prompt = new PromptBuilder()
	    .withAgent(agent)
	    .withWorld(world)
	    .withLocations(world.getLocations())
	    .setPrompt(SmallvilleConfig.getPrompts().getPlans().getCurrent())
	    .build();

	String response = chat.sendChat(prompt, .5);

	LocalNLP nlp = new LocalNLP();
	CurrentActivity activity = Util.parseAsClass(response, CurrentActivity.class);
	LOG.info(activity.getActivity() + activity.getLocation());
	activity.setLastActivity(nlp.convertToPastTense(agent.getCurrentActivity()));

	return activity;
    }

    @Override
    public Conversation getConversationIfExists(Agent agent, Agent other, String topic) {
	PromptRequest prompt = new PromptBuilder()
	    .withAgent(agent)
	    .withOther(other)
	    .withObservation(topic)
	    .setPrompt(SmallvilleConfig.getPrompts().getReactions().getConversation())
	    .build();

	String response = chat.sendChat(prompt, .7);
	String[] lines = response.split("\\r?\\n");

	List<Dialog> dialogs = new ArrayList<>();
	for (String line : lines) {
	    String[] parts = line.split(":\\s+", 2);
	    if (parts.length == 2) { // ignores all lines before the conversation
		dialogs.add(new Dialog(parts[0], parts[1]));
	    }
	}

	Conversation conversation = new Conversation(agent.getFullName(), other.getFullName(), dialogs);
	return conversation;
    }

    @Override
    public List<Plan> parsePlans(String input) {
	List<Plan> plans = new ArrayList<>();

	String[] lines = input.split("\n");

	for (String line : lines) {
	    LocalDateTime start = null;

	    try {
		start = parseTime(input, line);
	    } catch (Exception e) {
		LOG.error("Could not parse time");
		continue;
	    }

	    if (start == null) {
		continue;
	    }

	    Plan plan = new Plan(line, start);
	    plans.add(plan);
	}

	return plans;
    }

    private LocalDateTime parseTime(String input, String line) throws DateTimeParseException {
	String[] splitPlan = line.split("\\d+", 2); // split after first number

	if (line.isBlank()) {
	    return null;
	}

	if (splitPlan.length == 1) {
	    LOG.warn("Temporal memory possibly missing a time. " + line);
	    return null;
	}

	int index = input.indexOf(splitPlan[1]) - 2;

	if (index == -1) {
	    index++;
	}

	String time = input.substring(index, index + 8).trim().toUpperCase();
	DateTimeFormatter formatter = DateTimeFormatter.ofPattern("h:mm a");

	return LocalDateTime.of(LocalDate.now(), LocalTime.parse(time, formatter));
    }

    @Override
    public ObjectChangeResponse[] getObjectsChangedBy(Agent agent) {
	if (agent.getCurrentActivity().equals(agent.getLastActivity())) {
	    return new ObjectChangeResponse[0];
	}

	PromptRequest tensesPrompt = new PromptBuilder()
	    .withAgent(agent)
	    .withWorld(world)
	    .setPrompt(SmallvilleConfig.getPrompts().getMisc().getCombineSentences())
	    .build(); // might be able to use LocalNLP for this

	String tenses = chat.sendChat(tensesPrompt, .1);

	PromptRequest changedPrompt = new PromptBuilder()
	    .withAgent(agent)
	    .withTense(tenses)
	    .withWorld(world)
	    .withLocations(world.getLocations())
	    .setPrompt(SmallvilleConfig.getPrompts().getWorld().getObjectStates())
	    .build();

	String response = chat.sendChat(changedPrompt, .3);

	String[] lines = response.split("\n");
	ObjectChangeResponse[] objects = new ObjectChangeResponse[lines.length];

	for (int i = 0; i < lines.length; i++) {
	    String line = lines[i];

	    if (line.isBlank()) {
		continue;
	    }

	    String[] parts = line.split(":");

	    if (parts.length < 2) {
		continue;
	    }

	    String item = parts[0].trim();
	    String value = parts[1].trim();

	    LOG.debug("Trying to change " + item + " to " + value);

	    if (item != null && value != null && !value.equalsIgnoreCase("Unchanged")) {
		objects[i] = new ObjectChangeResponse(item, value);
	    }
	}

	if (objects.length == 0) {
	    LOG.warn("No objects were updated");
	}

	return objects;
    }

    @Override
    public Reflection createReflectionFor(Agent agent) {
	Reflection reflection = new Reflection("");
	PromptRequest prompt = new PromptBuilder()
	    .withAgent(agent)
	    .setPrompt(SmallvilleConfig.getPrompts().getAgent().getReflectionQuestion())
	    .build();

	String query = chat.sendChat(prompt, .1);
	String[] lines = query.split("\n");
	query = query.split("\n")[lines.length - 1].substring(2);

	LOG.debug("[Reflections] Question: " + query);

	Set<Memory> filter = new HashSet<Memory>();
	filter.addAll(agent.getMemoryStream().getRelevantMemories(query.substring(2)));
	List<Memory> memories = new ArrayList<>(filter); // Convert the set back to a list

	LOG.debug(String.join(",", memories.stream().map(m -> m.getDescription()).collect(Collectors.toList())));

	PromptRequest secondPrompt = new PromptBuilder()
	    .withAgent(agent)
	    .withStatements(memories.stream().map(m -> m.getDescription()).collect(Collectors.toList()))
	    .setPrompt(SmallvilleConfig.getPrompts().getAgent().getReflectionResult())
	    .build();

	String description = chat.sendChat(secondPrompt, .8);

	// retrieve just the insight. remove the because clause and the key
	int index = description.lastIndexOf(":");

	if (index != -1) {
	    description = description.substring(index);
	}

	description = description.replaceAll(":", "").trim();

	reflection.setDescription(description);

	return reflection;
    }

    @Override
    public Reaction shouldUpdatePlans(Agent agent, String observation) {
	PromptRequest prompt = new PromptBuilder()
	    .withObservation(observation)
	    .withAgent(agent)
	    .setPrompt(SmallvilleConfig.getPrompts().getReactions().getReaction())
	    .build();

	String response = chat.sendChat(prompt, .2);
	Reaction result = Util.parseAsClass(response, Reaction.class);
	if (result == null) {
	    result = new Reaction();
	    result.setAnswer("no");
	    result.setConversation("no");
	}
	if (result.getAnswer() == null) {
	    result.setAnswer("no");
	}
	if (result.getConversation() == null) {
	    result.setConversation("no");
	}

	LOG.debug("reacting " + result.getAnswer());
	return result;
    }

    public String createTraits(Agent agent) {
	PromptRequest prompt = new PromptBuilder()
	    .withAgent(agent)
	    .setPrompt(SmallvilleConfig.getPrompts().getAgent().getCharacteristics())
	    .build();

	return chat.sendChat(prompt, .5);
    }

    @Override
    public Dialog saySomething(Agent agent, String observation) {
	PromptRequest request = new PromptBuilder()
	    .withObservation(observation)
	    .withAgent(agent)
	    .setPrompt(SmallvilleConfig.getPrompts().getReactions().getSay())
	    .build();
	
	String result = chat.sendChat(request, .5);
	
	return new Dialog(agent.getFullName(), result);
    }

    @Override
    public void updateCurrentActivity(Agent agent) {
	CurrentActivity activity = getCurrentActivity(agent);
	agent.setCurrentActivity(activity.getActivity());
	agent.setCurrentEmoji(activity.getEmoji());

	String desiredLocation = activity.getLocation();
	if (desiredLocation != null && !desiredLocation.isBlank()) {
	    world.getLocation(desiredLocation).ifPresentOrElse(loc -> {
		agent.setTargetLocation(loc.getFullPath());
		if (agent.getLocation() != null && agent.getLocation().getFullPath().equals(loc.getFullPath())) {
		    agent.setTargetLocation(null);
		}
	    }, () -> LOG.debug("Location not found for activity: {}", desiredLocation));
	}
    }

    @Override
    public List<Commitment> getCommitments(Agent agent) {
	String promptTemplate = SmallvilleConfig.getPrompts().getPlans().getDailyCommitments();
	if (promptTemplate == null || promptTemplate.isBlank()) {
	    LOG.warn("[Commitments] dailyCommitments prompt not configured; skipping.");
	    return buildFallbackCommitments(agent);
	}

	PromptRequest prompt = new PromptBuilder()
	    .withLocations(world.getLocations())
	    .withObservation(agent.getMemoryStream().getLastObservation().getDescription())
	    .withAgent(agent)
	    .withWorld(world)
	    .setPrompt(promptTemplate)
	    .build();

	String response = chat.sendChat(prompt, 0.3);
	LOG.info("[Commitments] Raw response for {}: {}",
	    agent.getFullName(),
	    response == null ? "<null>" : response.replaceAll("\\s+", " ").trim());
	List<Commitment> commitments = parseCommitmentsFromJson(response, agent);
	if (commitments.isEmpty()) {
	    LOG.warn("[Commitments] Parsed zero commitments for {}. Using deterministic fallback.", agent.getFullName());
	    return buildFallbackCommitments(agent);
	}
	return commitments;
    }

    private List<Commitment> parseCommitmentsFromJson(String response, Agent agent) {
	List<Commitment> result = new ArrayList<>();
	ObjectMapper mapper = new ObjectMapper();

	String cleaned = extractCommitmentsJson(response);
	if (cleaned == null || cleaned.isBlank()) {
	    LOG.warn("[Commitments] No valid JSON object found in response for {}. Response: {}",
		agent.getFullName(),
		response == null ? "<null>" : response.replaceAll("\\s+", " ").trim());
	    return result;
	}

	try {
	    JsonNode root = mapper.readTree(cleaned);
	    JsonNode commitmentsNode = root.get("commitments");
	    if (commitmentsNode == null || !commitmentsNode.isArray()) {
		LOG.warn("[Commitments] No 'commitments' array in JSON for {}", agent.getFullName());
		return result;
	    }

	    LocalDate today = SimulationTime.now().toLocalDate();
	    for (JsonNode c : commitmentsNode) {
		String startStr = c.path("start").asText("").trim();
		String endStr = c.path("end").asText("").trim();
		String location = c.path("location").asText("").trim();
		String goal = c.path("goal").asText("").trim();
		String priorityStr = c.path("priority").asText("medium").toLowerCase().trim();

		if (startStr.isBlank() || endStr.isBlank() || location.isBlank() || goal.isBlank()) {
		    LOG.warn("[Commitments] Skipping incomplete commitment entry for {}", agent.getFullName());
		    continue;
		}

		String resolvedLocation = resolveLocationName(location);
		if (resolvedLocation == null) {
		    resolvedLocation = agent.getLocation() != null ? agent.getLocation().getFullPath() : null;
		    LOG.warn("[Commitments] Cannot resolve location '{}' for {}. Falling back to current location '{}'.",
			location,
			agent.getFullName(),
			resolvedLocation);
		    if (resolvedLocation == null) {
			continue;
		    }
		}

		LocalDateTime startTime = parseCommitmentTime(startStr, today);
		LocalDateTime endTime = parseCommitmentTime(endStr, today);
		if (startTime == null || endTime == null) {
		    LOG.warn("[Commitments] Invalid time values [{}-{}] for {}. Skipping.", startStr, endStr, agent.getFullName());
		    continue;
		}

		if (!endTime.isAfter(startTime)) {
		    // Allow overnight windows like 23:00 -> 01:00
		    endTime = endTime.plusDays(1);
		}

		if (!endTime.isAfter(startTime)) {
		    LOG.warn("[Commitments] Invalid time range [{}-{}] for {}. Skipping.", startStr, endStr, agent.getFullName());
		    continue;
		}

		int priority = parsePriority(priorityStr);
		result.add(new Commitment(goal, resolvedLocation, startTime, endTime, priority));
	    }
	} catch (Exception e) {
	    LOG.error("[Commitments] JSON parse failed for {}. Payload: {} Error: {}",
		agent.getFullName(),
		cleaned,
		e.getMessage());
	}

	LOG.info("[Commitments] Parsed {} commitments for {}", result.size(), agent.getFullName());
	return result;
    }

    private String extractCommitmentsJson(String response) {
	if (response == null) {
	    return null;
	}

	String cleaned = response
	    .replaceAll("(?i)```json", "")
	    .replaceAll("```", "")
	    .trim();

	if (cleaned.startsWith("\"commitments\"") || cleaned.startsWith("'commitments'")) {
	    cleaned = "{" + cleaned + "}";
	}

	if (cleaned.startsWith("commitments")) {
	    cleaned = "{\"" + cleaned.replaceFirst("^commitments", "commitments\"") + "}";
	}

	int keyIndex = cleaned.indexOf("\"commitments\"");
	if (keyIndex == -1) {
	    keyIndex = cleaned.toLowerCase().indexOf("commitments");
	}

	if (keyIndex >= 0) {
	    int start = cleaned.lastIndexOf('{', keyIndex);
	    if (start >= 0) {
		int end = findMatchingBrace(cleaned, start);
		if (end > start) {
		    return cleaned.substring(start, end + 1);
		}
	    }
	}

	int start = cleaned.indexOf('{');
	if (start < 0) {
	    return null;
	}
	int end = findMatchingBrace(cleaned, start);
	if (end <= start) {
	    return null;
	}
	return cleaned.substring(start, end + 1);
    }

    private int findMatchingBrace(String text, int startIndex) {
	int depth = 0;
	boolean inString = false;
	for (int i = startIndex; i < text.length(); i++) {
	    char ch = text.charAt(i);
	    if (ch == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
		inString = !inString;
	    }
	    if (inString) {
		continue;
	    }
	    if (ch == '{') {
		depth++;
	    } else if (ch == '}') {
		depth--;
		if (depth == 0) {
		    return i;
		}
	    }
	}
	return -1;
    }

    private List<Commitment> buildFallbackCommitments(Agent agent) {
	List<Commitment> fallback = new ArrayList<>();
	LocalDateTime now = SimulationTime.now();
	String currentLocation = agent.getLocation() != null ? agent.getLocation().getFullPath() : null;
	if (currentLocation == null && !world.getLocations().isEmpty()) {
	    currentLocation = world.getLocations().get(0).getFullPath();
	}
	if (currentLocation == null) {
	    return fallback;
	}

	// Keep the fallback simple and deterministic so agents always stay scheduled.
	fallback.add(new Commitment(
	    "continue routine",
	    currentLocation,
	    now,
	    now.plusMinutes(60),
	    10));

	String secondLocation = currentLocation;
	if (!world.getLocations().isEmpty()) {
	    int basePick = Math.abs(agent.getFullName().hashCode()) % world.getLocations().size();
	    for (int offset = 0; offset < world.getLocations().size(); offset++) {
		String candidate = world.getLocations().get((basePick + offset) % world.getLocations().size()).getFullPath();
		if (!candidate.equalsIgnoreCase(currentLocation) || world.getLocations().size() == 1) {
		    secondLocation = candidate;
		    break;
		}
	    }
	}
	fallback.add(new Commitment(
	    "travel and observe surroundings",
	    secondLocation,
	    now.plusMinutes(60),
	    now.plusMinutes(120),
	    5));

	LOG.info("[Commitments] Created {} fallback commitments for {}", fallback.size(), agent.getFullName());
	return fallback;
    }

    private LocalDateTime parseCommitmentTime(String timeStr, LocalDate date) {
	// Try 24-hour first (HH:mm or H:mm), then 12-hour with AM/PM
	String[] patterns24 = {"H:mm", "HH:mm"};
	for (String pattern : patterns24) {
	    try {
		return LocalDateTime.of(date, LocalTime.parse(timeStr, DateTimeFormatter.ofPattern(pattern)));
	    } catch (DateTimeParseException ignored) {
	    }
	}
	try {
	    return LocalDateTime.of(date, LocalTime.parse(timeStr.toUpperCase(), DateTimeFormatter.ofPattern("h:mm a")));
	} catch (DateTimeParseException e) {
	    LOG.warn("[Commitments] Cannot parse time string: '{}'", timeStr);
	    return null;
	}
    }

    private String resolveLocationName(String rawName) {
	// Exact match first (case-insensitive)
	for (var loc : world.getLocations()) {
	    if (loc.getFullPath().equalsIgnoreCase(rawName)) {
		return loc.getFullPath();
	    }
	}
	// Partial match: LLM name contained in known path or vice versa
	String lowered = rawName.toLowerCase();
	for (var loc : world.getLocations()) {
	    String locLower = loc.getFullPath().toLowerCase();
	    if (locLower.contains(lowered) || lowered.contains(locLower)) {
		return loc.getFullPath();
	    }
	}
	return null;
    }

    private int parsePriority(String priorityStr) {
	switch (priorityStr) {
	    case "high": return 10;
	    case "low": return 1;
	    default: return 5;
	}
    }
}
