package io.github.nickm980.smallville.api.v1;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.nickm980.smallville.World;
import io.github.nickm980.smallville.api.v1.dto.*;
import io.github.nickm980.smallville.entities.*;
import io.github.nickm980.smallville.exceptions.AgentNotFoundException;
import io.github.nickm980.smallville.exceptions.LocationNotFoundException;
import io.github.nickm980.smallville.exceptions.SmallvilleException;
import io.github.nickm980.smallville.llm.LLM;
import io.github.nickm980.smallville.memory.Characteristic;
import io.github.nickm980.smallville.memory.Memory;
import io.github.nickm980.smallville.memory.MemoryStream;
import io.github.nickm980.smallville.memory.Observation;
import io.github.nickm980.smallville.memory.Plan;
import io.github.nickm980.smallville.update.UpdateService;

public class SimulationService {

    private Logger LOG = LoggerFactory.getLogger(SimulationService.class);
	private static final double TILE_SIZE = 32.0;
	private static final String DEFAULT_TRACKED_AGENT_NAME = "Alex";
	private static final long TRACK_HEARTBEAT_MINUTES = 5;
	private static final double TRACE_POSITION_EPSILON = 0.1;
	private static final String AGENTIC_OBJECTIVE_SOCIAL_CONTACT = "social_contact";
	private static final double AGENTIC_SOCIAL_AWARENESS_RADIUS = 180.0;
	private static final double AGENTIC_INITIATE_RADIUS = 110.0;
	private static final double AGENTIC_DISENGAGE_RADIUS = 135.0;
	private static final int AGENTIC_MAX_DEFERRED_TURNS = 4;
	private static final long AGENTIC_SOCIAL_COOLDOWN_MINUTES = 20;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile String trackedAgentName = DEFAULT_TRACKED_AGENT_NAME;

    private final ModelMapper mapper;
    private final UpdateService prompts;
    private final World world;
    private int progress;
    private final Queue<PlayerActionRequest> actionQueue = new ConcurrentLinkedQueue<>();
	private static final int MAX_ACTION_HISTORY = 100;
	private static final int MAX_REACTIVE_EVENTS = 30;
	private static final int MAX_COMMITTED_ACTIONS = 20;
	private List<LocationStateResponse> cachedLocations = null;
	private List<Location> cachedLocationEntities = null;
	private final Map<String, Deque<PlayerActionRequest>> actionHistoryByPlayer = new ConcurrentHashMap<>();
	private final Map<String, Map<String, Object>> objectTypeDefinitions = new ConcurrentHashMap<>();
	private final Map<String, WorldObjectInstance> objectInstances = new ConcurrentHashMap<>();
	private final Map<String, RuntimeAgentState> runtimeStateByAgent = new ConcurrentHashMap<>();
	private final Map<String, Deque<ReactiveEvent>> reactiveEventsByAgent = new ConcurrentHashMap<>();
	private final Map<String, Deque<CommittedAction>> committedActionsByAgent = new ConcurrentHashMap<>();
	private final Map<String, AgenticRuntimeState> agenticStateByAgent = new ConcurrentHashMap<>();

	private static class RuntimeAgentState {
		private LocalDate lastRoutineDate;
		private LocalDate lastReflectionDate;
		private LocalDateTime lastLlmCallAt;
		private LocalDateTime lastOrchestratedAt;
		private boolean lastAware;
		private String lastTraceActivity;
		private String lastTraceLocation;
		private String lastTraceTarget;
		private Double lastTraceX;
		private Double lastTraceY;
		private LocalDateTime lastTraceLoggedAt;
	}

	private static class ReactiveEvent {
		private String description;
		private int severity;
		private LocalDateTime createdAt;
		private boolean playerInvolved;
	}

	private static class CommittedAction {
		private String action;
		private String reason;
		private String location;
		private double x;
		private double y;
		private LocalDateTime createdAt;
	}

	private static class InstinctDecision {
		private String action;
		private String activity;
		private String targetLocation;
		private double stressDelta;
		private String reason;
	}

	private enum AgenticPhase {
		IDLE,
		APPROACH_PLAYER,
		AWAITING_PLAYER_REPLY,
		COOLDOWN
	}

	private static class KnowledgeEntry {
		private List<String> values = new ArrayList<>();
		private double confidence;
		private LocalDateTime updatedAt;
		private String source;

		private boolean isFresh(LocalDateTime now, long ttlMinutes) {
			if (updatedAt == null || now == null) {
				return false;
			}
			return Duration.between(updatedAt, now).toMinutes() <= ttlMinutes;
		}
	}

	private static class AgenticRuntimeState {
		private AgenticPhase phase = AgenticPhase.IDLE;
		private String currentObjective;
		private String targetObjectId;
		private LocalDateTime phaseUpdatedAt;
		private LocalDateTime cooldownUntil;
		private String pendingPlayerId;
		private boolean chatWindowClosedObserved;
		private boolean pinnedLastTurn;
		private int deferredTurns;
		private int recentIgnoreCount;
		private double lastInitiativeScore;
		private String lastOutcome;
		private LocalDateTime lastInitiatedAt;
		private LocalDateTime lastRepliedAt;
		private String lastError;
	}

	private static class WorldObjectInstance {
		private String id;
		private String type;
		private String name;
		private double x;
		private double y;
		private String location;
		private Map<String, Object> properties = new HashMap<>();

