package io.github.nickm980.smallville.api.v1;

import static io.github.nickm980.smallville.api.SmallvilleServer.exists;

import java.io.StringWriter;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import io.github.nickm980.smallville.analytics.Analytics;
import io.github.nickm980.smallville.api.v1.dto.*;
import io.github.nickm980.smallville.entities.SimulationTime;
import io.javalin.community.routing.annotations.Endpoints;
import io.javalin.community.routing.annotations.Get;
import io.javalin.community.routing.annotations.Param;
import io.javalin.community.routing.annotations.Post;
import io.javalin.http.Context;

@Endpoints("/")
public final class SimulationController {

    private MustacheFactory mf;
    private Analytics analytics;
    private SimulationService service;
    private Gson gson = new Gson();
    private final Logger LOG = LoggerFactory.getLogger(SimulationController.class);

    public SimulationController(Analytics analytics, SimulationService service, MustacheFactory mf) {
	this.mf = mf;
	this.analytics = analytics;
	this.service = service;
    }

    
    @Get("/ping")
    public void ping(Context ctx) {
	ctx.status(200).json(Map.of("success", true, "ping", "pong"));
    }
    
    @Post("/memories/stream")
    public void createMemoryStream(Context ctx) {
	UUID uuid = service.createMemoryStream();
	ctx.json(Map.of("success", true, "uuid", uuid));
    }

    @Post("/memories/stream/{uuid}")
    public void saveMemory(Context ctx, @Param String uuidStr) {
	UUID uuid = UUID.fromString(uuidStr);

	Map<String, String> dataMap = gson.fromJson(ctx.body(), new TypeToken<Map<String, String>>() {
	}.getType());

	String query = (String) dataMap.get("query");

	List<String> result = service.getMemories(uuid, query);
	ctx.status(200).json(Map.of("success", true, "memories", result));
    }

    @Get("/memories/{name}")
    public void getMemoryByName(Context ctx, @Param String name) {
	Map<String, Object> model = new HashMap<>();
	model.put("memories", service.getMemoriesOfAgent(name));

	Mustache mustache = mf.compile("agent.mustache");
	String output = mustache.execute(new StringWriter(), model).toString();
	ctx.html(output);
    }

    @Get("/info")
    public void getInfo(Context ctx) {
	String time = SimulationTime.now().format(DateTimeFormatter.ofPattern("h:mm a"));

	ctx
	    .json(Map
		.of("time", time, "step", SimulationTime.getStepDurationInMinutes(), "prompts",
			analytics.getPromptHistory(), "locationVisits", analytics.getVisits()));
    }

    @Get("/agents")
    public void getAgents(Context ctx) {
	ctx.json(Map.of("agents", service.getAgents()));
    }

    @Get("/agents/{name}")
    public void getAgentsByName(Context ctx, @Param String name) {
	AgentStateResponse res = service.getAgentState(name);
	ctx.json(res);
    }

	@Get("/agents/{name}/memories/summary")
	public void getAgentMemorySummary(Context ctx, @Param String name) {
	ctx.json(service.getAgentMemorySummary(name));
	}

	@Get("/agents/{name}/memories/recent")
	public void getAgentMemoryRecent(Context ctx, @Param String name) {
	int limit = parseLimit(ctx, 20);
	ctx.json(Map.of("memories", service.getAgentMemoriesRecent(name, limit)));
	}

	@Get("/agents/{name}/memories/{index}")
	public void getAgentMemoryByIndex(Context ctx, @Param String name, @Param int index) {
	ctx.json(service.getAgentMemoryByIndex(name, index));
	}

	@Get("/agents/{name}/schedule")
	public void getAgentSchedule(Context ctx, @Param String name) {
	List<ScheduleResponse> schedule = service.getAgentSchedule(name);
	Map<String, Object> byType = new HashMap<>();
	byType.put("shortTerm", schedule.stream().filter(item -> "SHORT_TERM".equals(item.getType())).toList());
	byType.put("midTerm", schedule.stream().filter(item -> "MID_TERM".equals(item.getType())).toList());
	byType.put("longTerm", schedule.stream().filter(item -> "LONG_TERM".equals(item.getType())).toList());
	ctx.json(Map.of("agentName", name, "items", schedule, "byType", byType));
	}

