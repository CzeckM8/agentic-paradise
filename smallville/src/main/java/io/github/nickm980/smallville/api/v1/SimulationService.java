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
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private final ModelMapper mapper;
    private final UpdateService prompts;
    private final World world;
    private int progress;
    private final Queue<PlayerActionRequest> actionQueue = new ConcurrentLinkedQueue<>();
	private static final int MAX_ACTION_HISTORY = 100;
	private static final int MAX_REACTIVE_EVENTS = 30;
	private List<LocationStateResponse> cachedLocations = null;
	private List<Location> cachedLocationEntities = null;
	private final Map<String, Deque<PlayerActionRequest>> actionHistoryByPlayer = new ConcurrentHashMap<>();
	private final Map<String, Map<String, Object>> objectTypeDefinitions = new ConcurrentHashMap<>();
	private final Map<String, WorldObjectInstance> objectInstances = new ConcurrentHashMap<>();
	private final Map<String, RuntimeAgentState> runtimeStateByAgent = new ConcurrentHashMap<>();
	private final Map<String, Deque<ReactiveEvent>> reactiveEventsByAgent = new ConcurrentHashMap<>();

	private static class RuntimeAgentState {
		private LocalDate lastRoutineDate;
		private LocalDate lastReflectionDate;
		private LocalDateTime lastLlmCallAt;
		private LocalDateTime lastOrchestratedAt;
		private boolean lastAware;
	}

	private static class ReactiveEvent {
		private String description;
		private int severity;
		private LocalDateTime createdAt;
		private boolean playerInvolved;
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
	return mapper.fromAgent(agent);
    }

    public List<AgentStateResponse> getAgents() {
	List<Agent> agents = world.getAgents();

	return agents.stream().map(mapper::fromAgent).collect(Collectors.toList());
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
	    String traits = prompts.createTraitsWithCharacteristics(agent);
	    agent.setTraits(traits);
	    RuntimeAgentState state = runtimeStateByAgent.computeIfAbsent(agent.getFullName(), k -> new RuntimeAgentState());
	    state.lastRoutineDate = SimulationTime.now().toLocalDate();
	    state.lastReflectionDate = SimulationTime.now().toLocalDate();
	    state.lastLlmCallAt = SimulationTime.now();
	}
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
	orchestrateRuntime(new RuntimeOrchestrationRequest());
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
			return new PlayerActionResponse(false, "No actions in queue");
		}

		PlayerActionResponse response = executeAction(request);
		// After executing the action, advance time and orchestrate with player-awareness context.
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
		Map<String, Object> runtimeSummary = orchestrateRuntime(orchestrationRequest);
		if (response.getResult() == null || response.getResult().isBlank()) {
			response.setResult("Turn processed");
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

		if ("move".equalsIgnoreCase(request.getActionType())) {
			if (request.getTargetLocation() == null) {
				throw new SmallvilleException("targetLocation required for move");
			}
			world.getLocation(request.getTargetLocation()).ifPresentOrElse(loc -> {
				// Validate target position is within location bounds
				double targetX = request.getPlayerX();
				double targetY = request.getPlayerY();
				if (!loc.isWithinBounds(targetX, targetY)) {
					throw new SmallvilleException("Target position (" + targetX + ", " + targetY + ") is outside location bounds");
				}

				Agent occupant = findOccupyingAgentAtTile(loc, targetX, targetY, player);
				if (occupant != null) {
					throw new SmallvilleException("Target tile is occupied by " + occupant.getFullName());
				}

				player.setLocation(loc);
				player.setPosition(targetX, targetY);
				player.setCurrentActivity("Moving to " + loc.getFullPath());
			}, () -> {
				throw new LocationNotFoundException(request.getTargetLocation());
			});

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
			// player also experiences stress when committing aggressive acts
			player.applyStressChange(intensity * 0.05);

			targetAgent.setCurrentActivity(request.getActionDescription() != null ? request.getActionDescription() : "Interacted");
			player.setCurrentActivity(request.getActionDescription() != null ? request.getActionDescription() : "Interacted");
			int eventSeverity = "attack".equalsIgnoreCase(request.getActionType()) ? 9 : 6;
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
				enqueueReactiveEvent(bystander.getFullName(), "witnessed: " + (request.getActionDescription() == null ? "interaction" : request.getActionDescription()), 5, true);
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
			for (Agent listener : world.getAgents()) {
			    if (listener.getFullName().equals(player.getFullName())) {
				continue;
			    }
			    if (listener.getLocation() != null && player.getLocation() != null
				&& listener.getLocation().getFullPath().equals(player.getLocation().getFullPath())
				&& listener.distanceTo(player) <= 110.0) {
				enqueueReactiveEvent(listener.getFullName(), "overheard: " + request.getSpeakText(), 4, true);
			    }
			}
			res.setPlayerState(mapper.fromAgent(player));
			res.setResult("Spoke: " + (request.getSpeakText() == null ? "" : request.getSpeakText()));
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
			d.setStressLevel(a.getStressLevel());
			d.setMentalState(a.getMentalState());
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
	Map<String, Object> summary = new LinkedHashMap<>();
	List<String> llmUpdated = new ArrayList<>();
	List<String> deterministicUpdated = new ArrayList<>();
	List<String> reacted = new ArrayList<>();

	LocalDate nowDate = SimulationTime.now().toLocalDate();
	Location playerLocation = null;
	if (request.getPlayerX() != null && request.getPlayerY() != null) {
	    playerLocation = findLocationAt(request.getPlayerX(), request.getPlayerY());
	}

	for (Agent agent : world.getAgents()) {
	    if (agent instanceof Player) {
		continue;
	    }
	    RuntimeAgentState state = runtimeStateByAgent.computeIfAbsent(agent.getFullName(), k -> new RuntimeAgentState());
	    boolean isAware = isAgentAware(agent, playerLocation, request.getPlayerX(), request.getPlayerY(), request.getAwarenessRadius());

	    boolean dayStart = request.isForceDayStart() || state.lastRoutineDate == null || !state.lastRoutineDate.equals(nowDate);
	    if (dayStart) {
		prompts.updateAgent(agent);
		state.lastRoutineDate = nowDate;
		state.lastReflectionDate = nowDate;
		state.lastLlmCallAt = SimulationTime.now();
		llmUpdated.add(agent.getFullName());
	    } else {
		ReactiveEvent event = pollReactiveEvent(agent.getFullName());
		if (event != null) {
		    boolean shouldLlmReact = shouldTriggerLlmReaction(event, isAware);
		    if (shouldLlmReact) {
			prompts.react(agent, event.description);
			state.lastLlmCallAt = SimulationTime.now();
			reacted.add(agent.getFullName());
			llmUpdated.add(agent.getFullName());
		    } else {
			applyDeterministicReactiveFallback(agent, event);
			deterministicUpdated.add(agent.getFullName());
		    }
		} else {
		    applyDeterministicCatchUp(agent, isAware);
		    deterministicUpdated.add(agent.getFullName());
		}
	    }

	    advanceAgentMovement(agent);

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

    private void applyDeterministicReactiveFallback(Agent agent, ReactiveEvent event) {
	agent.applyStressChange(Math.min(0.25, event.severity * 0.02));
	agent.setCurrentActivity("processing event");
	agent.getMemoryStream().add(new Observation("Deterministic response: " + event.description));
    }

    private void applyDeterministicCatchUp(Agent agent, boolean awareToPlayer) {
	if (!awareToPlayer) {
	    agent.applyStressChange(-0.01);
	}

	applyScheduledActivity(agent);

	if (agent.getCurrentActivity() == null || agent.getCurrentActivity().isBlank() || "idle".equalsIgnoreCase(agent.getCurrentActivity())) {
	    agent.setCurrentActivity("following routine");
	}
	}

	private void applyScheduledActivity(Agent agent) {
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

		Plan candidate = null;
		for (Plan plan : shortTermPlans) {
			if (plan.getTime() == null || plan.getTime().isAfter(now)) {
				continue;
			}
			if (candidate == null || plan.getTime().isAfter(candidate.getTime())) {
				candidate = plan;
			}
		}

		if (candidate != null) {
			return candidate;
		}

		return agent.getMemoryStream().getPlans().stream()
			.filter(plan -> plan.getTime() != null)
			.min(Comparator.comparing(plan -> java.time.Duration.between(now, plan.getTime()).abs()))
			.orElse(null);
	}

	private String stripLeadingTime(String description) {
		return description.replaceFirst("^\\s*\\d{1,2}:\\d{2}\\s*[AaPp][Mm]\\s*", "").trim();
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

	private void advanceAgentMovement(Agent agent) {
		// Skip movement on first turn after agent creation
		if (!agent.hasBeenOrchestrated()) {
			return;
		}

		Location targetLocation = resolveTargetLocation(agent);
		if (targetLocation == null) {
			stepAgentRoutine(agent);
			return;
		}

		double targetX;
		double targetY;
		if (agent.getLocation() != null && targetLocation.getFullPath().equals(agent.getLocation().getFullPath())) {
			targetX = snapToTile(targetLocation.getCenterX());
			targetY = snapToTile(targetLocation.getCenterY());
		} else {
			// Choose the nearest in-bounds tile as an "entrance" point into the destination location.
			targetX = snapToTile(clamp(agent.getX(), targetLocation.getMinX(), targetLocation.getMaxX()));
			targetY = snapToTile(clamp(agent.getY(), targetLocation.getMinY(), targetLocation.getMaxY()));
		}

		stepAgentToward(agent, targetX, targetY, targetLocation);
	}

	private Location resolveTargetLocation(Agent agent) {
		String targetName = agent.getTargetLocation();
		if (targetName == null || targetName.isBlank()) {
			return null;
		}

		Location target = world.getLocation(targetName).orElse(null);
		if (target == null) {
			agent.setTargetLocation(null);
			return null;
		}

		if (agent.getLocation() != null && agent.getLocation().getFullPath().equals(target.getFullPath())
				&& toTile(agent.getX()) == toTile(target.getCenterX())
				&& toTile(agent.getY()) == toTile(target.getCenterY())) {
			agent.setTargetLocation(null);
			return null;
		}

		return target;
	}

	private void stepAgentRoutine(Agent agent) {
		Location current = agent.getLocation();
		if (current == null) {
			return;
		}

		int directionSeed = Math.floorMod((agent.getFullName() + SimulationTime.now().toString()).hashCode(), 4);
		double nextX = agent.getX();
		double nextY = agent.getY();
		switch (directionSeed) {
			case 0:
				nextX += TILE_SIZE;
				break;
			case 1:
				nextX -= TILE_SIZE;
				break;
			case 2:
				nextY += TILE_SIZE;
				break;
			default:
				nextY -= TILE_SIZE;
				break;
		}

		nextX = snapToTile(clamp(nextX, current.getMinX(), current.getMaxX()));
		nextY = snapToTile(clamp(nextY, current.getMinY(), current.getMaxY()));
		if (toTile(nextX) == toTile(agent.getX()) && toTile(nextY) == toTile(agent.getY())) {
			return;
		}
		if (!isTileOccupiedByOtherAgent(nextX, nextY, agent)) {
			agent.setPosition(nextX, nextY);
		}
	}

	private void stepAgentToward(Agent agent, double targetX, double targetY, Location targetLocation) {
		int currentTileX = toTile(agent.getX());
		int currentTileY = toTile(agent.getY());
		int targetTileX = toTile(targetX);
		int targetTileY = toTile(targetY);
		if (currentTileX == targetTileX && currentTileY == targetTileY) {
			Location locationAtCurrent = findLocationAt(agent.getX(), agent.getY());
			if (locationAtCurrent != null) {
				agent.setLocation(locationAtCurrent);
				if (locationAtCurrent.getFullPath().equals(targetLocation.getFullPath())) {
					agent.setTargetLocation(null);
				}
			}
			return;
		}

		int dx = Integer.compare(targetTileX, currentTileX);
		int dy = Integer.compare(targetTileY, currentTileY);
		double stepX = snapToTile(agent.getX() + (dx * TILE_SIZE));
		double stepY = snapToTile(agent.getY() + (dy * TILE_SIZE));

		boolean prioritizeX = Math.abs(targetTileX - currentTileX) >= Math.abs(targetTileY - currentTileY);
		List<double[]> candidates = new ArrayList<>();
		if (prioritizeX) {
			if (dx != 0) candidates.add(new double[] { stepX, snapToTile(agent.getY()) });
			if (dy != 0) candidates.add(new double[] { snapToTile(agent.getX()), stepY });
		} else {
			if (dy != 0) candidates.add(new double[] { snapToTile(agent.getX()), stepY });
			if (dx != 0) candidates.add(new double[] { stepX, snapToTile(agent.getY()) });
		}

		for (double[] candidate : candidates) {
			double nextX = candidate[0];
			double nextY = candidate[1];
			if (isTileOccupiedByOtherAgent(nextX, nextY, agent)) {
				continue;
			}
			agent.setPosition(nextX, nextY);
			Location locationAtNewPosition = findLocationAt(nextX, nextY);
			if (locationAtNewPosition != null) {
				agent.setLocation(locationAtNewPosition);
				if (locationAtNewPosition.getFullPath().equals(targetLocation.getFullPath())
						&& toTile(nextX) == targetTileX && toTile(nextY) == targetTileY) {
					agent.setTargetLocation(null);
				}
			}
			return;
		}
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
	for (Location location : getLocationsCached()) {
	    if (location.isWithinBounds(x, y)) {
		return location;
	    }
	}
	return null;
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