		private Map<String, Object> toMap() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("id", id);
			result.put("type", type);
			result.put("name", name);
			result.put("x", x);
			result.put("y", y);
			result.put("location", location);
			result.put("properties", properties);
			return result;
		}
	}

    public SimulationService(LLM llm, World world) {
	this.world = world;
	this.mapper = new ModelMapper();
	this.prompts = new UpdateService(llm, world);
	this.progress = 0;
	seedDefaultObjectTypes();
    }

	private void seedDefaultObjectTypes() {
	Map<String, Object> wall = new HashMap<>();
	wall.put("walkable", false);
	wall.put("interactive", false);
	objectTypeDefinitions.put("wall", wall);

	Map<String, Object> table = new HashMap<>();
	table.put("walkable", false);
	table.put("interactive", true);
	table.put("surface", true);
	objectTypeDefinitions.put("table", table);

	Map<String, Object> chair = new HashMap<>();
	chair.put("walkable", false);
	chair.put("interactive", true);
	chair.put("sit-able", true);
	objectTypeDefinitions.put("chair", chair);
	}

    public void createMemory(CreateMemoryRequest request) {
	Agent agent = world.getAgent(request.getName()).orElseThrow();
	Observation observation = new Observation(request.getDescription());
	observation.setReactable(request.isReactable());
	agent.getMemoryStream().add(observation);

	if (observation.isReactable()) {
	    SimulationTime.update();
	    prompts.react(agent, observation.getDescription());
	}
    }

    public AgentStateResponse getAgentState(String name) {
	Agent agent = world.getAgent(name).orElseThrow(() -> new AgentNotFoundException(name));
	return buildAgentStateResponse(agent);
    }

    public List<AgentStateResponse> getAgents() {
	List<Agent> agents = world.getAgents();

	return agents.stream().map(this::buildAgentStateResponse).collect(Collectors.toList());
    }

	private AgentStateResponse buildAgentStateResponse(Agent agent) {
		AgentStateResponse response = mapper.fromAgent(agent);
		response.setObject(getActiveTargetObjectId(agent));
		return response;
	}

	private String getActiveTargetObjectId(Agent agent) {
		if (agent == null || agent.getFullName() == null) {
			return null;
		}
		AgenticRuntimeState state = agenticStateByAgent.get(agent.getFullName());
		if (state == null || state.targetObjectId == null || state.targetObjectId.isBlank()) {
			return null;
		}
		return state.targetObjectId;
	}

    public List<LocationStateResponse> getAllLocations() {
	if (cachedLocations != null) {
	    return new ArrayList<>(cachedLocations);
	}

	List<LocationStateResponse> result = new ArrayList<LocationStateResponse>();
	for (Location location : world.getLocations()) {
	    result.add(mapper.fromLocation(location));
	}
	
	cachedLocations = result;
	LOG.info("[SERVER] Cached {} locations", result.size());
	return result;
    }

	private List<Location> getLocationsCached() {
		if (cachedLocationEntities != null) {
			return cachedLocationEntities;
		}
		cachedLocationEntities = new ArrayList<>(world.getLocations());
		return cachedLocationEntities;
	}

    public void createAgent(CreateAgentRequest request) {
	List<Characteristic> characteristics = request
	    .getMemories()
	    .stream()
	    .map(c -> new Characteristic(c))
	    .collect(Collectors.toList());
	// Location : Object
	Location location = world.getLocation(request.getLocation()).orElse(null);

	if (location == null) {
	    LOG.error("Could not find location " + request.getLocation());
	    throw new LocationNotFoundException(request.getLocation());
	}

	Agent agent = new Agent(request.getName(), characteristics, request.getActivity(), location);

	if (world.create(agent)) {
	    if (shouldAutoTrackNewAgent()) {
		setTrackedAgentName(agent.getFullName());
	    }
	    String traits = prompts.createTraitsWithCharacteristics(agent);
	    agent.setTraits(traits);
	    RuntimeAgentState state = runtimeStateByAgent.computeIfAbsent(agent.getFullName(), k -> new RuntimeAgentState());
	    state.lastRoutineDate = SimulationTime.now().toLocalDate();
	    state.lastReflectionDate = SimulationTime.now().toLocalDate();
	    state.lastLlmCallAt = SimulationTime.now();
	}
    }

	public Map<String, Object> generateAndSpawnAgents(GenerateAgentRequest request) {
		GenerateAgentRequest safeRequest = request == null ? new GenerateAgentRequest() : request;
		int count = safeRequest.getCount() == null ? 1 : Math.max(1, Math.min(3, safeRequest.getCount()));
		boolean replaceExistingAgents = safeRequest.getReplaceExistingAgents() == null || safeRequest.getReplaceExistingAgents();
		boolean trackFirstAgent = safeRequest.getTrackFirstAgent() == null || safeRequest.getTrackFirstAgent();
		boolean enableRepairPass = safeRequest.getEnableRepairPass() == null || safeRequest.getEnableRepairPass();

		List<Location> locations = getLocationsCached();
		if (locations.isEmpty()) {
			throw new SmallvilleException("Create locations before generating agents");
		}

		int removedAgents = 0;
		if (replaceExistingAgents) {
			removedAgents = clearNonPlayerAgents();
		}

		Set<String> existingNames = world.getAgents().stream()
			.map(Agent::getFullName)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		List<Map<String, Object>> createdAgents = new ArrayList<>();

		for (int index = 0; index < count; index++) {
			String rawGeneration = prompts.sendRawPrompt(
				buildAgentGenerationPrompt(safeRequest, index, locations, existingNames),
				0.85);
			GeneratedAgentBlueprint candidate = parseGeneratedAgentBlueprint(rawGeneration);
			List<String> validationIssues = new ArrayList<>();
			GeneratedAgentBlueprint normalized = validateAndNormalizeGeneratedAgent(candidate, safeRequest, existingNames, locations, validationIssues, index);
			boolean repaired = false;

			if (!validationIssues.isEmpty() && enableRepairPass) {
				String rawRepair = prompts.sendRawPrompt(
					buildAgentRepairPrompt(rawGeneration, validationIssues, safeRequest, locations, existingNames),
					0.25);
				GeneratedAgentBlueprint repairedCandidate = parseGeneratedAgentBlueprint(rawRepair);
				List<String> repairIssues = new ArrayList<>();
				normalized = validateAndNormalizeGeneratedAgent(repairedCandidate, safeRequest, existingNames, locations, repairIssues, index);
				validationIssues = repairIssues;
				repaired = true;
			}

			CreateAgentRequest createRequest = toCreateAgentRequest(normalized);
			createAgent(createRequest);
			existingNames.add(normalized.getName());

			Map<String, Object> created = new LinkedHashMap<>();
			created.put("agent", normalized.toMap());
			created.put("repaired", repaired);
			created.put("warnings", validationIssues);
			createdAgents.add(created);
		}

		if (trackFirstAgent && !createdAgents.isEmpty()) {
			@SuppressWarnings("unchecked")
			Map<String, Object> firstAgent = (Map<String, Object>) createdAgents.get(0).get("agent");
			setTrackedAgentName((String) firstAgent.get("name"));
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("removedAgents", removedAgents);
		result.put("generatedCount", createdAgents.size());
		result.put("trackedAgent", trackedAgentName);
		result.put("agents", createdAgents);
		return result;
	}

    public void createLocation(CreateLocationRequest request) {
	// Check if location already exists
	if (world.getLocation(request.getName()).isPresent()) {
	    LOG.warn("[SERVER] Location already exists, skipping: {}", request.getName());
	    return;  // Idempotent: don't fail, just skip
	}

	Location location = new Location(request.getName());
	location.setType(request.getType());
	location.setMinX(request.getMinX());
	location.setMaxX(request.getMaxX());
	location.setMinY(request.getMinY());
	location.setMaxY(request.getMaxY());
	
	world.create(location);
	cachedLocations = null;
	cachedLocationEntities = null;
	LOG.info("[SERVER] Created location: {} with type: {} bounds: ({},{}) to ({},{})", 
	    request.getName(), request.getType(), request.getMinX(), request.getMinY(), 
	    request.getMaxX(), request.getMaxY());
	LOG.info("[SERVER] Total locations after creation: {}", world.getLocations().size());
    }

    public void createPlayer(CreatePlayerRequest request) {
	// Check if player already exists
	if (world.getAgent(request.getName()).isPresent()) {
	    LOG.warn("[SERVER] Player already exists, skipping: {}", request.getName());
	    return;  // Idempotent: don't fail, just skip
	}

	Location location = world.getLocation(request.getLocation())
	    .orElseThrow(() -> new LocationNotFoundException(request.getLocation()));

	List<Characteristic> characteristics = new ArrayList<>();
	if (request.getMemories() != null) {
	    for (String memory : request.getMemories()) {
	        characteristics.add(new Characteristic(memory));
	    }
	}

	String activity = request.getActivity() != null ? request.getActivity() : "idle";
	Player player = new Player(request.getName(), characteristics, activity, location);
	
	world.create(player);
	RuntimeAgentState state = runtimeStateByAgent.computeIfAbsent(player.getFullName(), k -> new RuntimeAgentState());
	state.lastRoutineDate = SimulationTime.now().toLocalDate();
	state.lastReflectionDate = SimulationTime.now().toLocalDate();
	state.lastLlmCallAt = SimulationTime.now();
	LOG.info("[SERVER] Created player: {} at location: {} activity: {}", 
	    request.getName(), request.getLocation(), activity);
    }

    public PlayerStateResponse getPlayerState(String name) {
	Agent agent = world.getAgent(name).orElseThrow(() -> new AgentNotFoundException(name));
	
	if (!(agent instanceof Player)) {
	    throw new AgentNotFoundException("Player named " + name + " not found");
	}
	
	Player player = (Player) agent;
	PlayerStateResponse response = new PlayerStateResponse(
	    player.getFullName(),
	    player.getLocation().getFullPath(),
	    player.getCurrentActivity(),
	    player.getStress()
	);
	response.setInventory(player.getInventory());
	response.setX(player.getX());
	response.setY(player.getY());
	return response;
    }

    public List<MemoryResponse> getMemoriesOfAgent(String agentName) {
	List<MemoryResponse> result = world
	    .getAgent(agentName)
	    .orElseThrow(() -> new AgentNotFoundException(agentName))
	    .getMemoryStream()
	    .getMemories()
	    .stream()
	    .map(mapper::fromMemory)
	    .sorted(Comparator.comparing(MemoryResponse::getTime, Comparator.nullsLast(Comparator.naturalOrder())))
	    .collect(Collectors.toList());

	return result;
    }

    public String askQuestion(String name, String question) {
	Agent agent = world.getAgent(name).orElseThrow(() -> new AgentNotFoundException(name));
	String result = prompts.ask(agent, question);

	return result;
    }

    public void updateState() throws SmallvilleException {
//	AnalyticsListener.refresh();
	if (world.getAgents().size() == 0) {
	    throw new SmallvilleException("Must create an agent before changing the state");
	}

	SimulationTime.update();
	orchestrateRuntime(new RuntimeOrchestrationRequest(), null);
    }

    public List<ConversationResponse> getConversations() {
	List<ConversationResponse> result = new ArrayList<ConversationResponse>();
	List<Conversation> conversations = world
	    .getConversationsAfter(SimulationTime.now().minus(SimulationTime.getStepDuration()));

	for (Conversation conversation : conversations) {
	    result.addAll(mapper.fromConversation(conversation));
	}

	return result;
    }

    public void setTimestep(SetTimestepRequest request) {
	long durationValue = Long.parseLong(request.getNumOfMinutes());
	Duration duration = Duration.ofMinutes(durationValue);
	SimulationTime.setStep(duration);
    }

    public int getProgress() {
	return progress;
    }

    public void setState(String location, String state) {
	world.setState(location, state);
    }

	/**
	 * Enqueue a player action for later processing in turns.
	 * Actions are no longer executed immediately; use processNextAction() to execute.
	 */
	public void enqueuePlayerAction(PlayerActionRequest request) {
		String playerId = request.getPlayerId();
		if (playerId == null || playerId.isEmpty()) {
			throw new SmallvilleException("playerId cannot be blank");
		}

		// Basic validation
		if (request.getActionType() == null || request.getActionType().isEmpty()) {
			request.setActionType("interact");
		}

		recordActionHistory(request);
		actionQueue.add(request);
	}

	private void recordActionHistory(PlayerActionRequest request) {
		if (request.getPlayerId() == null || request.getPlayerId().isEmpty()) {
			return;
		}
		Deque<PlayerActionRequest> history = actionHistoryByPlayer.computeIfAbsent(request.getPlayerId(), k -> new ArrayDeque<>());
		history.addFirst(copyActionRequest(request));
		while (history.size() > MAX_ACTION_HISTORY) {
			history.removeLast();
		}
	}

	private PlayerActionRequest copyActionRequest(PlayerActionRequest source) {
		PlayerActionRequest copy = new PlayerActionRequest();
		copy.setPlayerId(source.getPlayerId());
		copy.setActionType(source.getActionType());
		copy.setTargetLocation(source.getTargetLocation());
		copy.setTargetAgent(source.getTargetAgent());
		copy.setActionDescription(source.getActionDescription());
		copy.setIntensity(source.getIntensity());
		copy.setItem(source.getItem());
		copy.setRequestedDurationSeconds(source.getRequestedDurationSeconds());
		copy.setTimestamp(source.getTimestamp());
		copy.setFlair(source.getFlair());
		copy.setSpeakText(source.getSpeakText());
		copy.setPlayerX(source.getPlayerX());
		copy.setPlayerY(source.getPlayerY());
		return copy;
	}

	/**
	 * Process the next action in the queue, execute it, advance time, and update affected agents.
	 * Returns the result of the action execution.
	 */
	public PlayerActionResponse processNextAction() throws SmallvilleException {
		return processNextAction(new RuntimeOrchestrationRequest());
	}

	public PlayerActionResponse processNextAction(RuntimeOrchestrationRequest orchestrationRequest) throws SmallvilleException {
		PlayerActionRequest request = actionQueue.poll();
		if (request == null) {
			SimulationTime.update();
			if (orchestrationRequest == null) {
				orchestrationRequest = new RuntimeOrchestrationRequest();
			}
			Map<String, Object> runtimeSummary = orchestrateRuntime(orchestrationRequest, null);
			PlayerActionResponse noActionResponse = new PlayerActionResponse(false, "No actions in queue");
			noActionResponse.setResult("Turn advanced without queued player action | runtime=" + runtimeSummary);
			return noActionResponse;
		}

		PlayerActionResponse response;
		try {
			response = executeAction(request);
		} catch (Exception e) {
			response = new PlayerActionResponse(false, e.getMessage() == null ? "Action failed" : e.getMessage());
		}

		// Each consumed player action advances simulation time by one step.
		SimulationTime.update();
		if (orchestrationRequest == null) {
			orchestrationRequest = new RuntimeOrchestrationRequest();
		}
		if (orchestrationRequest.getPlayerX() == null) {
			orchestrationRequest.setPlayerX(request.getPlayerX());
		}
		if (orchestrationRequest.getPlayerY() == null) {
			orchestrationRequest.setPlayerY(request.getPlayerY());
		}
		if (orchestrationRequest.getAwarenessRadius() <= 0) {
			orchestrationRequest.setAwarenessRadius(180.0);
		}
		if (request.getTargetAgent() != null && !request.getTargetAgent().isBlank()) {
			String type = request.getActionType() == null ? "" : request.getActionType().toLowerCase();
			if ("interact".equals(type) || "speak".equals(type) || "talk".equals(type) || "wait".equals(type)) {
				orchestrationRequest.addPinnedAgent(request.getTargetAgent());
			}
		}
		Map<String, Object> runtimeSummary = orchestrateRuntime(orchestrationRequest, request);
		if (response.getResult() == null || response.getResult().isBlank()) {
			response.setResult(response.isSuccess() ? "Turn processed" : "Turn processed with failed player action");
		}
		response.setResult(response.getResult() + " | runtime=" + runtimeSummary);
		return response;
	}

	/**
	 * Execute a player action, apply effects, and return the response.
	 */
	private PlayerActionResponse executeAction(PlayerActionRequest request) {
		String playerId = request.getPlayerId();
		Agent player = world.getAgent(playerId).orElseThrow(() -> new AgentNotFoundException(playerId));

		double intensity = computeIntensity(request);
		long baseDuration = computeDurationSeconds(request);

		// Apply simple effects based on action type
		PlayerActionResponse res = new PlayerActionResponse(true, "Action executed");
		res.setActionId(java.util.UUID.randomUUID().toString());

		if ("wait".equalsIgnoreCase(request.getActionType())) {
			String waitActivity = request.getActionDescription() != null && !request.getActionDescription().isBlank()
				? request.getActionDescription()
				: "Waiting";
			player.setCurrentActivity(waitActivity);
			player.applyStressChange(-0.02);
			recordCommittedAction(player, "WAIT", "player requested wait");

			if (request.getTargetAgent() != null && !request.getTargetAgent().isBlank()) {
				Agent targetAgent = world.getAgent(request.getTargetAgent())
					.orElseThrow(() -> new AgentNotFoundException(request.getTargetAgent()));
				targetAgent.setCurrentActivity("Waiting");
				targetAgent.setTargetLocation(null);
				recordCommittedAction(targetAgent, "WAIT", "waiting due to player request");
				res.setTargetAgentState(mapper.fromAgent(targetAgent));
			}

			res.setPlayerState(mapper.fromAgent(player));
			res.setStressChange(-0.02);
			res.setResult("Wait action processed");
			return res;
		}

		if ("move".equalsIgnoreCase(request.getActionType())) {
			if (request.getTargetLocation() == null) {
				throw new SmallvilleException("targetLocation required for move");
			}
			double targetX = request.getPlayerX();
			double targetY = request.getPlayerY();
			Location resolvedByCoordinate = findLocationAt(targetX, targetY);
			Location destination = resolvedByCoordinate;
			if (destination == null) {
				destination = world.getLocation(request.getTargetLocation())
					.orElseThrow(() -> new LocationNotFoundException(request.getTargetLocation()));
				if (!destination.isWithinBounds(targetX, targetY)) {
					throw new SmallvilleException("Target position (" + targetX + ", " + targetY + ") is outside location bounds");
				}
			}

			Agent occupant = findOccupyingAgentAtTile(destination, targetX, targetY, player);
			if (occupant != null) {
				throw new SmallvilleException("Target tile is occupied by " + occupant.getFullName());
			}

			player.setLocation(destination);
			player.setPosition(targetX, targetY);
			player.setCurrentActivity("Moving to " + destination.getFullPath());

			res.setPlayerState(mapper.fromAgent(player));
			res.setStressChange(0);
			return res;
		}

		if ("interact".equalsIgnoreCase(request.getActionType()) || "attack".equalsIgnoreCase(request.getActionType())) {
			String target = request.getTargetAgent();
			if (target == null) {
				throw new SmallvilleException("targetAgent required for interaction");
			}

			Agent targetAgent = world.getAgent(target).orElseThrow(() -> new AgentNotFoundException(target));

			// Update player position if provided
			if (request.getPlayerX() != 0 || request.getPlayerY() != 0) {
				if (player.getLocation() != null && player.getLocation().isWithinBounds(request.getPlayerX(), request.getPlayerY())) {
					player.setPosition(request.getPlayerX(), request.getPlayerY());
				}
			}

			// Check feasibility: same location + distance
			String feasibilityError = validateInteractionFeasibility(player, targetAgent);
			if (feasibilityError != null) {
				throw new SmallvilleException(feasibilityError);
			}

			// Calculate distance-adjusted duration
			double distance = calculateDistance(player, targetAgent);
			long adjustedDuration = computeDistanceAdjustedDuration(baseDuration, distance);

			// apply stress change to target based on intensity and duration
			double stressDelta = intensity * 0.2;
			targetAgent.applyStressChange(stressDelta);
			recordStressEventIfSignificant(targetAgent,
				(request.getActionDescription() != null ? request.getActionDescription() : "player action"));
			// player also experiences stress when committing aggressive acts
			player.applyStressChange(intensity * 0.05);

			targetAgent.setCurrentActivity(request.getActionDescription() != null ? request.getActionDescription() : "Interacted");
			player.setCurrentActivity(request.getActionDescription() != null ? request.getActionDescription() : "Interacted");
			// Attack uses high severity (9) so the target always reacts via LLM.
			// Basic interact uses severity 4 so the event is recorded deterministically
			// without blocking the turn with an LLM call â€” dialogue content comes from speak actions.
			int eventSeverity = "attack".equalsIgnoreCase(request.getActionType()) ? 9 : 4;
			enqueueReactiveEvent(targetAgent.getFullName(),
			    request.getActionDescription() != null ? request.getActionDescription() : "direct interaction",
			    eventSeverity,
			    true);
			for (Agent bystander : world.getAgents()) {
			    if (bystander.getFullName().equals(player.getFullName()) || bystander.getFullName().equals(targetAgent.getFullName())) {
				continue;
			    }
			    if (bystander.getLocation() != null && targetAgent.getLocation() != null
				&& bystander.getLocation().getFullPath().equals(targetAgent.getLocation().getFullPath())) {
				// Severity 3 = deterministic only, no LLM stall for bystanders
				enqueueReactiveEvent(bystander.getFullName(), "witnessed: " + (request.getActionDescription() == null ? "interaction" : request.getActionDescription()), 3, true);
			    }
			}

			res.setPlayerState(mapper.fromAgent(player));
			res.setTargetAgentState(mapper.fromAgent(targetAgent));
			res.setStressChange(stressDelta);
			res.setResult("Distance: " + String.format("%.1f", distance) + " units, Action time: " + adjustedDuration + "s");

			return res;
		}

		if ("speak".equalsIgnoreCase(request.getActionType()) || (request.getSpeakText() != null && !request.getSpeakText().isEmpty())) {
			// speaking: minor stress impacts, record as conversation
			player.setCurrentActivity("Speaking");
			player.applyStressChange(0.01);
			Agent dialogueTarget = null;
			double closestDistance = Double.MAX_VALUE;

			if (request.getTargetAgent() != null && !request.getTargetAgent().isBlank()) {
				dialogueTarget = world.getAgent(request.getTargetAgent()).orElse(null);
			}

			for (Agent listener : world.getAgents()) {
			    if (listener.getFullName().equals(player.getFullName())) {
				continue;
			    }
			    if (listener.getLocation() != null && player.getLocation() != null
				&& listener.getLocation().getFullPath().equals(player.getLocation().getFullPath())
				&& listener.distanceTo(player) <= 110.0) {
				enqueueReactiveEvent(listener.getFullName(), "overheard: " + request.getSpeakText(), 4, true);
				double distance = listener.distanceTo(player);
				if (dialogueTarget == null && distance < closestDistance) {
				    dialogueTarget = listener;
				    closestDistance = distance;
				}
			    }
			}

			if (dialogueTarget != null && request.getSpeakText() != null && !request.getSpeakText().isBlank()) {
				dialogueTarget.setCurrentActivity("Talking with " + player.getFullName());
				recordCommittedAction(dialogueTarget, "TALK", "responding to player dialogue");
				res.setTargetAgentState(mapper.fromAgent(dialogueTarget));
				res.setAgentReplySpeaker(dialogueTarget.getFullName());
				try {
				    String contextAwarePrompt = composeContextAwareQuestion(dialogueTarget, request.getSpeakText());
				    String reply = prompts.ask(dialogueTarget, contextAwarePrompt);
				    if (reply != null && !reply.isBlank()) {
				        res.setAgentReplyText(reply);
				    } else {
				        res.setAgentReplyText("I need a moment to think about that.");
				    }
				    res.setResult("Dialogue complete");
				} catch (Exception e) {
				    LOG.warn("Dialogue ask failed for {}: {}", dialogueTarget.getFullName(), e.getMessage());
				    res.setAgentReplyText("I need a moment to think about that.");
				    res.setResult("Dialogue fallback response");
				}
			} else {
				res.setResult("Spoke: " + (request.getSpeakText() == null ? "" : request.getSpeakText()));
			}
			res.setPlayerState(mapper.fromAgent(player));
			return res;
		}

		// default fallback
		player.setCurrentActivity(request.getActionDescription() != null ? request.getActionDescription() : "Acting");
		res.setPlayerState(mapper.fromAgent(player));
		return res;
	}

	private double computeIntensity(PlayerActionRequest request) {
		// base mapping
		double base = 0.1;
		String type = request.getActionType();
		if (type == null) {
			type = "interact";
		}
		switch (type.toLowerCase()) {
			case "move":
				base = 0.05;
				break;
			case "wait":
				base = 0.0;
				break;
			case "talk":
			case "speak":
				base = 0.05;
				break;
			case "attack":
				base = 0.6;
				break;
			case "defend":
				base = 0.4;
				break;
			case "interact":
			default:
				base = 0.1;
				break;
		}

		// item modifiers
		if (request.getItem() != null) {
			String item = request.getItem().toLowerCase();
			if (item.contains("knife")) base += 0.15;
			if (item.contains("gun")) base += 0.4;
		}

		// player-provided intensity has priority
		double playerProvided = request.getIntensity();
		double result = playerProvided > 0 ? playerProvided : base;

		// flair modifier: small bump if naughty words or explicit violence detected
		if (request.getFlair() != null && !request.getFlair().isEmpty()) {
			String f = request.getFlair().toLowerCase();
			if (f.contains("stab") || f.contains("kill") || f.contains("shoot") || f.contains("slit")) {
				result += 0.15;
			}
		}

		if (result < 0) result = 0;
		if (result > 1) result = 1;
		return result;
	}

	private long computeDurationSeconds(PlayerActionRequest request) {
		if (request.getRequestedDurationSeconds() > 0) return request.getRequestedDurationSeconds();
		String type = request.getActionType();
		if (type == null) type = "interact";
		switch (type.toLowerCase()) {
			case "move":
				return 5; // default 5s
			case "wait":
				return 2;
			case "talk":
			case "speak":
				return 2;
			case "attack":
				return 3;
			case "defend":
				return 2;
			default:
				return 2;
		}
	}

	public PlayerActionResponse executePlayerMove(String playerId, PlayerActionRequest request) {
		request.setPlayerId(playerId);
		request.setActionType("move");
		enqueuePlayerAction(request);
		return new PlayerActionResponse(true, "Move action enqueued");
	}

	public PlayerActionResponse executePlayerInteraction(String playerId, PlayerActionRequest request) {
		request.setPlayerId(playerId);
		request.setActionType("interact");
		enqueuePlayerAction(request);
		return new PlayerActionResponse(true, "Interaction action enqueued");
	}

	public PlayerActionResponse executePlayerDefense(String playerId, PlayerActionRequest request) {
		request.setPlayerId(playerId);
		request.setActionType("defend");
		enqueuePlayerAction(request);
		return new PlayerActionResponse(true, "Defense action enqueued");
	}

	public List<AgentDeltaStateResponse> getAgentDeltas() {
		List<AgentDeltaStateResponse> deltas = new ArrayList<>();
		for (Agent a : world.getAgents()) {
			AgentDeltaStateResponse d = new AgentDeltaStateResponse();
			d.setName(a.getFullName());
			if (a.getLocation() != null) d.setLocation(a.getLocation().getFullPath());
			d.setCurrentAction(a.getCurrentActivity());
			d.setEmoji(a.getEmoji());
			d.setObject(getActiveTargetObjectId(a));
			d.setTargetLocation(a.getTargetLocation());
			d.setStressLevel(a.getStressLevel());
			d.setMentalState(a.getMentalState());
			d.setX(a.getX());
			d.setY(a.getY());
			deltas.add(d);
		}
		return deltas;
	}

    public List<Map<String, Object>> getPlayerActionHistory(String playerId, int limit) {
	Deque<PlayerActionRequest> history = actionHistoryByPlayer.getOrDefault(playerId, new ArrayDeque<>());
	int safeLimit = Math.max(1, limit);

	return history
	    .stream()
	    .limit(safeLimit)
	    .map(action -> {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("playerId", action.getPlayerId());
		map.put("actionType", action.getActionType());
		map.put("targetLocation", action.getTargetLocation());
		map.put("targetAgent", action.getTargetAgent());
		map.put("description", action.getActionDescription());
		map.put("speakText", action.getSpeakText());
		map.put("x", action.getPlayerX());
		map.put("y", action.getPlayerY());
		map.put("timestamp", action.getTimestamp());
		return map;
	    })
	    .collect(Collectors.toList());
    }

    public void defineObjectType(String type, Map<String, Object> properties) {
	if (type == null || type.isBlank()) {
	    throw new SmallvilleException("Object type cannot be blank");
	}
	Map<String, Object> safeProperties = properties == null ? new HashMap<>() : new HashMap<>(properties);
	objectTypeDefinitions.put(type, safeProperties);
    }

    public Map<String, Map<String, Object>> getObjectTypes() {
	return objectTypeDefinitions;
    }

    public Map<String, Object> getObjectType(String type) {
	Map<String, Object> typeProperties = objectTypeDefinitions.get(type);
	if (typeProperties == null) {
	    throw new SmallvilleException("Unknown object type: " + type);
	}
	Map<String, Object> response = new LinkedHashMap<>();
	response.put("type", type);
	response.put("properties", typeProperties);
	return response;
    }

    public Map<String, Object> upsertObjectInstance(String objectId, ObjectInstanceUpsertRequest request) {
	if (objectId == null || objectId.isBlank()) {
	    throw new SmallvilleException("Object id cannot be blank");
	}
	if (request.getType() == null || request.getType().isBlank()) {
	    throw new SmallvilleException("Object type cannot be blank");
	}

	WorldObjectInstance instance = objectInstances.getOrDefault(objectId, new WorldObjectInstance());
	instance.id = objectId;
	instance.type = request.getType();
	instance.name = request.getName() == null || request.getName().isBlank() ? objectId : request.getName();
	instance.x = request.getX();
	instance.y = request.getY();
	Location location = findLocationAt(request.getX(), request.getY());
	instance.location = request.getLocation() != null ? request.getLocation() : (location == null ? null : location.getFullPath());

	Map<String, Object> merged = new HashMap<>();
	merged.putAll(objectTypeDefinitions.getOrDefault(instance.type, new HashMap<>()));
	if (request.getProperties() != null) {
	    merged.putAll(request.getProperties());
	}
	instance.properties = merged;

	objectInstances.put(objectId, instance);

	return instance.toMap();
    }

    public Map<String, Object> getObjectInstance(String objectId) {
	WorldObjectInstance instance = objectInstances.get(objectId);
	if (instance == null) {
	    throw new SmallvilleException("Unknown object id: " + objectId);
	}
	return instance.toMap();
    }

    public List<Map<String, Object>> getAllObjectInstances() {
	return objectInstances.values().stream().map(WorldObjectInstance::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> getCoordinateSnapshot(double x, double y) {
	Map<String, Object> snapshot = new LinkedHashMap<>();
	Location location = findLocationAt(x, y);
	String locationName = location == null ? null : location.getFullPath();
	snapshot.put("x", x);
	snapshot.put("y", y);
	snapshot.put("location", locationName);
	snapshot.put("objects", getObjectsAtCoordinate(x, y));
	return snapshot;
    }

    public Map<String, Object> getLocationAtCoordinate(double x, double y) {
	Location location = findLocationAt(x, y);
	Map<String, Object> response = new LinkedHashMap<>();
	response.put("x", x);
	response.put("y", y);
	response.put("location", location == null ? null : location.getFullPath());
	return response;
    }

    public List<Map<String, Object>> getObjectsAtCoordinate(double x, double y) {
	final double tolerance = 25.0;
	Location location = findLocationAt(x, y);
	String locationName = location == null ? null : location.getFullPath();

	List<Map<String, Object>> objects = new ArrayList<>();

	for (Agent agent : world.getAgents()) {
	    if (agent.getLocation() == null) {
		continue;
	    }
	    boolean sameLocation = locationName != null && locationName.equals(agent.getLocation().getFullPath());
	    boolean near = Math.hypot(agent.getX() - x, agent.getY() - y) <= tolerance;
	    if (sameLocation && near) {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("kind", agent instanceof Player ? "player" : "agent");
		a.put("id", agent.getFullName());
		a.put("name", agent.getFullName());
		a.put("x", agent.getX());
		a.put("y", agent.getY());
		a.put("location", agent.getLocation().getFullPath());
		a.put("activity", agent.getCurrentActivity());
		objects.add(a);
	    }
	}

	for (WorldObjectInstance instance : objectInstances.values()) {
	    boolean sameLocation = locationName != null && locationName.equals(instance.location);
	    boolean near = Math.hypot(instance.x - x, instance.y - y) <= tolerance;
	    if (sameLocation && near) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("kind", "object");
		item.putAll(instance.toMap());
		objects.add(item);
	    }
	}

	return objects;
    }

    private void enqueueReactiveEvent(String agentName, String description, int severity, boolean playerInvolved) {
	ReactiveEvent event = new ReactiveEvent();
	event.description = description;
	event.severity = Math.max(1, Math.min(10, severity));
	event.createdAt = SimulationTime.now();
	event.playerInvolved = playerInvolved;

	Deque<ReactiveEvent> queue = reactiveEventsByAgent.computeIfAbsent(agentName, k -> new ArrayDeque<>());
	queue.addFirst(event);
	while (queue.size() > MAX_REACTIVE_EVENTS) {
	    queue.removeLast();
	}
    }

    public Map<String, Object> orchestrateRuntime(RuntimeOrchestrationRequest request) {
	return orchestrateRuntime(request, null);
    }

    private Map<String, Object> orchestrateRuntime(RuntimeOrchestrationRequest request, PlayerActionRequest lastPlayerAction) {
	Map<String, Object> summary = new LinkedHashMap<>();
	List<String> llmUpdated = new ArrayList<>();
	List<String> deterministicUpdated = new ArrayList<>();
	List<String> reacted = new ArrayList<>();

	LocalDateTime now = SimulationTime.now();
	LocalDate nowDate = now.toLocalDate();
	boolean isEndOfDayReflectionMinute = now.getHour() == 23 && now.getMinute() == 59;
	Location playerLocation = null;
	if (request.getPlayerX() != null && request.getPlayerY() != null) {
	    playerLocation = findLocationAt(request.getPlayerX(), request.getPlayerY());
	}

	// Apply client-reported NPC positions and detect arrivals
	if (request.getNpcPositions() != null && !request.getNpcPositions().isEmpty()) {
	    for (java.util.Map<String, Object> pos : request.getNpcPositions()) {
		String npcName = (String) pos.get("name");
		if (npcName == null) continue;
		Agent npc = world.getAgent(npcName).orElse(null);
		if (npc == null || npc instanceof Player) continue;
		double nx = pos.containsKey("x") ? ((Number) pos.get("x")).doubleValue() : npc.getX();
		double ny = pos.containsKey("y") ? ((Number) pos.get("y")).doubleValue() : npc.getY();
		npc.setPosition(nx, ny);
		Location npcLoc = findLocationAt(nx, ny);
		if (npcLoc != null) {
		    npc.setLocation(npcLoc);
		}
		if (npc.getTargetLocation() != null) {
		    Location targetLoc = world.getLocation(npc.getTargetLocation()).orElse(null);
		    if (targetLoc != null && targetLoc.isWithinBounds(nx, ny)) {
			npc.setLocation(targetLoc);
			npc.setTargetLocation(null);
		    }
		}
	    }
	}

	for (Agent agent : world.getAgents()) {
	    if (agent instanceof Player) {
		continue;
	    }
	    RuntimeAgentState state = runtimeStateByAgent.computeIfAbsent(agent.getFullName(), k -> new RuntimeAgentState());
	    boolean isAware = isAgentAware(agent, playerLocation, request.getPlayerX(), request.getPlayerY(), request.getAwarenessRadius());
	    traceTrackedAgent(agent, state, "start-turn");
	    LocalDate lastOrchestratedDate = state.lastOrchestratedAt == null ? null : state.lastOrchestratedAt.toLocalDate();
	    boolean crossedMidnight = lastOrchestratedDate != null && !lastOrchestratedDate.equals(nowDate);

	    // End-of-day reflection: primary trigger at 23:59 simulation time,
	    // plus fallback when a tick jumps over midnight.
	    if ((isEndOfDayReflectionMinute || crossedMidnight)
		&& (state.lastReflectionDate == null || !state.lastReflectionDate.equals(lastOrchestratedDate == null ? nowDate : lastOrchestratedDate))) {
		try {
		    prompts.runEndOfDayReflection(agent);
		    state.lastReflectionDate = (lastOrchestratedDate == null ? nowDate : lastOrchestratedDate);
		    llmUpdated.add(agent.getFullName());
		} catch (Exception e) {
		    LOG.warn("[Runtime] Reflection failed for {}: {}", agent.getFullName(), e.getMessage());
		}
	    }

	    boolean dayStart = request.isForceDayStart() || state.lastRoutineDate == null || !state.lastRoutineDate.equals(nowDate);
	    if (dayStart) {
		try {
		    prompts.refreshAgentForNewDay(agent);
		    state.lastRoutineDate = nowDate;
		    state.lastLlmCallAt = SimulationTime.now();
		    llmUpdated.add(agent.getFullName());
		    traceTrackedAgent(agent, state, "after-day-start-refresh");
		} catch (Exception e) {
		    LOG.warn("[Runtime] Day-start refresh failed for {}: {}. Falling back to deterministic update.", agent.getFullName(), e.getMessage());
		    applyDeterministicCatchUp(agent, isAware);
		    deterministicUpdated.add(agent.getFullName());
		    traceTrackedAgent(agent, state, "after-day-start-fallback");
		}
	    } else {
		ReactiveEvent event = pollReactiveEvent(agent.getFullName());
		if (event != null) {
		    boolean shouldLlmReact = shouldTriggerLlmReaction(event, isAware);
		    if (shouldLlmReact) {
			try {
			    prompts.react(agent, event.description);
			    state.lastLlmCallAt = SimulationTime.now();
			    reacted.add(agent.getFullName());
			    llmUpdated.add(agent.getFullName());
			} catch (Exception e) {
			    LOG.warn("[Runtime] Reactive LLM update failed for {}: {}. Falling back to deterministic reaction.", agent.getFullName(), e.getMessage());
			    applyDeterministicReactiveFallback(agent, event);
			    deterministicUpdated.add(agent.getFullName());
			    traceTrackedAgent(agent, state, "after-reactive-fallback");
			}
		    } else {
			applyDeterministicReactiveFallback(agent, event);
			deterministicUpdated.add(agent.getFullName());
			traceTrackedAgent(agent, state, "after-reactive-fallback");
		    }
		} else {
		    applyDeterministicCatchUp(agent, isAware);
		    deterministicUpdated.add(agent.getFullName());
		    traceTrackedAgent(agent, state, "after-deterministic-catchup");
		}

	    runAgenticObjectiveLoop(agent, state, now, request, lastPlayerAction);
	    }

	    // Mark agent as orchestrated after first movement pass
	    agent.setHasBeenOrchestrated(true);

	    state.lastAware = isAware;
	    state.lastOrchestratedAt = SimulationTime.now();
	}

	summary.put("time", SimulationTime.now().toString());
	summary.put("llmUpdatedAgents", llmUpdated);
	summary.put("deterministicUpdatedAgents", deterministicUpdated);
	summary.put("reactedAgents", reacted);
	summary.put("playerAwareLocation", playerLocation == null ? null : playerLocation.getFullPath());
	return summary;
    }

    private ReactiveEvent pollReactiveEvent(String agentName) {
	Deque<ReactiveEvent> queue = reactiveEventsByAgent.get(agentName);
	if (queue == null || queue.isEmpty()) {
	    return null;
	}
	return queue.pollFirst();
    }

    private boolean shouldTriggerLlmReaction(ReactiveEvent event, boolean isAware) {
	if (event.severity >= 8) {
	    return true;
	}
	if (event.playerInvolved && isAware && event.severity >= 5) {
	    return true;
	}
	return false;
    }

    private boolean isAgentAware(Agent agent, Location playerLocation, Double playerX, Double playerY, double radius) {
	if (playerX == null || playerY == null || playerLocation == null || agent.getLocation() == null) {
	    return false;
	}
	if (!playerLocation.getFullPath().equals(agent.getLocation().getFullPath())) {
	    return false;
	}
	double dx = agent.getX() - playerX;
	double dy = agent.getY() - playerY;
	return Math.hypot(dx, dy) <= Math.max(1.0, radius);
    }

	private boolean isTrackedAgent(Agent agent) {
		return agent != null
			&& agent.getFullName() != null
			&& trackedAgentName != null
			&& agent.getFullName().equalsIgnoreCase(trackedAgentName);
	}

	private void setTrackedAgentName(String agentName) {
		if (agentName == null || agentName.isBlank()) {
			trackedAgentName = DEFAULT_TRACKED_AGENT_NAME;
			return;
		}
		trackedAgentName = agentName;
	}

	private boolean shouldAutoTrackNewAgent() {
		if (trackedAgentName == null || trackedAgentName.isBlank()) {
			return true;
		}
		if (DEFAULT_TRACKED_AGENT_NAME.equalsIgnoreCase(trackedAgentName)) {
			return true;
		}
		return world.getAgent(trackedAgentName).isEmpty();
	}

	private String getTrackedAgentLabel() {
		return trackedAgentName == null || trackedAgentName.isBlank() ? DEFAULT_TRACKED_AGENT_NAME : trackedAgentName;
	}

	private String traceAgentSnapshot(Agent agent) {
		if (agent == null) {
			return "agent=null";
		}
		String location = agent.getLocation() == null ? "null" : agent.getLocation().getFullPath();
		String activity = agent.getCurrentActivity() == null ? "null" : agent.getCurrentActivity();
		String target = agent.getTargetLocation() == null ? "null" : agent.getTargetLocation();
		return "time=" + SimulationTime.now()
			+ " loc=" + location
			+ " activity=" + activity
			+ " target=" + target
			+ " pos=(" + String.format("%.1f", agent.getX()) + "," + String.format("%.1f", agent.getY()) + ")";
	}

	private void traceTrackedAgent(Agent agent, RuntimeAgentState state, String phase) {
		if (!isTrackedAgent(agent)) {
			return;
		}
		String location = agent.getLocation() == null ? "null" : agent.getLocation().getFullPath();
		String activity = agent.getCurrentActivity() == null ? "null" : agent.getCurrentActivity();
		String target = agent.getTargetLocation() == null ? "null" : agent.getTargetLocation();
		boolean changed = state == null
			|| !java.util.Objects.equals(state.lastTraceActivity, activity)
			|| !java.util.Objects.equals(state.lastTraceLocation, location)
			|| !java.util.Objects.equals(state.lastTraceTarget, target)
			|| state.lastTraceX == null
			|| state.lastTraceY == null
			|| Math.abs(state.lastTraceX - agent.getX()) > TRACE_POSITION_EPSILON
			|| Math.abs(state.lastTraceY - agent.getY()) > TRACE_POSITION_EPSILON;
		boolean forcePhase = phase.contains("boundary") || phase.contains("blocked") || phase.contains("arrival");
		boolean heartbeatDue = state == null
			|| state.lastTraceLoggedAt == null
			|| Duration.between(state.lastTraceLoggedAt, SimulationTime.now()).toMinutes() >= TRACK_HEARTBEAT_MINUTES;
		if (changed || forcePhase || heartbeatDue) {
			LOG.info("[Track:{}] {}", getTrackedAgentLabel(), phase + " | " + traceAgentSnapshot(agent));
			if (state != null) {
				state.lastTraceLoggedAt = SimulationTime.now();
			}
		}
		if (state != null) {
			state.lastTraceActivity = activity;
			state.lastTraceLocation = location;
			state.lastTraceTarget = target;
			state.lastTraceX = agent.getX();
			state.lastTraceY = agent.getY();
		}
	}

	public Map<String, Object> getAgenticState(String agentName) {
		Agent agent = world.getAgent(agentName).orElseThrow(() -> new AgentNotFoundException(agentName));
		AgenticRuntimeState state = agenticStateByAgent.computeIfAbsent(agent.getFullName(), k -> new AgenticRuntimeState());

		Map<String, Object> view = new LinkedHashMap<>();
		view.put("agent", agent.getFullName());
		view.put("phase", state.phase.name());
		view.put("objective", state.currentObjective);
		view.put("targetObject", state.targetObjectId);
		view.put("pendingPlayer", state.pendingPlayerId);
		view.put("chatWindowClosedObserved", state.chatWindowClosedObserved);
		view.put("deferredTurns", state.deferredTurns);
		view.put("recentIgnoreCount", state.recentIgnoreCount);
		view.put("lastInitiativeScore", state.lastInitiativeScore);
		view.put("lastOutcome", state.lastOutcome);
		view.put("lastInitiatedAt", state.lastInitiatedAt == null ? null : state.lastInitiatedAt.toString());
		view.put("lastRepliedAt", state.lastRepliedAt == null ? null : state.lastRepliedAt.toString());
		view.put("cooldownUntil", state.cooldownUntil == null ? null : state.cooldownUntil.toString());
		view.put("lastError", state.lastError);
		return view;
	}

	private void runAgenticObjectiveLoop(
		Agent agent,
		RuntimeAgentState runtimeState,
		LocalDateTime now,
		RuntimeOrchestrationRequest request,
		PlayerActionRequest lastPlayerAction) {
		runAgenticSocialLoop(agent, runtimeState, now, request, lastPlayerAction);
	}

	private void runAgenticSocialLoop(
		Agent agent,
		RuntimeAgentState runtimeState,
		LocalDateTime now,
		RuntimeOrchestrationRequest request,
		PlayerActionRequest lastPlayerAction) {
		if (agent == null || agent instanceof Player) {
			return;
		}

		AgenticRuntimeState state = agenticStateByAgent.computeIfAbsent(agent.getFullName(), k -> new AgenticRuntimeState());
		Agent player = findPrimaryPlayer();
		if (player == null) {
			return;
		}

		boolean playerVisible = isWithinSocialAwareness(agent, player, request);
		double initiativeScore = computeSocialInitiativeScore(agent, player);
		state.lastInitiativeScore = initiativeScore;

		try {
			switch (state.phase) {
				case IDLE -> {
					if (!playerVisible || !shouldInitiateSocialContact(agent, state, initiativeScore, now)) {
						return;
					}
					state.currentObjective = AGENTIC_OBJECTIVE_SOCIAL_CONTACT;
					state.targetObjectId = null;
					state.lastError = null;
					if (isWithinInitiateRange(agent, player)) {
						initiateConversationWithPlayer(agent, player, state, now);
					} else {
						transitionAgenticPhase(state, AgenticPhase.APPROACH_PLAYER, now);
						moveTowardPlayer(agent, player);
						LOG.info("[Agentic] {} objective=social_contact phase=APPROACH_PLAYER score={}",
							agent.getFullName(), String.format("%.2f", initiativeScore));
					}
				}
				case APPROACH_PLAYER -> {
					if (!playerVisible) {
						state.currentObjective = null;
						transitionAgenticPhase(state, AgenticPhase.IDLE, now);
						agent.setTargetLocation(null);
						return;
					}
					moveTowardPlayer(agent, player);
					if (isWithinInitiateRange(agent, player)) {
						initiateConversationWithPlayer(agent, player, state, now);
					}
				}
				case AWAITING_PLAYER_REPLY -> {
					evaluatePendingSocialOutcome(agent, player, state, now, request, lastPlayerAction);
				}
				case COOLDOWN -> {
					if (state.cooldownUntil == null || now == null || !now.isBefore(state.cooldownUntil)) {
						transitionAgenticPhase(state, AgenticPhase.IDLE, now);
						state.currentObjective = null;
						state.pendingPlayerId = null;
						state.chatWindowClosedObserved = false;
						state.pinnedLastTurn = false;
						state.deferredTurns = 0;
					}
				}
			}
		} catch (Exception e) {
			state.lastError = e.getMessage();
			LOG.error("[Agentic] {} loop error at phase {}: {}", agent.getFullName(), state.phase, e.getMessage(), e);
		}

		traceTrackedAgent(agent, runtimeState, "after-agentic-" + state.phase.name().toLowerCase());
	}

	private void transitionAgenticPhase(AgenticRuntimeState state, AgenticPhase next, LocalDateTime now) {
		if (state == null) {
			return;
		}
		if (state.phase != next) {
			state.phase = next;
		}
		state.phaseUpdatedAt = now;
	}

	private Agent findPrimaryPlayer() {
		for (Agent candidate : world.getAgents()) {
			if (candidate instanceof Player) {
				return candidate;
			}
		}
		return null;
	}

	private boolean isWithinSocialAwareness(Agent agent, Agent player, RuntimeOrchestrationRequest request) {
		if (agent == null || player == null || player.getLocation() == null || agent.getLocation() == null) {
			return false;
		}
		if (!player.getLocation().getFullPath().equals(agent.getLocation().getFullPath())) {
			return false;
		}
		double px = request != null && request.getPlayerX() != null ? request.getPlayerX() : player.getX();
		double py = request != null && request.getPlayerY() != null ? request.getPlayerY() : player.getY();
		double dx = agent.getX() - px;
		double dy = agent.getY() - py;
		return Math.hypot(dx, dy) <= AGENTIC_SOCIAL_AWARENESS_RADIUS;
	}

	private boolean isWithinInitiateRange(Agent agent, Agent player) {
		if (agent == null || player == null || agent.getLocation() == null || player.getLocation() == null) {
			return false;
		}
		if (!agent.getLocation().getFullPath().equals(player.getLocation().getFullPath())) {
			return false;
		}
		return calculateDistance(agent, player) <= AGENTIC_INITIATE_RADIUS;
	}

	private boolean shouldInitiateSocialContact(Agent agent, AgenticRuntimeState state, double initiativeScore, LocalDateTime now) {
		if (agent == null || state == null) {
			return false;
		}
		if (state.phase != AgenticPhase.IDLE) {
			return false;
		}
		if (state.cooldownUntil != null && now != null && now.isBefore(state.cooldownUntil)) {
			return false;
		}
		double threshold = 0.55 + (state.recentIgnoreCount * 0.1);
		return initiativeScore >= threshold;
	}

	private double computeSocialInitiativeScore(Agent agent, Agent player) {
		if (agent == null || player == null) {
			return 0.0;
		}
		double distance = calculateDistance(agent, player);
		double proximity = clamp01(1.0 - (distance / AGENTIC_SOCIAL_AWARENESS_RADIUS));
		double sociability = clamp01(
			(agent.getCompassion() * 0.35)
			+ (agent.getSocialDominance() * 0.35)
			+ (agent.getRiskTolerance() * 0.2)
			+ ((1.0 - agent.getFearfulness()) * 0.1));
		return clamp01((proximity * 0.45) + (sociability * 0.55));
	}

	private void moveTowardPlayer(Agent agent, Agent player) {
		if (agent == null || player == null) {
			return;
		}
		if (player.getLocation() != null) {
			agent.setTargetLocation(player.getLocation().getFullPath());
		}
		agent.setCurrentActivity("agentic: moving closer to start a conversation");
	}

	private void initiateConversationWithPlayer(Agent agent, Agent player, AgenticRuntimeState state, LocalDateTime now) {
		if (agent == null || player == null || state == null) {
			return;
		}
		String opener = buildPersonalityOpening(agent, player);
		if (opener != null && !opener.isBlank()) {
			List<Dialog> lines = new ArrayList<>();
			lines.add(new Dialog(agent.getFullName(), opener));
			world.create(new Conversation(agent.getFullName(), player.getFullName(), lines));
		}
		agent.setTargetLocation(null);
		agent.setCurrentActivity("agentic: speaking to player");
		recordCommittedAction(agent, "SPEAK", "initiated conversation with player");
		agent.getMemoryStream().add(new Observation("Initiated conversation with " + player.getFullName() + ": " + opener));

		transitionAgenticPhase(state, AgenticPhase.AWAITING_PLAYER_REPLY, now);
		state.pendingPlayerId = player.getFullName();
		state.chatWindowClosedObserved = false;
		state.pinnedLastTurn = false;
		state.deferredTurns = 0;
		state.lastInitiatedAt = now;
		state.lastOutcome = "initiated";
		LOG.info("[Agentic] {} initiated social chat with {}", agent.getFullName(), player.getFullName());
	}

	private void evaluatePendingSocialOutcome(
		Agent agent,
		Agent player,
		AgenticRuntimeState state,
		LocalDateTime now,
		RuntimeOrchestrationRequest request,
		PlayerActionRequest lastPlayerAction) {
		if (agent == null || player == null || state == null) {
			return;
		}

		boolean pinnedNow = request != null && request.isPinned(agent.getFullName());
		if (state.pinnedLastTurn && !pinnedNow) {
			state.chatWindowClosedObserved = true;
		}
		state.pinnedLastTurn = pinnedNow;

		if (isReplyToAgent(lastPlayerAction, player, agent)) {
			agent.setCurrentActivity("agentic: engaged in conversation");
			state.lastRepliedAt = now;
			state.lastOutcome = "success";
			state.recentIgnoreCount = Math.max(0, state.recentIgnoreCount - 1);
			enterSocialCooldown(state, now, "success");
			LOG.info("[Agentic] {} social outcome=success player={}", agent.getFullName(), player.getFullName());
			return;
		}

		if (state.chatWindowClosedObserved && isMoveAwayAction(lastPlayerAction, player, agent)) {
			agent.setCurrentActivity("agentic: conversation declined");
			state.recentIgnoreCount = Math.min(5, state.recentIgnoreCount + 1);
			state.lastOutcome = "ignored";
			enterSocialCooldown(state, now, "ignored");
			LOG.info("[Agentic] {} social outcome=ignored player={}", agent.getFullName(), player.getFullName());
			return;
		}

		if (lastPlayerAction != null && player.getFullName().equals(lastPlayerAction.getPlayerId())) {
			state.deferredTurns++;
		}

		if (state.deferredTurns >= AGENTIC_MAX_DEFERRED_TURNS) {
			agent.setCurrentActivity("agentic: deferred conversation");
			state.lastOutcome = "deferred";
			enterSocialCooldown(state, now, "deferred");
			LOG.info("[Agentic] {} social outcome=deferred player={}", agent.getFullName(), player.getFullName());
		}
	}

	private boolean isReplyToAgent(PlayerActionRequest action, Agent player, Agent agent) {
		if (action == null || player == null || agent == null) {
			return false;
		}
		if (!player.getFullName().equals(action.getPlayerId())) {
			return false;
		}
		String type = action.getActionType() == null ? "" : action.getActionType().toLowerCase();
		boolean isSpeak = "speak".equals(type) || "talk".equals(type) || (action.getSpeakText() != null && !action.getSpeakText().isBlank());
		if (!isSpeak) {
			return false;
		}
		return action.getTargetAgent() != null && action.getTargetAgent().equalsIgnoreCase(agent.getFullName());
	}

	private boolean isMoveAwayAction(PlayerActionRequest action, Agent player, Agent agent) {
		if (action == null || player == null || agent == null) {
			return false;
		}
		if (!player.getFullName().equals(action.getPlayerId())) {
			return false;
		}
		if (action.getActionType() == null || !"move".equalsIgnoreCase(action.getActionType())) {
			return false;
		}
		if (player.getLocation() == null || agent.getLocation() == null) {
			return true;
		}
		if (!player.getLocation().getFullPath().equals(agent.getLocation().getFullPath())) {
			return true;
		}
		return calculateDistance(player, agent) > AGENTIC_DISENGAGE_RADIUS;
	}

	private void enterSocialCooldown(AgenticRuntimeState state, LocalDateTime now, String outcome) {
		if (state == null) {
			return;
		}
		transitionAgenticPhase(state, AgenticPhase.COOLDOWN, now);
		state.cooldownUntil = now == null ? null : now.plusMinutes(AGENTIC_SOCIAL_COOLDOWN_MINUTES);
		state.currentObjective = null;
		state.pendingPlayerId = null;
		state.chatWindowClosedObserved = false;
		state.pinnedLastTurn = false;
		state.deferredTurns = 0;
		state.lastOutcome = outcome;
	}

	private String buildPersonalityOpening(Agent agent, Agent player) {
		String playerName = player == null ? "there" : player.getFullName();
		if (agent.getFearfulness() >= 0.7) {
			return "Hey " + playerName + ", quick check-in... is everything okay around here?";
		}
		if (agent.getSocialDominance() >= 0.7) {
			return "" + playerName + ", got a minute? I want your take on something.";
		}
		if (agent.getCompassion() >= 0.65) {
			return "Hi " + playerName + ", how are you holding up today?";
		}
		if (agent.getImpulsivity() >= 0.7) {
			return "Hey " + playerName + "! You won't believe what I was just thinking about.";
		}
		return "Hey " + playerName + ", want to chat for a moment?";
	}

	private double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

    private void applyDeterministicReactiveFallback(Agent agent, ReactiveEvent event) {
	InstinctDecision decision = evaluateInstinctDecision(agent, event);
	if (decision != null) {
	    agent.applyStressChange(decision.stressDelta);
	    recordStressEventIfSignificant(agent, event.description);
	    agent.setCurrentActivity(decision.activity);
	    if (decision.targetLocation != null && !decision.targetLocation.isBlank()) {
		agent.setTargetLocation(decision.targetLocation);
	    }
	    recordCommittedAction(agent, decision.action, decision.reason);
	    agent.getMemoryStream().add(new Observation("Instinct response: " + decision.action + " because " + decision.reason));
	    return;
	}

	agent.applyStressChange(Math.min(0.25, event.severity * 0.02));
	recordStressEventIfSignificant(agent, event.description);
	agent.setCurrentActivity("processing event");
	recordCommittedAction(agent, "PROCESS_EVENT", event.description);
	agent.getMemoryStream().add(new Observation("Deterministic response: " + event.description));
    }

    private void applyDeterministicCatchUp(Agent agent, boolean awareToPlayer) {
	if (!awareToPlayer) {
	    agent.applyStressChange(-0.01);
	}

	if (!isAgenticMovementLocked(agent)) {
	    applyScheduledActivity(agent);
	}

	if (shouldRecoverFromTransientReactiveState(agent)) {
	    LOG.info("[Reactive] Clearing stale reactive state for {}", agent.getFullName());
	    agent.setCurrentActivity("following routine");
	}

	if (agent.getCurrentActivity() == null || agent.getCurrentActivity().isBlank() || "idle".equalsIgnoreCase(agent.getCurrentActivity())) {
	    agent.setCurrentActivity("following routine");
	}
	}

	private boolean isAgenticMovementLocked(Agent agent) {
		if (agent == null || agent.getFullName() == null) {
			return false;
		}
		AgenticRuntimeState state = agenticStateByAgent.get(agent.getFullName());
		if (state == null || state.phase == null) {
			return false;
		}
		return state.phase == AgenticPhase.APPROACH_PLAYER
			|| state.phase == AgenticPhase.AWAITING_PLAYER_REPLY;
	}

	private boolean shouldRecoverFromTransientReactiveState(Agent agent) {
		if (agent == null || agent.getTargetLocation() != null) {
			return false;
		}
		if (!isTransientReactiveActivity(agent.getCurrentActivity())) {
			return false;
		}
		return !hasPendingReactiveEvent(agent.getFullName());
	}

	private boolean isTransientReactiveActivity(String activity) {
		if (activity == null || activity.isBlank()) {
			return false;
		}
		String lowered = activity.toLowerCase();
		return lowered.contains("hesitating")
			|| lowered.contains("processing event")
			|| lowered.contains("standing ground")
			|| lowered.contains("protecting nearby ally");
	}

	private boolean hasPendingReactiveEvent(String agentName) {
		Deque<ReactiveEvent> queue = reactiveEventsByAgent.get(agentName);
		return queue != null && !queue.isEmpty();
	}

	private void recordCommittedAction(Agent agent, String action, String reason) {
		if (agent == null) {
			return;
		}
		CommittedAction committed = new CommittedAction();
		committed.action = action;
		committed.reason = reason;
		committed.location = agent.getLocation() == null ? null : agent.getLocation().getFullPath();
		committed.x = agent.getX();
		committed.y = agent.getY();
		committed.createdAt = SimulationTime.now();

		Deque<CommittedAction> actions = committedActionsByAgent.computeIfAbsent(agent.getFullName(), k -> new ArrayDeque<>());
		actions.addFirst(committed);
		while (actions.size() > MAX_COMMITTED_ACTIONS) {
			actions.removeLast();
		}
	}

	private InstinctDecision evaluateInstinctDecision(Agent agent, ReactiveEvent event) {
		if (agent == null || event == null) {
			return null;
		}

		double aggression = agent.getAggression();
		double fear = agent.getFearfulness();
		double loyalty = agent.getLoyalty();
		double impulsivity = agent.getImpulsivity();

		InstinctDecision decision = new InstinctDecision();

		if (event.severity >= 8 && fear > aggression + 0.15) {
			Location flee = findSaferLocation(agent);
			decision.action = "FLEE";
			decision.activity = "fleeing from danger";
			decision.targetLocation = flee == null ? null : flee.getFullPath();
			decision.stressDelta = Math.min(0.2, event.severity * 0.015);
			decision.reason = "high threat and fear-dominant temperament";
			return decision;
		}

		if (event.severity >= 7 && (aggression + impulsivity) > fear) {
			decision.action = "CONFRONT";
			decision.activity = "standing ground";
			decision.targetLocation = agent.getLocation() == null ? null : agent.getLocation().getFullPath();
			decision.stressDelta = Math.min(0.18, event.severity * 0.012);
			decision.reason = "threat response with aggressive temperament";
			return decision;
		}

		if (event.playerInvolved && loyalty > 0.6 && event.severity >= 5) {
			decision.action = "PROTECT";
			decision.activity = "protecting nearby ally";
			decision.targetLocation = agent.getLocation() == null ? null : agent.getLocation().getFullPath();
			decision.stressDelta = Math.min(0.12, event.severity * 0.01);
			decision.reason = "player-involved event with loyal temperament";
			return decision;
		}

		if (event.severity >= 4) {
			decision.action = "FREEZE";
			decision.activity = "hesitating";
			decision.targetLocation = null;
			decision.stressDelta = Math.min(0.1, event.severity * 0.008);
			decision.reason = "uncertain threat with mixed temperament";
			return decision;
		}

		return null;
	}

	private Location findSaferLocation(Agent agent) {
		if (agent == null || agent.getLocation() == null) {
			return null;
		}

		Location current = agent.getLocation();
		Location best = null;
		double bestScore = Double.NEGATIVE_INFINITY;

		for (Location location : getLocationsCached()) {
			if (location.getFullPath().equals(current.getFullPath())) {
				continue;
			}
			double dx = location.getCenterX() - agent.getX();
			double dy = location.getCenterY() - agent.getY();
			double distance = Math.hypot(dx, dy);
			double score = distance - ("public".equalsIgnoreCase(location.getType()) ? 80.0 : 0.0);
			if (score > bestScore) {
				bestScore = score;
				best = location;
			}
		}

		return best;
	}

	private String composeContextAwareQuestion(Agent agent, String playerQuestion) {
		Map<String, Object> packet = new LinkedHashMap<>();

		Map<String, Object> temperament = new LinkedHashMap<>();
		temperament.put("aggression", roundTrait(agent.getAggression()));
		temperament.put("fearfulness", roundTrait(agent.getFearfulness()));
		temperament.put("loyalty", roundTrait(agent.getLoyalty()));
		temperament.put("impulsivity", roundTrait(agent.getImpulsivity()));
		temperament.put("compassion", roundTrait(agent.getCompassion()));
		temperament.put("riskTolerance", roundTrait(agent.getRiskTolerance()));
		temperament.put("socialDominance", roundTrait(agent.getSocialDominance()));

		Map<String, Object> self = new LinkedHashMap<>();
		self.put("name", agent.getFullName());
		self.put("location", agent.getLocation() == null ? null : agent.getLocation().getFullPath());
		self.put("x", agent.getX());
		self.put("y", agent.getY());
		self.put("currentActivity", agent.getCurrentActivity());
		self.put("temperament", temperament);

		List<Map<String, Object>> nearbyAgents = new ArrayList<>();
		for (Agent other : world.getAgents()) {
			if (other.getFullName().equals(agent.getFullName())) {
				continue;
			}
			double distance = agent.distanceTo(other);
			if (distance > 180.0) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", other.getFullName());
			row.put("location", other.getLocation() == null ? null : other.getLocation().getFullPath());
			row.put("distance", roundTrait(distance / 180.0) * 180.0);
			row.put("activity", other.getCurrentActivity());
			nearbyAgents.add(row);
		}

		List<Map<String, Object>> commitments = new ArrayList<>();
		Deque<CommittedAction> committed = committedActionsByAgent.getOrDefault(agent.getFullName(), new ArrayDeque<>());
		int count = 0;
		for (CommittedAction c : committed) {
			if (count++ >= 3) {
				break;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("action", c.action);
			row.put("reason", c.reason);
			row.put("location", c.location);
			row.put("time", c.createdAt == null ? null : c.createdAt.toString());
			commitments.add(row);
		}

		packet.put("self", self);
		packet.put("nearbyAgents", nearbyAgents);
		packet.put("committedActions", commitments);

		return "You must stay consistent with committed actions and current world state. "
			+ "Here is a compact environment packet: " + packet.toString()
			+ "\nPlayer says: " + playerQuestion;
	}

private static final double STRESS_MEMORY_THRESHOLD = 0.5;

	/**
	 * Records a high-importance observation in the agent's memory when their
	 * stress level is at or above the significant threshold after an event.
	 * These memories persist and influence future planning and reactions.
	 */
	private void recordStressEventIfSignificant(Agent agent, String eventDescription) {
		double level = agent.getStressLevel();
		if (level >= STRESS_MEMORY_THRESHOLD) {
			String memText = "High-stress event: " + eventDescription
				+ " (stress " + String.format("%.0f", level * 100) + "%)";
			Observation stressMemory = new Observation(memText);
			stressMemory.setImportance(8);
			stressMemory.setReactable(false);
			agent.getMemoryStream().add(stressMemory);
			LOG.info("[Stress] Recorded stress memory for {}: {}", agent.getFullName(), memText);
		}
	}

	private double roundTrait(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private void applyScheduledActivity(Agent agent) {
		LocalDateTime now = SimulationTime.now();
		List<io.github.nickm980.smallville.memory.Commitment> commitments = agent.getMemoryStream()
			.getPlans(io.github.nickm980.smallville.memory.PlanType.COMMITMENT).stream()
			.filter(p -> p instanceof io.github.nickm980.smallville.memory.Commitment)
			.map(p -> (io.github.nickm980.smallville.memory.Commitment) p)
			.collect(Collectors.toList());

		// Expire commitments whose window has passed
		commitments.stream()
			.filter(c -> c.isExpired(now)
				&& c.getStatus() != io.github.nickm980.smallville.memory.CommitmentStatus.COMPLETED)
			.forEach(c -> {
				if (c.getStatus() == io.github.nickm980.smallville.memory.CommitmentStatus.PENDING) {
					c.setStatus(io.github.nickm980.smallville.memory.CommitmentStatus.DEFERRED);
				} else {
					c.setStatus(io.github.nickm980.smallville.memory.CommitmentStatus.COMPLETED);
				}
				// Clear the target so the agent stops heading there
				if (agent.getTargetLocation() != null
					&& agent.getTargetLocation().equalsIgnoreCase(c.getLocation())) {
					agent.setTargetLocation(null);
				}
			});

		// Find the highest-priority active commitment for this sim moment
		io.github.nickm980.smallville.memory.Commitment active = commitments.stream()
			.filter(c -> c.getStatus() != io.github.nickm980.smallville.memory.CommitmentStatus.COMPLETED
				&& c.isActiveAt(now))
			.max(java.util.Comparator.comparingInt(
				io.github.nickm980.smallville.memory.Commitment::getPriority))
			.orElse(null);

		if (active != null) {
			if (active.getStatus() == io.github.nickm980.smallville.memory.CommitmentStatus.PENDING) {
				active.setStatus(io.github.nickm980.smallville.memory.CommitmentStatus.ACTIVE);
			}
			Location activeLocation = world.getLocation(active.getLocation()).orElse(null);
			boolean atCommitmentLocation = activeLocation != null
				? isAgentWithinLocationBounds(agent, activeLocation)
				: (agent.getLocation() != null
					&& agent.getLocation().getFullPath().equalsIgnoreCase(active.getLocation()));
			if (atCommitmentLocation) {
				if (activeLocation != null) {
					agent.setLocation(activeLocation);
				}
				agent.setTargetLocation(null);
				agent.setCurrentActivity(active.getGoal());
			} else {
				agent.setCurrentActivity("heading to " + active.getLocation() + " for " + active.getGoal());
				agent.setTargetLocation(active.getLocation());
			}
			return;
		}

		// If the next commitment starts soon, begin traveling toward it so agents don't
		// appear idle/stuck between windows.
		io.github.nickm980.smallville.memory.Commitment upcoming = commitments.stream()
			.filter(c -> c.getStatus() == io.github.nickm980.smallville.memory.CommitmentStatus.PENDING)
			.filter(c -> !c.getTime().isBefore(now))
			.min(Comparator.comparing(io.github.nickm980.smallville.memory.Commitment::getTime))
			.orElse(null);

		if (upcoming != null) {
			long minutesUntilStart = Duration.between(now, upcoming.getTime()).toMinutes();
			if (minutesUntilStart <= 30) {
				agent.setCurrentActivity("getting ready for " + upcoming.getGoal());
				if (agent.getLocation() == null
					|| !agent.getLocation().getFullPath().equalsIgnoreCase(upcoming.getLocation())) {
					agent.setTargetLocation(upcoming.getLocation());
				} else {
					agent.setTargetLocation(null);
				}
				return;
			}
		}

		// No active commitment; fall back to short-term plan selection
		Plan currentPlan = findCurrentPlan(agent);
		if (currentPlan == null) {
			return;
		}

		String description = currentPlan.getDescription();
		String activity = stripLeadingTime(description);
		if (!activity.isBlank()) {
			agent.setCurrentActivity(activity);
		}

		Location scheduledLocation = findMentionedLocation(description);
		if (scheduledLocation != null) {
			agent.setTargetLocation(scheduledLocation.getFullPath());
		}
	}

	private Plan findCurrentPlan(Agent agent) {
		LocalDateTime now = SimulationTime.now();
		List<Plan> shortTermPlans = agent.getMemoryStream().getPlans().stream()
			.filter(plan -> plan.getType() == io.github.nickm980.smallville.memory.PlanType.SHORT_TERM)
			.collect(Collectors.toList());

		if (shortTermPlans.isEmpty()) {
			return null;
		}

		// Prefer plans within a fuzzy two-hour window around current simulation time.
		final long fuzzyWindowMinutes = 120;
		Plan inWindow = shortTermPlans.stream()
			.filter(plan -> plan.getTime() != null)
			.min(Comparator.comparingLong(plan -> {
				long delta = Math.abs(java.time.Duration.between(now, plan.getTime()).toMinutes());
				return delta <= fuzzyWindowMinutes ? delta : Long.MAX_VALUE / 2 + delta;
			}))
			.orElse(null);

		if (inWindow != null) {
			return inWindow;
		}

		return shortTermPlans.stream()
			.filter(plan -> plan.getTime() != null)
			.min(Comparator.comparing(plan -> java.time.Duration.between(now, plan.getTime()).abs()))
			.orElse(shortTermPlans.get(0));
	}

	private String stripLeadingTime(String description) {
		if (description == null) {
			return "";
		}
		String sanitized = description.trim();
		sanitized = sanitized.replaceFirst("^\\s*[-*•]+\\s*", "");
		sanitized = sanitized.replaceFirst("^\\s*\\d{1,2}\\s*[.)-:]\\s*", "");
		sanitized = sanitized.replaceFirst("^\\s*\\d{1,2}:\\d{2}(?:\\s*[AaPp][Mm])?\\s*(?:[-:–—]\\s*)?", "");
		sanitized = sanitized.replaceFirst("^\\s*\\d{1,2}\\s*[AaPp][Mm]\\s*(?:[-:–—]\\s*)?", "");
		return sanitized.trim();
	}

	private Location findMentionedLocation(String description) {
		String lowered = description.toLowerCase();
		Location match = null;
		for (Location location : getLocationsCached()) {
			if (lowered.contains(location.getFullPath().toLowerCase())) {
				if (match == null || location.getFullPath().length() > match.getFullPath().length()) {
					match = location;
				}
			}
		}
		return match;
	}

	private boolean isAgentWithinLocationBounds(Agent agent, Location location) {
		if (agent == null || location == null) {
			return false;
		}
		return location.isWithinBounds(agent.getX(), agent.getY());
	}

	private int clearNonPlayerAgents() {
		List<String> removedNames = world.getAgents().stream()
			.filter(agent -> !(agent instanceof Player))
			.map(Agent::getFullName)
			.collect(Collectors.toList());
		int removedAgents = world.removeNonPlayerAgents();
		for (String name : removedNames) {
			runtimeStateByAgent.remove(name);
			reactiveEventsByAgent.remove(name);
			committedActionsByAgent.remove(name);
			agenticStateByAgent.remove(name);
		}
		if (removedNames.stream().anyMatch(name -> name.equalsIgnoreCase(getTrackedAgentLabel()))) {
			setTrackedAgentName(null);
		}
		return removedAgents;
	}

	private CreateAgentRequest toCreateAgentRequest(GeneratedAgentBlueprint blueprint) {
		CreateAgentRequest request = new CreateAgentRequest();
		request.setName(blueprint.getName());
		request.setActivity(blueprint.getCurrentActivity());
		request.setLocation(blueprint.getHomeLocation());
		request.setMemories(blueprint.getMemories());
		return request;
	}

	private GeneratedAgentBlueprint parseGeneratedAgentBlueprint(String rawResponse) {
		if (rawResponse == null || rawResponse.isBlank()) {
			return null;
		}
		String json = extractJsonObject(rawResponse);
		if (json == null) {
			return null;
		}
		try {
			return objectMapper.readValue(json, GeneratedAgentBlueprint.class);
		} catch (Exception e) {
			LOG.warn("Failed to parse generated agent blueprint: {}", e.getMessage());
			return null;
		}
	}

	private String extractJsonObject(String text) {
		if (text == null) {
			return null;
		}
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start < 0 || end <= start) {
			return null;
		}
		return text.substring(start, end + 1);
	}

	private GeneratedAgentBlueprint validateAndNormalizeGeneratedAgent(GeneratedAgentBlueprint candidate,
		GenerateAgentRequest request,
		Set<String> existingNames,
		List<Location> locations,
		List<String> warnings,
		int index) {
		GeneratedAgentBlueprint blueprint = candidate == null ? new GeneratedAgentBlueprint() : candidate;
		if (blueprint.getName() == null || blueprint.getName().isBlank()) {
			warnings.add("Missing name; applied fallback name");
			blueprint.setName("Generated Agent " + (index + 1));
		}
		blueprint.setName(makeUniqueAgentName(blueprint.getName(), existingNames));

		Location location = resolveGeneratedAgentLocation(blueprint.getHomeLocation(), request, locations);
		if (blueprint.getHomeLocation() == null || !location.getFullPath().equalsIgnoreCase(blueprint.getHomeLocation())) {
			warnings.add("Adjusted homeLocation to an existing location");
		}
		blueprint.setHomeLocation(location.getFullPath());

		if (blueprint.getCurrentActivity() == null || blueprint.getCurrentActivity().isBlank()) {
			warnings.add("Missing currentActivity; applied fallback activity");
			blueprint.setCurrentActivity("settling into the day at " + location.getFullPath());
		}

		blueprint.setCoreTraits(sanitizeTextList(blueprint.getCoreTraits(), 0, 6));
		blueprint.setFlaws(sanitizeTextList(blueprint.getFlaws(), 0, 4));
		blueprint.setMemories(normalizeGeneratedMemories(blueprint, location, warnings));
		if (blueprint.getSocialStyle() == null || blueprint.getSocialStyle().isBlank()) {
			blueprint.setSocialStyle("guarded but curious");
		}
		if (blueprint.getDailyAnchors() == null) {
			Map<String, String> anchors = new LinkedHashMap<>();
			anchors.put("morning", "ease into the day near " + location.getFullPath());
			anchors.put("noon", "watch people passing through " + location.getFullPath());
			anchors.put("evening", "reflect quietly before heading home");
			blueprint.setDailyAnchors(anchors);
		}
		return blueprint;
	}

	private List<String> normalizeGeneratedMemories(GeneratedAgentBlueprint blueprint, Location location, List<String> warnings) {
		LinkedHashSet<String> memories = new LinkedHashSet<>(sanitizeTextList(blueprint.getMemories(), 0, 12));
		if (memories.size() < 6) {
			warnings.add("Expanded memory list to minimum viable seed set");
		}
		for (String trait : sanitizeTextList(blueprint.getCoreTraits(), 0, 6)) {
			if (memories.size() >= 12) {
				break;
			}
			memories.add("People often read me as " + trait + ", even when they miss the full story.");
		}
		for (String flaw : sanitizeTextList(blueprint.getFlaws(), 0, 4)) {
			if (memories.size() >= 12) {
				break;
			}
			memories.add("I know my habit of being " + flaw + " gets me into trouble sometimes.");
		}
		if (memories.size() < 12) {
			memories.add("I spend a lot of time around " + location.getFullPath() + ".");
		}
		if (memories.size() < 12) {
			memories.add("I have reasons for keeping some parts of myself hidden from strangers.");
		}
		if (memories.size() < 12) {
			memories.add("I want my day to look ordinary even when my thoughts are not.");
		}
		return memories.stream().limit(12).collect(Collectors.toList());
	}

	private List<String> sanitizeTextList(List<String> values, int min, int max) {
		LinkedHashSet<String> sanitized = new LinkedHashSet<>();
		if (values != null) {
			for (String value : values) {
				if (value == null) {
					continue;
				}
				String cleaned = value.trim();
				if (!cleaned.isBlank()) {
					sanitized.add(cleaned);
				}
				if (sanitized.size() == max) {
					break;
				}
			}
		}
		return new ArrayList<>(sanitized.stream().limit(Math.max(min, max)).collect(Collectors.toList()));
	}

	private String makeUniqueAgentName(String baseName, Set<String> existingNames) {
		String cleaned = baseName == null ? "Generated Agent" : baseName.trim();
		if (cleaned.isBlank()) {
			cleaned = "Generated Agent";
		}
		String candidate = cleaned;
		int suffix = 2;
		while (containsNameIgnoreCase(existingNames, candidate)) {
			candidate = cleaned + " " + suffix;
			suffix++;
		}
		return candidate;
	}

	private boolean containsNameIgnoreCase(Set<String> existingNames, String candidate) {
		for (String name : existingNames) {
			if (name != null && name.equalsIgnoreCase(candidate)) {
				return true;
			}
		}
		return false;
	}

	private Location resolveGeneratedAgentLocation(String proposedLocation, GenerateAgentRequest request, List<Location> locations) {
		Location exact = findLocationByName(proposedLocation, locations);
		if (exact != null) {
			return exact;
		}
		Location preferred = findLocationByName(request == null ? null : request.getPreferredLocation(), locations);
		if (preferred != null) {
			return preferred;
		}
		return locations.get(0);
	}

	private Location findLocationByName(String locationName, List<Location> locations) {
		if (locationName == null || locationName.isBlank()) {
			return null;
		}
		for (Location location : locations) {
			if (location.getFullPath().equalsIgnoreCase(locationName.trim())) {
				return location;
			}
		}
		return null;
	}

	private String buildAgentGenerationPrompt(GenerateAgentRequest request, int index, List<Location> locations, Set<String> existingNames) {
		String locationList = locations.stream().map(Location::getFullPath).collect(Collectors.joining(", "));
		String usedNames = existingNames.isEmpty() ? "none" : String.join(", ", existingNames);
		String extraPrompt = request.getPrompt() == null || request.getPrompt().isBlank()
			? ""
			: "Additional theme request: " + request.getPrompt().trim() + "\n";
		return "Generate exactly one Smallville NPC as strict JSON with no markdown or commentary.\n"
			+ "The NPC must be internally consistent, have believable flaws, and may appear contradictory from the outside.\n"
			+ extraPrompt
			+ "Use one homeLocation from this exact list: [" + locationList + "]\n"
			+ "Do not reuse these names: [" + usedNames + "]\n"
			+ "Return a JSON object with these keys only: name, homeLocation, currentActivity, coreTraits, flaws, memories, socialStyle, dailyAnchors.\n"
			+ "Constraints:\n"
			+ "- currentActivity: short present-tense phrase\n"
			+ "- coreTraits: array of 3 to 5 strings\n"
			+ "- flaws: array of 2 to 4 strings\n"
			+ "- memories: array of 6 to 10 short first-person factual memories\n"
			+ "- dailyAnchors: object with morning, noon, evening strings\n"
			+ "- keep the NPC grounded in the chosen location and suitable for a town simulation\n"
			+ "- this is NPC number " + (index + 1) + " in the batch\n"
			+ "JSON only.";
	}

	private String buildAgentRepairPrompt(String rawGeneration,
		List<String> validationIssues,
		GenerateAgentRequest request,
		List<Location> locations,
		Set<String> existingNames) {
		String locationList = locations.stream().map(Location::getFullPath).collect(Collectors.joining(", "));
		String usedNames = existingNames.isEmpty() ? "none" : String.join(", ", existingNames);
		return "Repair the following NPC JSON so it satisfies the schema and constraints. Keep the spirit of the character. Return strict JSON only.\n"
			+ "Allowed locations: [" + locationList + "]\n"
			+ "Disallowed duplicate names: [" + usedNames + "]\n"
			+ "Validation issues: " + String.join("; ", validationIssues) + "\n"
			+ "Required keys only: name, homeLocation, currentActivity, coreTraits, flaws, memories, socialStyle, dailyAnchors\n"
			+ "Additional theme request: " + (request.getPrompt() == null ? "none" : request.getPrompt()) + "\n"
			+ "Original JSON candidate:\n"
			+ rawGeneration;
	}

	private boolean isTileOccupiedByOtherAgent(double x, double y, Agent ignoreAgent) {
		int tileX = toTile(x);
		int tileY = toTile(y);
		for (Agent agent : world.getAgents()) {
			if (ignoreAgent != null && agent.getFullName().equals(ignoreAgent.getFullName())) {
				continue;
			}
			if (toTile(agent.getX()) == tileX && toTile(agent.getY()) == tileY) {
				return true;
			}
		}
		return false;
	}

	private Agent findOccupyingAgentAtTile(Location location, double x, double y, Agent ignoreAgent) {
		if (location == null) {
			return null;
		}
		int targetTileX = toTile(x);
		int targetTileY = toTile(y);
		for (Agent agent : world.getAgents()) {
			if (ignoreAgent != null && agent.getFullName().equals(ignoreAgent.getFullName())) {
				continue;
			}
			if (agent.getLocation() == null) {
				continue;
			}
			if (!location.getFullPath().equals(agent.getLocation().getFullPath())) {
				continue;
			}
			if (toTile(agent.getX()) == targetTileX && toTile(agent.getY()) == targetTileY) {
				return agent;
			}
		}
		return null;
	}

	private int toTile(double coordinate) {
		return (int) Math.floor(coordinate / TILE_SIZE);
	}

	private double snapToTile(double coordinate) {
		return Math.round(coordinate / TILE_SIZE) * TILE_SIZE;
	}

	private double snapToTileWithinBounds(double coordinate, double min, double max) {
		double minTile = Math.ceil(min / TILE_SIZE) * TILE_SIZE;
		double maxTile = Math.floor(max / TILE_SIZE) * TILE_SIZE;
		if (minTile <= maxTile) {
			double snapped = snapToTile(coordinate);
			return clamp(snapped, minTile, maxTile);
		}
		return clamp(coordinate, min, max);
	}

    private double clamp(double value, double min, double max) {
	return Math.max(min, Math.min(max, value));
    }

    public int getPendingReactiveEventCount() {
	int total = 0;
	for (Deque<ReactiveEvent> queue : reactiveEventsByAgent.values()) {
	    total += queue.size();
	}
	return total;
    }

    private Location findLocationAt(double x, double y) {
	Location specificMatch = null;
	double smallestArea = Double.MAX_VALUE;
	Location outsideMatch = null;

	for (Location location : getLocationsCached()) {
	    if (!location.isWithinBounds(x, y)) {
		continue;
	    }

	    if (isOutsideLocation(location)) {
		outsideMatch = location;
		continue;
	    }

	    double area = locationArea(location);
	    if (specificMatch == null || area < smallestArea) {
		specificMatch = location;
		smallestArea = area;
	    }
	}

	if (specificMatch != null) {
	    return specificMatch;
	}
	if (outsideMatch != null) {
	    return outsideMatch;
	}

	for (Location location : getLocationsCached()) {
	    if (isOutsideLocation(location)) {
		return location;
	    }
	}

	return null;
    }

	private boolean isOutsideLocation(Location location) {
		if (location == null || location.getFullPath() == null) {
			return false;
		}
		String name = location.getFullPath().toLowerCase();
		String type = location.getType() == null ? "" : location.getType().toLowerCase();
		return "outside".equals(name) || "street".equals(name) || "road".equals(name)
			|| "outside".equals(type) || "street".equals(type) || "road".equals(type);
	}

	private double locationArea(Location location) {
		double width = Math.max(0.0, location.getMaxX() - location.getMinX());
		double height = Math.max(0.0, location.getMaxY() - location.getMinY());
		return width * height;
	}

    public Map<String, Object> getAgentMemorySummary(String agentName) {
	Agent agent = world.getAgent(agentName).orElseThrow(() -> new AgentNotFoundException(agentName));
	List<Memory> all = agent.getMemoryStream().getMemories();
	List<Memory> top = all.stream()
	    .sorted(Comparator.comparing(Memory::getImportance).reversed())
	    .limit(5)
	    .collect(Collectors.toList());

	Map<String, Object> summary = new LinkedHashMap<>();
	summary.put("agent", agentName);
	summary.put("total", all.size());
	summary.put("top", top.stream().map(mem -> Map.of(
	    "description", mem.getDescription(),
	    "importance", mem.getImportance(),
	    "type", mem.getClass().getSimpleName()
	)).collect(Collectors.toList()));
	return summary;
    }

    public List<MemoryResponse> getAgentMemoriesRecent(String agentName, int limit) {
	int safeLimit = Math.max(1, limit);
	List<MemoryResponse> all = getMemoriesOfAgent(agentName);
	return all.stream().limit(safeLimit).collect(Collectors.toList());
    }

    public MemoryResponse getAgentMemoryByIndex(String agentName, int index) {
	List<MemoryResponse> all = getMemoriesOfAgent(agentName);
	if (index < 0 || index >= all.size()) {
	    throw new SmallvilleException("Memory index out of range");
	}
	return all.get(index);
    }

    public Map<String, Object> bootstrapSchedules() {
	List<String> bootstrapped = new ArrayList<>();
	List<String> skipped = new ArrayList<>();
	for (Agent agent : world.getAgents()) {
	    if (agent instanceof Player) {
		skipped.add(agent.getFullName() + " (player)");
		continue;
	    }
	    if (agent.getMemoryStream().getPlans().isEmpty()) {
		try {
		    prompts.updateAgent(agent);
		    bootstrapped.add(agent.getFullName());
		} catch (Exception e) {
		    LOG.warn("Bootstrap schedule failed for {}: {}", agent.getFullName(), e.getMessage());
		    skipped.add(agent.getFullName() + " (error: " + e.getMessage() + ")");
		}
	    } else {
		skipped.add(agent.getFullName() + " (already has schedule)");
	    }
	}
	return Map.of("bootstrapped", bootstrapped, "skipped", skipped);
    }

    public List<ScheduleResponse> getAgentSchedule(String agentName) {
	Agent agent = world.getAgent(agentName).orElseThrow(() -> new AgentNotFoundException(agentName));
	List<ScheduleResponse> schedule = new ArrayList<>();
	
	// Get all plans and format them as schedule items
	for (Plan plan : agent.getMemoryStream().getPlans()) {
	    schedule.add(new ScheduleResponse(
	        plan.getDescription(),
	        plan.getTime(),
	        plan.getType().toString()
	    ));
	}
	
	// Sort by time ascending
	schedule.sort((a, b) -> a.getTime().compareTo(b.getTime()));
	LOG.info("[Schedules] Returning {} schedule items for {}", schedule.size(), agentName);
	
	return schedule;
    }

    public Map<String, Object> getLlmCallPolicy() {
	Map<String, Object> response = new LinkedHashMap<>();
	response.put("stable", List.of(
	    "day_start_reflection_and_routine",
	    "agent_creation_worldview_generation",
	    "reaction_to_extrenuating_events"
	));
	response.put("situational", List.of(
	    "player_direct_conversation",
	    "overheard_or_eavesdropped_context",
	    "witnessed_high_impact_events",
	    "meaningful_player_interaction"
	));
	response.put("avoid", List.of(
	    "player_step_with_no_interruptions",
	    "inanimate_object_no_consequence_interactions",
	    "offscreen_agents_without_significant_conflict"
	));
	response.put("offscreen_mode", "run deterministic catch-up and persist state effects only");
	return response;
    }

    public List<Map<String, Object>> getLatencyBudgetTable() {
	List<Map<String, Object>> rows = new ArrayList<>();
	rows.add(Map.of(
	    "callType", "reaction",
	    "targetP95Seconds", "2-6",
	    "maxInputTokens", "800-2000",
	    "notes", "keep prompts short and event-focused"
	));
	rows.add(Map.of(
	    "callType", "player_dialogue",
	    "targetP95Seconds", "2-8",
	    "maxInputTokens", "1200-3000",
	    "notes", "include only relevant recent memory summary"
	));
	rows.add(Map.of(
	    "callType", "daily_reflection",
	    "targetP95Seconds", "6-15",
	    "maxInputTokens", "3000-9000",
	    "notes", "run async at day boundary"
	));
	rows.add(Map.of(
	    "callType", "daily_routine_generation",
	    "targetP95Seconds", "4-12",
	    "maxInputTokens", "2000-6000",
	    "notes", "cache stable profile context"
	));
	rows.add(Map.of(
	    "callType", "offscreen_conflict_reconcile",
	    "targetP95Seconds", "8-20",
	    "maxInputTokens", "4000-12000",
	    "notes", "batch only high-severity unresolved conflicts"
	));
	return rows;
    }

    Map<UUID, MemoryStream> memories = new HashMap<UUID, MemoryStream>();

    public UUID createMemoryStream() {
	UUID uuid = UUID.randomUUID();
	memories.put(uuid, new MemoryStream());
	return uuid;
    }

    public List<String> getMemories(UUID uuid, String query) {
	MemoryStream stream = memories.get(uuid);
	
	return stream
	    .getRelevantMemories(query)
	    .stream()
	    .map(memory -> memory.getDescription())
	    .collect(Collectors.toList());
    }

    /**
     * Calculate distance between two agents
     */
    private double calculateDistance(Agent agent1, Agent agent2) {
        if (agent1 == null || agent2 == null) return Double.MAX_VALUE;
        return agent1.distanceTo(agent2);
    }

    /**
     * Check if two agents are in the same location
     */
    private boolean sameLocation(Agent agent1, Agent agent2) {
        if (agent1 == null || agent2 == null) return false;
        if (agent1.getLocation() == null || agent2.getLocation() == null) return false;
        return agent1.getLocation().getFullPath().equals(agent2.getLocation().getFullPath());
    }

    /**
     * Compute time cost for interaction based on distance
     * Base time + distance scaling
     */
    private long computeDistanceAdjustedDuration(long baseSeconds, double distance) {
        double distanceCost = distance * 0.1; // 0.1 seconds per unit of distance
        return baseSeconds + (long) Math.ceil(distanceCost);
    }

    /**
     * Check if two agents can interact (within range, same location, etc.)
     * @return error message if cannot interact, null if OK
     */
    private String validateInteractionFeasibility(Agent initiator, Agent target) {
        if (initiator == null || target == null) {
            return "Initiator or target agent is null";
        }
        
        if (!sameLocation(initiator, target)) {
            return "Agents are not in the same location";
        }

        double distance = calculateDistance(initiator, target);
        // Max interaction range of 75 units
        if (distance > 75.0) {
            return "Target is too far away (distance: " + (int)distance + " units)";
        }

        return null; // OK
    }
}