    @Post("/agents/{name}/ask")
    public void askAgentQuestion(Context ctx) {
	AskQuestionRequest request = ctx
	    .bodyValidator(AskQuestionRequest.class)
	    .check((req) -> exists(req.getQuestion()), "{question} cannot be blank")
	    .get();

	String res = service.askQuestion(ctx.pathParam("name"), request.getQuestion());

	ctx.json(Map.of("answer", res));
    }

    @Post("/agents")
    public void createAgent(Context ctx) {
	LOG.info("[SERVER] POST /agents called");
	String raw = ctx.body();
	LOG.info("[SERVER] raw POST /agents body: {}", raw);
	try {
	    CreateAgentRequest request = ctx
		.bodyValidator(CreateAgentRequest.class)
		.check((req) -> exists(req.getName()), "{name} cannot be missing")
		.check((req) -> exists(req.getActivity()), "{activity} cannot be missing")
		.check((req) -> exists(req.getLocation()), "{location} cannot be missing")
		.check((req) -> req.getMemories() != null && !req.getMemories().isEmpty(), "{memories} cannot be missing")
		.get();

	    LOG.info("[SERVER] Parsed agent request: name={}, loc={}, memoriesCount={}",
		request.getName(), request.getLocation(),
		request.getMemories() == null ? 0 : request.getMemories().size());
	    service.createAgent(request);
	    LOG.info("[SERVER] Agent created successfully: {}", request.getName());
	    ctx.json(Map.of("success", true));
	} catch (Exception e) {
	    LOG.error("[SERVER] Error creating agent: {}", e.getMessage(), e);
	    throw e;
	}
    }

    @Post("/locations")
    public void createLocation(Context ctx) {
	LOG.info("[SERVER] POST /locations called");
	String raw = ctx.body();
	LOG.info("[SERVER] raw POST /locations body: {}", raw);
	try {
	    CreateLocationRequest request = ctx
		.bodyValidator(CreateLocationRequest.class)
		.check((req) -> exists(req.getName()), "{name} cannot be missing")
		.get();
	    
	    LOG.info("[SERVER] Creating location: {} (type: {})", request.getName(), request.getType());
	    service.createLocation(request);
	    ctx.json(Map.of("success", true));
	} catch (Exception e) {
	    LOG.error("[SERVER] Error creating location", e);
	    throw e;
	}
    }

    @Post("/player")
    public void createPlayer(Context ctx) {
	LOG.info("[SERVER] POST /player called");
	try {
	    CreatePlayerRequest request = ctx
		.bodyValidator(CreatePlayerRequest.class)
		.check((req) -> exists(req.getName()), "{name} cannot be missing")
		.check((req) -> exists(req.getLocation()), "{location} cannot be missing")
		.get();

	    LOG.info("[SERVER] Creating player: {} at location: {}", request.getName(), request.getLocation());
	    service.createPlayer(request);
	    LOG.info("[SERVER] Player created successfully: {}", request.getName());
	    ctx.json(Map.of("success", true));
	} catch (Exception e) {
	    LOG.error("[SERVER] Error creating player: {}", e.getMessage(), e);
	    throw e;
	}
    }

    @Get("/player/{name}")
    public void getPlayer(Context ctx, @Param String name) {
	PlayerStateResponse res = service.getPlayerState(name);
	ctx.json(res);
    }

    @Post("/player/actions")
    public void enqueuePlayerAction(Context ctx) {
	PlayerActionRequest request = ctx.bodyAsClass(PlayerActionRequest.class);

	if (request.getPlayerId() == null || request.getPlayerId().isEmpty()) {
	    ctx.status(400).json(Map.of("success", false, "error", "playerId cannot be blank"));
	    return;
	}

	try {
	    service.enqueuePlayerAction(request);
	    ctx.json(Map.of("success", true, "message", "Action enqueued"));
	} catch (Exception e) {
	    ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
	}
    }

    @Get("/player/{name}/actions")
    public void getPlayerActionHistory(Context ctx, @Param String name) {
	int limit = parseLimit(ctx, 20);
	ctx.json(Map.of("actions", service.getPlayerActionHistory(name, limit)));
    }

    @Post("/locations/{name}")
    public void changeLocationState(Context ctx) throws JsonMappingException, JsonProcessingException {
	String location = ctx.pathParam("name");
	ObjectMapper objectMapper = new ObjectMapper();

	JsonNode rootNode = objectMapper.readTree(ctx.body());
	String state = rootNode.get("state").asText();

	service.setState(location, state);
	ctx.json(Map.of("success", true));
    }

    @Get("/locations")
    public void getLocations(Context ctx) {
	List<LocationStateResponse> request = service.getAllLocations();

	ctx.json(Map.of("locations", request));
    }

    @Post("/memories")
    public void saveAgentMemory(Context ctx) {
	CreateMemoryRequest request = ctx.bodyAsClass(CreateMemoryRequest.class);
	service.createMemory(request);

	ctx.json(Map.of("success", true));
    }

    @Post("/actions")
    public void enqueueAction(Context ctx) {
	PlayerActionRequest request = ctx.bodyAsClass(PlayerActionRequest.class);

	if (request.getPlayerId() == null || request.getPlayerId().isEmpty()) {
	    ctx.status(400).json(Map.of("success", false, "error", "playerId cannot be blank"));
	    return;
	}

	try {
	    service.enqueuePlayerAction(request);
	    ctx.json(Map.of("success", true, "message", "Action enqueued"));
	} catch (Exception e) {
	    ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
	}
    }

	@Post("/objects/types/{type}")
	public void defineObjectType(Context ctx, @Param String type) {
	ObjectTypeDefinitionRequest request = ctx.bodyAsClass(ObjectTypeDefinitionRequest.class);
	service.defineObjectType(type, request.getProperties());
	ctx.json(Map.of("success", true, "type", type));
	}

	@Get("/objects/types")
	public void getObjectTypes(Context ctx) {
	ctx.json(Map.of("types", service.getObjectTypes()));
	}

	@Get("/objects/types/{type}")
	public void getObjectType(Context ctx, @Param String type) {
	ctx.json(service.getObjectType(type));
	}

	@Post("/objects/{id}")
	public void upsertObjectInstance(Context ctx, @Param String id) {
	ObjectInstanceUpsertRequest request = ctx.bodyAsClass(ObjectInstanceUpsertRequest.class);
	ctx.json(service.upsertObjectInstance(id, request));
	}

	@Get("/objects/{id}")
	public void getObjectInstance(Context ctx, @Param String id) {
	ctx.json(service.getObjectInstance(id));
	}

	@Get("/objects")
	public void getObjectInstances(Context ctx) {
	ctx.json(Map.of("objects", service.getAllObjectInstances()));
	}

    @Post("/turn")
    public void processTurn(Context ctx) {
	try {
	    RuntimeOrchestrationRequest runtimeRequest = ctx.body().isBlank() ? new RuntimeOrchestrationRequest() : ctx.bodyAsClass(RuntimeOrchestrationRequest.class);
	    PlayerActionResponse response = service.processNextAction(runtimeRequest);
	    List<AgentStateResponse> agents = service.getAgents();
	    List<LocationStateResponse> locations = service.getAllLocations();
	    List<ConversationResponse> conversations = service.getConversations();

	    ctx.json(Map.of("actionResult", response, "runtimeRequest", runtimeRequest, "agents", agents, "location_states", locations, "conversations", conversations));
	} catch (Exception e) {
	    ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
	}
    }

    @Post("/state")
    public void updateState(Context ctx) {
	service.updateState();
	List<AgentStateResponse> agents = service.getAgents();
	List<LocationStateResponse> locations = service.getAllLocations();
	List<ConversationResponse> conversations = service.getConversations();

	ctx.json(Map.of("agents", agents, "location_states", locations, "conversations", conversations));
    }

    @Get("/state")
    public void getState(Context ctx) {
	List<AgentStateResponse> agents = service.getAgents();
	List<LocationStateResponse> locations = service.getAllLocations();
	List<ConversationResponse> conversations = service.getConversations();

	ctx.json(Map.of("agents", agents, "location_states", locations, "conversations", conversations));
    }

    @Get("/state/delta")
    public void getStateDelta(Context ctx) {
	List<AgentDeltaStateResponse> agentDeltas = service.getAgentDeltas();
	List<LocationStateResponse> locations = service.getAllLocations();

	ctx.json(Map.of("agents", agentDeltas, "location_states", locations));
    }

    @Get("/llm/policy")
    public void getLlmPolicy(Context ctx) {
	ctx.json(service.getLlmCallPolicy());
    }

    @Get("/llm/latency-budget")
    public void getLatencyBudget(Context ctx) {
	ctx.json(Map.of("rows", service.getLatencyBudgetTable()));
    }

	@Post("/runtime/orchestrate")
	public void orchestrateRuntime(Context ctx) {
	RuntimeOrchestrationRequest request = ctx.body().isBlank() ? new RuntimeOrchestrationRequest() : ctx.bodyAsClass(RuntimeOrchestrationRequest.class);
	ctx.json(service.orchestrateRuntime(request));
	}

	@Get("/runtime/pending-events")
	public void getPendingRuntimeEvents(Context ctx) {
	ctx.json(Map.of("pendingReactiveEvents", service.getPendingReactiveEventCount()));
	}

    @Get("/{x}/{y}")
    public void getCoordinateSnapshot(Context ctx, @Param String x, @Param String y) {
	try {
	    double xCoord = Double.parseDouble(x);
	    double yCoord = Double.parseDouble(y);
	    ctx.json(service.getCoordinateSnapshot(xCoord, yCoord));
	} catch (NumberFormatException e) {
	    ctx.status(400).json(Map.of("success", false, "error", "x and y must be numeric"));
	}
    }

    @Get("/{x}/{y}/location")
    public void getLocationAtCoordinate(Context ctx, @Param String x, @Param String y) {
	try {
	    double xCoord = Double.parseDouble(x);
	    double yCoord = Double.parseDouble(y);
	    ctx.json(service.getLocationAtCoordinate(xCoord, yCoord));
	} catch (NumberFormatException e) {
	    ctx.status(400).json(Map.of("success", false, "error", "x and y must be numeric"));
	}
    }

    @Get("/{x}/{y}/objects")
    public void getObjectsAtCoordinate(Context ctx, @Param String x, @Param String y) {
	try {
	    double xCoord = Double.parseDouble(x);
	    double yCoord = Double.parseDouble(y);
	    ctx.json(Map.of("objects", service.getObjectsAtCoordinate(xCoord, yCoord)));
	} catch (NumberFormatException e) {
	    ctx.status(400).json(Map.of("success", false, "error", "x and y must be numeric"));
	}
    }

    @Post("/timestep")
    public void setTimestep(Context ctx) {
	SetTimestepRequest request = ctx.bodyAsClass(SetTimestepRequest.class);
	int minutes = Integer.valueOf(request.getNumOfMinutes());
	SimulationTime.setStep(Duration.ofMinutes(minutes));
	ctx.json(Map.of("success", true, "message", "Timestep updated to " + minutes + " per update"));
    }

    private int parseLimit(Context ctx, int defaultValue) {
	String rawLimit = ctx.queryParam("limit");
	if (rawLimit == null || rawLimit.isBlank()) {
	    return defaultValue;
	}
	try {
	    return Integer.parseInt(rawLimit);
	} catch (NumberFormatException e) {
	    return defaultValue;
	}
    }
}
