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
import java.util.concurrent.atomic.AtomicReference;
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
import io.github.nickm980.smallville.config.SmallvilleConfig;
import io.github.nickm980.smallville.prompts.TemplateEngine;
import io.github.nickm980.smallville.update.UpdateService;

public class SimulationService {

    private Logger LOG = LoggerFactory.getLogger(SimulationService.class);
	private static final double TILE_SIZE = 32.0;
	private static final String DEFAULT_TRACKED_AGENT_NAME = "Alex";
	private static final long TRACK_HEARTBEAT_MINUTES = 5;
	private static final double TRACE_POSITION_EPSILON = 0.1;
	private static final String AGENTIC_OBJECTIVE_SOCIAL_CONTACT = "social_contact";
	private static final int AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE = 20;
	private static final int AGENTIC_INITIATE_TILE_DISTANCE = 5;
	private static final int AGENTIC_DISENGAGE_TILE_DISTANCE = 4;
	private static final int OBJECT_WITNESS_TILE_DISTANCE = 4;
	private static final int DEFAULT_INTERACTION_TILE_DISTANCE = 4;
	private static final int AGENTIC_MAX_DEFERRED_TURNS = 4;
	private static final long AGENTIC_SOCIAL_COOLDOWN_MINUTES = 20;
	private static final long AGENTIC_LAST_SEEN_TTL_MINUTES = 90;
	private static final double AGENTIC_MIN_GOAL_PRIORITY = 0.20;
	private static final int MAX_SOCIAL_EPISODES_PER_TARGET = 12;
	private static final int MAX_CONVERSATION_TURNS_PER_PAIR = 60;
	private static final long SOCIAL_APPRAISAL_TTL_MINUTES = 20;
	private static final int DEFAULT_PLAYER_AFFORDANCE_TILE_RADIUS = 4;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile String trackedAgentName = DEFAULT_TRACKED_AGENT_NAME;

    private final ModelMapper mapper;
    private final UpdateService prompts;
    private final World world;
    private final LLM llm;
    private int progress;
    private final Queue<PlayerActionRequest> actionQueue = new ConcurrentLinkedQueue<>();
	private static final int MAX_ACTION_HISTORY = 100;
	private static final int MAX_REACTIVE_EVENTS = 30;
	private static final int MAX_COMMITTED_ACTIONS = 20;

	// ── Chronicle (Item 8 — append-only world event log) ─────────────────────
	private final java.util.concurrent.CopyOnWriteArrayList<ChronicleEvent> chronicle =
		new java.util.concurrent.CopyOnWriteArrayList<>();
	private static final int MAX_CHRONICLE_SIZE = 500;
	private final java.util.concurrent.atomic.AtomicInteger turnCounter = new java.util.concurrent.atomic.AtomicInteger(0);

	// ── Async cognition (Item 7 — PlanQueue) ─────────────────────────────────
	// LLM planning calls run in a thread pool; the main simulation thread only
	// APPLIES their results (via pendingCognitionApplies) at the start of each
	// orchestration pass, keeping agent mutation single-threaded.
	private final java.util.concurrent.ExecutorService cognitionExecutor =
		java.util.concurrent.Executors.newFixedThreadPool(
			Math.max(2, Runtime.getRuntime().availableProcessors() - 1));
	/** Agents whose LLM cognition job is currently running in the background. */
	private final java.util.Set<String> cognitionInFlight =
		java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
	/** Callbacks produced by worker threads, consumed by the main thread before each turn. */
	private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> pendingCognitionApplies =
		new java.util.concurrent.ConcurrentLinkedQueue<>();

	// ── Global simulation clock ───────────────────────────────────────────────
	private java.util.concurrent.ScheduledExecutorService simulationClock;
	private volatile boolean simulationPaused = false;
	private List<LocationStateResponse> cachedLocations = null;
	private List<Location> cachedLocationEntities = null;
	private final Map<String, Deque<PlayerActionRequest>> actionHistoryByPlayer = new ConcurrentHashMap<>();
	private final Map<String, Map<String, Object>> objectTypeDefinitions = new ConcurrentHashMap<>();
	private final Map<String, WorldObjectInstance> objectInstances = new ConcurrentHashMap<>();
	private final List<Map<String, Object>> pendingNpcSpeeches = new java.util.concurrent.CopyOnWriteArrayList<>();
	private final List<Map<String, Object>> pendingNpcActions  = new java.util.concurrent.CopyOnWriteArrayList<>();
	private volatile boolean npcObjectsDirty = false;
	private final Map<String, LinkedHashSet<String>> inventoryByAgent = new ConcurrentHashMap<>();
	private final Map<String, RuntimeAgentState> runtimeStateByAgent = new ConcurrentHashMap<>();
	private final Map<String, Deque<ReactiveEvent>> reactiveEventsByAgent = new ConcurrentHashMap<>();
	private final Map<String, Deque<CommittedAction>> committedActionsByAgent = new ConcurrentHashMap<>();
	private final Map<String, AgenticRuntimeState> agenticStateByAgent = new ConcurrentHashMap<>();
	private final Map<String, Map<String, Deque<SocialEpisode>>> socialEpisodesByAgent = new ConcurrentHashMap<>();
	private final Map<String, Deque<ConversationTurn>> conversationTurnsByPair = new ConcurrentHashMap<>();

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

	private static class SocialEpisode {
		private String target;
		private String outcome;
		private String topic;
		private String playerReply;
		private String summary;
		private LocalDateTime createdAt;
	}

	private static class ConversationTurn {
		private String speaker;
		private String listener;
		private String text;
		private LocalDateTime createdAt;
	}

	private static class InstinctDecision {
		private String action;
		private String activity;
		private String targetLocation;
		private double stressDelta;
		private String reason;
	}

	/** Generalized agentic state-machine phases.
	 *  IDLE            – no active goal; perceive + evaluate each turn.
	 *  MOVING_TO_TARGET – goal chosen, pathing toward target (mobile or stationary).
	 *  AWAITING_OUTCOME – interaction executed; waiting for a response/result.
	 *  COOLDOWN         – post-interaction rest before accepting a new goal.
	 */
	private enum AgenticPhase {
		IDLE,
		MOVING_TO_TARGET,
		AWAITING_OUTCOME,
		COOLDOWN
	}

	private enum SocialReplyKind {
		POSITIVE,
		NEUTRAL,
		REJECTING,
		HOSTILE
	}

	/** A single entity visible within the agent's perception radius. */
	private static class PerceptionEntry {
		String entityId;       // full name for agents, object-id for objects
		String entityType;     // "player" | "agent" | "object"
		double x, y;           // world position at perception time
		double distance;       // from perceiving agent
		String locationPath;   // entity's current location full-path
		boolean isMobile;      // true for agents/players
	}

	/** Snapshot of everything the agent can perceive this turn. */
	private static class PerceptionSnapshot {
		List<PerceptionEntry> visible = new ArrayList<>();
		String agentLocationPath;
	}

	/** An active goal: what the agent wants to do, to whom/what, and why. */
	private static class AgenticGoal {
		String type;             // "SOCIAL_CONTACT" – extensible for future goal types
		String targetId;         // entity name or object id
		String targetType;       // "player" | "agent" | "object"
		boolean targetIsMobile;  // if true, re-resolve target location every turn
		double snapshotX;        // cached target world-position (refreshed each turn for mobile)
		double snapshotY;
		String snapshotLocation; // cached target location path
		String topic;            // conversation topic / interaction payload
		String opener;           // pre-generated opening line (set at goal-commit time, before approach)
		String description;      // human-readable goal summary
		double priority;         // 0-1 salience score used to pick the best goal
		String actionType;       // e.g. speak/interact/attack
		String actionDescription;// concrete verb phrase to execute
		String actionFlair;      // action metadata key
	}

	private static class ToolActionCandidate {
		String actionType;
		String actionDescription;
		String actionFlair;
		String targetId;
		String targetType;
		boolean targetIsMobile;
		double targetX;
		double targetY;
		String targetLocation;
		double score;
		String reason;
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
		private AgenticGoal activeGoal;           // current goal; null when IDLE/COOLDOWN
		/** Ordered queue of sub-goals from the current decomposed plan. Populated by
		 *  decomposeIntoSubGoals(); cleared on abandon or rejection. */
		private final Queue<AgenticGoal> goalPlan = new ArrayDeque<>();
		private LocalDateTime phaseUpdatedAt;
		private LocalDateTime cooldownUntil;
		private final Map<String, KnowledgeEntry> knowledge = new ConcurrentHashMap<>();
		// Interaction-phase tracking (reused across goal types that need a reply)
		private boolean chatWindowClosedObserved;
		private boolean pinnedLastTurn;
		private int deferredTurns;
		// History
		private int recentIgnoreCount;
		private double socialFriction; // rises on rebuffs; lowers on positive exchanges
		private double lastInitiativeScore;
		private String lastOutcome;
		private LocalDateTime lastInitiatedAt;
		private LocalDateTime lastRepliedAt;
		private String lastError;
		/**
		 * What this agent believes about each other agent it has observed or heard about.
		 * Keyed by target agent name. Populated by refreshBeliefModels() before each LLM call.
		 * Cleared only on full agent reset; entries are updated in place.
		 */
		final Map<String, io.github.nickm980.smallville.entities.AgentBeliefModel> beliefModels = new ConcurrentHashMap<>();
		/**
		 * Snapshot of each nearby WorldObjectInstance's state from the previous turn.
		 * Used by scanEnvironment() to detect property changes (doors opened/closed, etc.).
		 * Keyed by instanceId.
		 */
		final Map<String, Map<String, Object>> objectStateSnapshot = new ConcurrentHashMap<>();
		/**
		 * Instance IDs of objects whose writing this agent has already read.
		 * Prevents duplicate Observations for the same has_writing content.
		 */
		final Set<String> alreadyReadObjects = ConcurrentHashMap.newKeySet();
	}

    public SimulationService(LLM llm, World world) {
	this.world = world;
	this.llm = llm;
	this.mapper = new ModelMapper();
	this.prompts = new UpdateService(llm, world);
	this.progress = 0;
	seedDefaultObjectTypes();
    }

    public String getLlmModelName() {
        String name = llm.getModelName();
        return name != null ? name : "unknown";
    }

    public List<Map<String, Object>> drainNpcSpeeches() {
        List<Map<String, Object>> result = new ArrayList<>(pendingNpcSpeeches);
        pendingNpcSpeeches.clear();
        return result;
    }

    public List<Map<String, Object>> drainNpcActions() {
        List<Map<String, Object>> result = new ArrayList<>(pendingNpcActions);
        pendingNpcActions.clear();
        return result;
    }

    /** Returns true (and resets the flag) when an NPC action moved or created a world object. */
    public boolean drainNpcObjectsDirty() {
        boolean dirty = npcObjectsDirty;
        npcObjectsDirty = false;
        return dirty;
    }

    // ── Global simulation clock ───────────────────────────────────────────────

    public void startGlobalSimulationClock() {
        int tickMs = SmallvilleConfig.getConfig().getSimulationTickMs();
        simulationClock = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "simulation-clock");
            t.setDaemon(true);
            return t;
        });
        simulationClock.scheduleAtFixedRate(this::tickSimulation,
            tickMs, tickMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        LOG.info("[SimClock] Global simulation clock started — tick every {}ms", tickMs);
    }

    private void tickSimulation() {
        if (simulationPaused) return;
        try {
            SimulationTime.update();
            orchestrateRuntime(new RuntimeOrchestrationRequest(), null);
        } catch (Throwable t) {
            // Catch Throwable (not just Exception) so that Errors don't silently
            // cancel the ScheduledExecutorService future and stop the clock.
            LOG.error("[SimClock] Tick failed: {}", t.getMessage(), t);
        }
    }

    public void pauseSimulation() {
        simulationPaused = true;
        LOG.info("[SimClock] Simulation paused — in-flight LLM calls will complete and be applied");
    }

    public void resumeSimulation() {
        simulationPaused = false;
        LOG.info("[SimClock] Simulation resumed");
    }

    public boolean isSimulationPaused() {
        return simulationPaused;
    }

    // ── Agentic tool loop helpers ─────────────────────────────────────────────

    private AgentTurnContext buildAgentTurnContext(Agent agent, LocalDateTime now) {
        int maxIter = SmallvilleConfig.getConfig().getAgenticMaxIterations();
        long deadlineMs = SmallvilleConfig.getConfig().getAgenticTurnDeadlineMs();
        List<Map<String, Object>> nearby = buildNearbyEntityListForContext(agent);

        String pendingIntent = null;

        // (a) Immediate retaliation/flee goal set by triggerAttackedResponse
        AgenticRuntimeState agState = agenticStateByAgent.get(agent.getFullName());
        if (agState != null && !agState.goalPlan.isEmpty()) {
            AgenticGoal top = agState.goalPlan.peek();
            if (isAggressiveActionType(top.actionType != null ? top.actionType.toLowerCase() : "")) {
                boolean targetNearby = nearby.stream().anyMatch(e -> top.targetId.equals(e.get("id")));
                String healthCtx = " Your health: " + agent.getHealth() + "/100.";
                if (targetNearby) {
                    pendingIntent = "** HIGH PRIORITY: " + top.targetId + " just attacked you." + healthCtx
                        + " They are right here. React as your character demands. **";
                } else {
                    pendingIntent = "** HIGH PRIORITY: " + top.targetId + " attacked you." + healthCtx
                        + " They are not adjacent. Decide what to do. **";
                }
                agState.goalPlan.poll();
            } else if ("move".equals(top.actionType)
                    && top.actionDescription != null
                    && top.actionDescription.startsWith("Fleeing")) {
                String attackerName = top.actionDescription.startsWith("Fleeing from ")
                    ? top.actionDescription.substring("Fleeing from ".length()) : "someone";
                String healthCtx = " Health: " + agent.getHealth() + "/100.";
                pendingIntent = "** HIGH PRIORITY: " + attackerName + " just attacked you." + healthCtx
                    + " Your goal is survival. Choose the best action available to you right now. **";
                agState.goalPlan.poll();
            } else if ("speak".equalsIgnoreCase(top.actionType)
                    && "player".equalsIgnoreCase(top.targetType)) {
                String combatMemories = agent.getMemoryStream().getMemories().stream()
                    .filter(m -> m.getImportance() >= 7)
                    .limit(3)
                    .map(io.github.nickm980.smallville.memory.Memory::getDescription)
                    .collect(Collectors.joining(". "));
                double stress = agent.getStressLevel();
                String stressDesc = stress > 0.7 ? "extremely stressed" : stress > 0.4 ? "shaken" : "tense";
                String healthCtx = "health " + agent.getHealth() + "/100, " + stressDesc;
                boolean playerNearby = nearby.stream().anyMatch(e -> top.targetId.equals(e.get("id")));
                if (playerNearby) {
                    pendingIntent = "** HIGH PRIORITY: " + top.targetId + " attacked you. You are " + healthCtx + "."
                        + (combatMemories.isBlank() ? "" : " What happened: " + combatMemories + ".")
                        + " Express your genuine emotional reaction — cuss them out, threaten them,"
                        + " demand an explanation, beg for mercy, or whatever your character would say."
                        + " Keep it raw and in-character (1-2 sentences)."
                        + " commit_action(verb=\"speak\", target_id=\"" + top.targetId
                        + "\", target_type=\"player\", payload=\"<your words>\"). **";
                } else {
                    pendingIntent = "** HIGH PRIORITY: " + top.targetId + " attacked you (health " + agent.getHealth() + "/100)."
                        + " They are not next to you yet. Update your plan and wait for them. **";
                }
                agState.goalPlan.poll();
            }
        }

        // (b) Hostile-on-sight: attacked within last 20 turns, attacker now nearby
        if (pendingIntent == null) {
            int currentTurn = turnCounter.get();
            for (io.github.nickm980.smallville.entities.EpistemicMemory.ObservedEvent evt
                    : agent.getEpistemicMemory().recentObserved(20)) {
                if (isAggressiveActionType(evt.verb)
                        && agent.getFullName().equals(evt.targetId)
                        && (currentTurn - evt.turnNumber) <= 20) {
                    boolean attackerNearby = nearby.stream()
                        .anyMatch(e -> evt.actorId.equals(e.get("id")));
                    if (attackerNearby) {
                        if (agent.getAggression() >= agent.getFearfulness()) {
                            pendingIntent = "** WARNING: " + evt.actorId
                                + " attacked you " + (currentTurn - evt.turnNumber)
                                + " turns ago and is standing nearby. "
                                + "Your aggression drives you to confront them. **";
                        } else {
                            pendingIntent = "** WARNING: " + evt.actorId
                                + " attacked you recently and is nearby. "
                                + "You are frightened — consider fleeing or keeping distance. **";
                        }
                        break;
                    }
                }
            }
        }

        // (c) Low-priority food/healing intent — inject only when not already occupied by combat
        if (pendingIntent == null) {
            int hp = agent.getHealth();
            // Find consumable in inventory first
            InventoryItem heldFood = agent.getInventory().values().stream()
                .filter(item -> {
                    WorldObjectInstance obj = objectInstances.get(item.getId());
                    return obj != null && obj.getProperties() != null
                        && Boolean.TRUE.equals(obj.getProperties().get("consumable"))
                        && !Boolean.TRUE.equals(obj.getProperties().get("is_trash"));
                }).findFirst().orElse(null);

            if (hp < 50) {
                if (heldFood != null) {
                    pendingIntent = "** CRITICAL: Health at " + hp + "/100. You are carrying "
                        + heldFood.getDisplayName() + " (id=" + heldFood.getId() + ")."
                        + " commit_action(verb=\"use\", target_id=\"" + heldFood.getId()
                        + "\", target_type=\"object\") RIGHT NOW to eat/drink it and recover HP. **";
                } else {
                    WorldObjectInstance nearestFood = findNearestFoodItem(agent);
                    if (nearestFood != null) {
                        int foodTileX = (int)(nearestFood.getX() / TILE_SIZE);
                        int foodTileY = (int)(nearestFood.getY() / TILE_SIZE);
                        int myTileX = (int)(agent.getX() / TILE_SIZE);
                        int myTileY = (int)(agent.getY() / TILE_SIZE);
                        int dist = Math.abs(foodTileX - myTileX) + Math.abs(foodTileY - myTileY);
                        String foodLoc = nearestFood.getLocation() != null ? nearestFood.getLocation() : "";
                        if (dist <= 4) {
                            pendingIntent = "** CRITICAL: Health at " + hp + "/100. "
                                + nearestFood.getName() + " (id=" + nearestFood.getInstanceId() + ") is RIGHT HERE. "
                                + "commit_action(verb=\"carry\", target_id=\"" + nearestFood.getInstanceId()
                                + "\", target_type=\"object\") to pick it up NOW. **";
                        } else {
                            pendingIntent = "** CRITICAL: Health at " + hp + "/100. You need food urgently. "
                                + "Nearest food: " + nearestFood.getName() + " (id=" + nearestFood.getInstanceId()
                                + ") at tile (" + foodTileX + "," + foodTileY + ")"
                                + (foodLoc.isBlank() ? "" : " in location \"" + foodLoc + "\"")
                                + " — " + dist + " tiles away. "
                                + "Step 1: call update_plan(activity=\"going to get food\""
                                + (foodLoc.isBlank() ? "" : ", target_location=\"" + foodLoc + "\"")
                                + "). Step 2: commit_action(verb=\"wait\", target_id=\"self\", target_type=\"agent\"). "
                                + "You will automatically move toward food. **";
                        }
                    } else {
                        pendingIntent = "** CRITICAL: Health at " + hp + "/100. Head to the market area to find food. **";
                    }
                }
            } else if (hp < 80 && heldFood != null) {
                pendingIntent = "** You are injured (" + hp + "/100) and carrying "
                    + heldFood.getDisplayName() + " (id=" + heldFood.getId() + ")."
                    + " commit_action(verb=\"use\", target_id=\"" + heldFood.getId()
                    + "\", target_type=\"object\") to eat it now. **";
            }
        }

        return new AgentTurnContext(
            agent,
            agent.getLegalActions() != null ? agent.getLegalActions() : new ArrayList<>(),
            agent.getBeliefSummary() != null ? agent.getBeliefSummary() : "",
            nearby,
            turnCounter.get(),
            now,
            maxIter,
            deadlineMs,
            pendingIntent
        );
    }

    private List<Map<String, Object>> buildNearbyEntityListForContext(Agent agent) {
        double perceptionRange = AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE * TILE_SIZE;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Agent other : world.getAgents()) {
            if (other == agent) continue;
            double dx = other.getX() - agent.getX();
            double dy = other.getY() - agent.getY();
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist <= perceptionRange) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", other.getFullName());
                entry.put("type", other instanceof Player ? "player" : "agent");
                entry.put("distance_tiles", Math.round(dist / TILE_SIZE * 10.0) / 10.0);
                entry.put("current_activity", other.getCurrentActivity());
                result.add(entry);
            }
        }
        for (WorldObjectInstance obj : objectInstances.values()) {
            double dx = obj.getX() - agent.getX();
            double dy = obj.getY() - agent.getY();
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist <= perceptionRange) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", obj.getId());
                entry.put("type", "object");
                entry.put("type_id", obj.getTypeId());
                entry.put("distance_tiles", Math.round(dist / TILE_SIZE * 10.0) / 10.0);
                result.add(entry);
            }
        }
        return result;
    }

    private void applyTurnResult(Agent agent, TurnResult result) {
        if (result == null) {
            LOG.warn("[TurnResult] null result for {} — no-op [FALLBACK]", agent.getFullName());
            return;
        }
        if (result.wasIterationFallback) {
            LOG.warn("[TurnResult] [FALLBACK] {} exhausted iterations/deadline — waiting this turn",
                agent.getFullName());
            agent.setCurrentActivity("observing");
            return;
        }
        if (!result.committed) {
            LOG.info("[TurnResult] [FALLBACK] {} action rejected ({}→{}): {}",
                agent.getFullName(), result.verb, result.targetId, result.rejectExplanation);
            agent.setCurrentActivity("reconsidering");
            agent.getMemoryStream().add(new io.github.nickm980.smallville.memory.Observation(
                "I tried to " + result.verb + " " + result.targetId + " but was blocked: " + result.rejectExplanation));
            return;
        }

        // Committed and permitted
        LOG.info("[TurnResult] {} COMMITTED: {} {} {}", agent.getFullName(), result.verb, result.targetType, result.targetId);
        String activity = buildActivityFromCommit(result.verb, result.targetId, result.payload);
        agent.setCurrentActivity(activity);
        agent.getMemoryStream().add(new io.github.nickm980.smallville.memory.Observation(
            "I " + result.verb + " " + result.targetId +
            (result.payload != null && !result.payload.isBlank() ? ": \"" + result.payload + "\"" : "")));

        // Clear pending movement so advanceAgentMovement doesn't also act this same turn.
        // The tool loop manages movement via update_plan; the old action queue is redundant here.
        if (!"wait".equalsIgnoreCase(result.verb)) {
            agent.clearActions();
            agent.setTargetLocation(null);
        }

        // Verb-specific state mutations via WorldStateMutator (Tier 4)
        WorldAction.TargetType targetType = parseTargetTypeString(result.targetType);
        String mutationNarrative = new WorldStateMutator(world, objectInstances)
            .apply(agent, result.verb, result.targetId, targetType, result.payload, turnCounter.get());
        if (mutationNarrative != null && !mutationNarrative.isBlank()) {
            npcObjectsDirty = true; // flag Godot to refresh world objects on next delta poll
            // Overwrite the generic memory entry with the richer mutation narrative
            agent.getMemoryStream().add(
                new io.github.nickm980.smallville.memory.Observation("I " + mutationNarrative));
        }

        // Combat verbs: re-validate range at apply-time (decision was async; target may have moved)
        if (isAggressiveActionType(result.verb)) {
            Agent combatTarget = world.getAgent(result.targetId).orElse(null);
            if (combatTarget != null) {
                String verbLower = result.verb.toLowerCase();
                int distNow = tileManhattanDistance(agent.getX(), agent.getY(), combatTarget.getX(), combatTarget.getY());
                boolean inRange;
                if ("punch".equals(verbLower) || "kick".equals(verbLower)) {
                    inRange = distNow <= 2;
                } else if ("tackle".equals(verbLower)) {
                    inRange = distNow >= 2 && distNow <= 3;
                } else {
                    inRange = distNow <= 4; // attack/generic: slightly more lenient
                }
                if (!inRange) {
                    LOG.info("[TurnResult] {} {} missed {} — target moved since decision (dist={}t)",
                        agent.getFullName(), result.verb, result.targetId, distNow);
                    agent.setCurrentActivity("missed " + result.targetId);
                    agent.getMemoryStream().add(new io.github.nickm980.smallville.memory.Observation(
                        "I tried to " + result.verb + " " + result.targetId + " but they were too far away."));
                    return;
                }
                // Tackle: snap acting NPC to closest adjacent tile of target (mirrors player tackle)
                if ("tackle".equals(verbLower)) {
                    double[][] adjacentTiles = {
                        {combatTarget.getX() + TILE_SIZE, combatTarget.getY()},
                        {combatTarget.getX() - TILE_SIZE, combatTarget.getY()},
                        {combatTarget.getX(), combatTarget.getY() + TILE_SIZE},
                        {combatTarget.getX(), combatTarget.getY() - TILE_SIZE}
                    };
                    double bestDist = Double.MAX_VALUE;
                    double newX = agent.getX(), newY = agent.getY();
                    for (double[] tile : adjacentTiles) {
                        double cx = snapToTile(tile[0]);
                        double cy = snapToTile(tile[1]);
                        double d = Math.abs(cx - agent.getX()) + Math.abs(cy - agent.getY());
                        if (d < bestDist) {
                            bestDist = d;
                            newX = cx;
                            newY = cy;
                        }
                    }
                    agent.setPosition(newX, newY);
                }
                // Apply damage and stress (was previously missing — hits logged but no health loss)
                int damage = computeVerbDamage(agent, verbLower);
                combatTarget.applyDamage(damage);
                combatTarget.applyStressChange(0.15);
                agent.applyStressChange(0.05);
                String hitDesc = buildCombatHitDescription(agent.getFullName(), verbLower, damage);
                io.github.nickm980.smallville.memory.Observation hitObs =
                    new io.github.nickm980.smallville.memory.Observation(hitDesc);
                hitObs.setImportance(9);
                combatTarget.getMemoryStream().add(hitObs);

                String narrative = agent.getFullName() + " attacked " + result.targetId + " using " + result.verb
                    + " for " + damage + " damage";
                ChronicleEvent combatEvt = appendChronicle(
                    agent.getFullName(), "agent", result.verb,
                    result.targetId, result.targetType != null ? result.targetType : "agent",
                    narrative, agent.getX(), agent.getY(),
                    combatTarget.getX(), combatTarget.getY());
                combatTarget.getEpistemicMemory().ingestObserved(combatEvt);
                triggerAttackedResponse(combatTarget, agent.getFullName(), agent);
                applyKnockback(agent, combatTarget);
            }
        } else {
            appendChronicle(agent.getFullName(), "agent", result.verb,
                result.targetId,
                result.targetType != null ? result.targetType : "object",
                result.payload,
                agent.getX(), agent.getY(),
                agent.getX(), agent.getY());
        }
        // Capture NPC speech directed at the player for client display
        if ("speak".equalsIgnoreCase(result.verb) && result.payload != null && !result.payload.isBlank()) {
            Agent player = findPrimaryPlayer();
            if (player != null && (player.getFullName().equals(result.targetId)
                    || "player".equalsIgnoreCase(result.targetType))) {
                Map<String, Object> speech = new java.util.LinkedHashMap<>();
                speech.put("speaker", agent.getFullName());
                speech.put("text", result.payload);
                pendingNpcSpeeches.add(speech);
                LOG.info("[NpcSpeech] {} speaks to player: {}", agent.getFullName(), result.payload.substring(0, Math.min(60, result.payload.length())));
            }
        }
        // Free-action blurb: broadcast alongside any verb (not just speak)
        if (result.speech != null && !result.speech.isBlank() && !"speak".equalsIgnoreCase(result.verb)) {
            Map<String, Object> blurb = new java.util.LinkedHashMap<>();
            blurb.put("speaker", agent.getFullName());
            blurb.put("text", result.speech);
            pendingNpcSpeeches.add(blurb);
            LOG.info("[NpcBlurb] {} says: {}", agent.getFullName(), result.speech.substring(0, Math.min(60, result.speech.length())));
        }
        // Log all non-movement, non-wait NPC committed actions to the action log.
        // This includes object interactions (carry, use, place_object, open, etc.) and
        // combat/give regardless of whether the target is the player.
        if (!"wait".equalsIgnoreCase(result.verb) && !"observe".equalsIgnoreCase(result.verb)) {
            String narrative = mutationNarrative != null && !mutationNarrative.isBlank()
                ? mutationNarrative : result.verb + " " + result.targetId;
            // For speech targeted at player, narrative is already added via pendingNpcSpeeches
            if (!"speak".equalsIgnoreCase(result.verb)) {
                Map<String, Object> npcAction = new java.util.LinkedHashMap<>();
                npcAction.put("actor", agent.getFullName());
                npcAction.put("verb", result.verb);
                npcAction.put("narrative", narrative);
                npcAction.put("ax", (int)(agent.getX() / TILE_SIZE));
                npcAction.put("ay", (int)(agent.getY() / TILE_SIZE));
                pendingNpcActions.add(npcAction);
            }
        }
    }

    private String buildActivityFromCommit(String verb, String targetId, String payload) {
        return switch (verb) {
            case "speak"        -> "talking to " + targetId;
            case "carry"        -> "carrying " + targetId;
            case "give"         -> "giving " + targetId + " away";
            case "open"         -> "opening " + targetId;
            case "close"        -> "closing " + targetId;
            case "write"        -> "writing on " + targetId;
            case "observe"      -> "observing " + targetId;
            case "sit"          -> "sitting at " + targetId;
            case "inspect"      -> "inspecting " + targetId;
            case "place_object" -> "placing object on " + targetId;
            case "unlock"       -> "unlocking " + targetId;
            case "lock"         -> "locking " + targetId;
            case "use"          -> "using " + targetId;
            case "wait"         -> "waiting";
            default             -> verb + " " + targetId;
        };
    }

    private WorldAction.TargetType parseTargetTypeString(String s) {
        if (s == null) return WorldAction.TargetType.OBJECT;
        switch (s.toLowerCase()) {
            case "agent":  return WorldAction.TargetType.AGENT;
            case "player": return WorldAction.TargetType.PLAYER;
            default:       return WorldAction.TargetType.OBJECT;
        }
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
	    injectLegalActions(agent);
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
		if (state == null || state.activeGoal == null || state.activeGoal.targetId == null
				|| state.activeGoal.targetId.isBlank()) {
			return null;
		}
		// Only expose object-type targets; agent/player targets are not world objects
		if ("object".equals(state.activeGoal.targetType)) {
			return state.activeGoal.targetId;
		}
		return null;
	}

	public Map<String, Object> getAgentPositionSnapshot(String name) {
		Agent agent = world.getAgent(name).orElseThrow(() -> new AgentNotFoundException(name));
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("kind", agent instanceof Player ? "player" : "agent");
		response.put("id", agent.getFullName());
		response.put("location", agent.getLocation() == null ? null : agent.getLocation().getFullPath());
		response.put("x", agent.getX());
		response.put("y", agent.getY());
		response.put("tileX", toTile(agent.getX()));
		response.put("tileY", toTile(agent.getY()));
		return response;
	}

	public Map<String, Object> getObjectPositionSnapshot(String objectId) {
		WorldObjectInstance instance = objectInstances.get(objectId);
		if (instance == null) {
			throw new SmallvilleException("Unknown object id: " + objectId);
		}
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("kind", "object");
		response.put("id", instance.getId());
		response.put("name", instance.getName());
		response.put("location", instance.getLocation());
		response.put("x", instance.getX());
		response.put("y", instance.getY());
		response.put("tileX", toTile(instance.getX()));
		response.put("tileY", toTile(instance.getY()));
		if (instance.getProperties() != null) {
			Object heldBy = instance.getProperties().get("heldBy");
			if (heldBy != null && !String.valueOf(heldBy).isBlank()) {
				response.put("heldBy", String.valueOf(heldBy));
			}
		}
		return response;
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

	private void seedInitialSpatialMemory(Agent agent) {
		if (agent == null) {
			return;
		}
		String snapshot = buildWorldSpatialSnapshot();
		if (snapshot.isBlank()) {
			return;
		}
		agent.getMemoryStream().add(new Observation(snapshot));
	}

	private String buildWorldSpatialSnapshot() {
		List<String> locationsView = new ArrayList<>();
		for (Location location : getLocationsCached()) {
			locationsView.add(location.getFullPath()
				+ "[(" + toTile(location.getMinX()) + "," + toTile(location.getMinY()) + ")"
				+ "..(" + toTile(location.getMaxX()) + "," + toTile(location.getMaxY()) + ")]"
				+ " type=" + location.getType());
		}

		List<String> blockedTiles = new ArrayList<>();
		List<String> objectsView = new ArrayList<>();
		for (WorldObjectInstance object : objectInstances.values()) {
			if (object == null || isObjectHeld(object)) {
				continue;
			}
			String tile = "(" + toTile(object.getX()) + "," + toTile(object.getY()) + ")";
			String location = object.getLocation() == null ? "unknown" : object.getLocation();
			objectsView.add(object.getId() + "@" + tile + "[" + location + "]");
			if (!asBoolean(object.getProperties() == null ? null : object.getProperties().get("walkable"), true)) {
				blockedTiles.add(tile);
			}
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Map snapshot (tile coordinates). Locations: ")
			.append(String.join(", ", locationsView));
		if (!blockedTiles.isEmpty()) {
			sb.append(" | blockedTiles=").append(String.join(", ", blockedTiles));
		}
		if (!objectsView.isEmpty()) {
			sb.append(" | objects=").append(String.join(", ", objectsView));
		}
		return sb.toString();
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
	inventoryByAgent.putIfAbsent(player.getFullName(), new LinkedHashSet<>());
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
	response.setInventory(getInventoryArray(player));
	Set<String> playerInvIds = new LinkedHashSet<>(getInventorySet(player));
	player.getInventory().keySet().forEach(playerInvIds::add);
	response.setInventoryObjects(
	    playerInvIds.stream()
		.map(id -> objectInstances.get(id))
		.filter(obj -> obj != null)
		.map(WorldObjectInstance::toMap)
		.collect(Collectors.toList())
	);
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
	    for (ConversationResponse response : mapper.fromConversation(conversation)) {
			if (response == null) {
				continue;
			}
			response.setMessage(sanitizeDialogueText(response.getMessage()));
			if (response.getMessage() == null || response.getMessage().isBlank()) {
				continue;
			}
			result.add(response);
		}
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

		// Sync client-reported NPC positions before executing the player action so that
		// distance checks (punch/kick adjacency, tackle range) use the positions the player sees.
		if (orchestrationRequest != null && orchestrationRequest.getNpcPositions() != null) {
			for (java.util.Map<String, Object> pos : orchestrationRequest.getNpcPositions()) {
				String npcName = (String) pos.get("name");
				if (npcName == null) continue;
				Agent npc = world.getAgent(npcName).orElse(null);
				if (npc == null || npc instanceof Player) continue;
				double nx = pos.containsKey("x") ? ((Number) pos.get("x")).doubleValue() : npc.getX();
				double ny = pos.containsKey("y") ? ((Number) pos.get("y")).doubleValue() : npc.getY();
				npc.setPosition(nx, ny);
			}
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
			orchestrationRequest.setAwarenessRadius(AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE);
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

			res.setPlayerState(fromAgentWithInventory(player));
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
			double currentX = player.getX();
			double currentY = player.getY();

			double deltaX = targetX - currentX;
			double deltaY = targetY - currentY;
			double stepX = currentX;
			double stepY = currentY;
			if (Math.abs(deltaX) >= Math.abs(deltaY)) {
				stepX = currentX + Math.signum(deltaX) * Math.min(TILE_SIZE, Math.abs(deltaX));
			} else {
				stepY = currentY + Math.signum(deltaY) * Math.min(TILE_SIZE, Math.abs(deltaY));
			}
			stepX = snapToTile(stepX);
			stepY = snapToTile(stepY);

			Location resolvedByCoordinate = findLocationAt(stepX, stepY);
			Location destination = resolvedByCoordinate;
			if (destination == null) {
				destination = world.getLocation(request.getTargetLocation())
					.orElseThrow(() -> new LocationNotFoundException(request.getTargetLocation()));
				if (!destination.isWithinBounds(stepX, stepY)) {
					throw new SmallvilleException("Move blocked by location bounds");
				}
			}

			Agent occupant = findOccupyingAgentAtTile(destination, stepX, stepY, player);
			if (occupant != null) {
				throw new SmallvilleException("Target tile is occupied by " + occupant.getFullName());
			}
			WorldObjectInstance blockingObject = findBlockingObjectAtTile(destination, stepX, stepY);
			boolean isClimbMove = request.getFlair() != null
				&& request.getFlair().toLowerCase().contains("action:climb");
			if (blockingObject != null) {
				if (isClimbMove && isObjectClimbable(blockingObject)) {
					player.setCurrentActivity("Climbing onto " + blockingObject.getName());
				} else {
					throw new SmallvilleException("Target tile is blocked by " + blockingObject.getName());
				}
			}

			player.setLocation(destination);
			player.setPosition(stepX, stepY);
			player.setCurrentActivity("Moving toward " + destination.getFullPath());

			res.setPlayerState(fromAgentWithInventory(player));
			res.setStressChange(0);
			res.setResult("Moved to (" + (int)(stepX / TILE_SIZE) + ", " + (int)(stepY / TILE_SIZE) + ")");
			return res;
		}

		if ("drop".equalsIgnoreCase(request.getActionType())) {
			String dropToken = request.getTargetAgent();
			String objId = dropToken != null && dropToken.startsWith("object:") ? dropToken.substring(7) : dropToken;
			WorldObjectInstance toDrop = objId != null ? objectInstances.get(objId) : null;
			if (toDrop != null && isObjectInInventory(player, objId)) {
				LinkedHashSet<String> inv = getInventorySet(player);
				inv.remove(objId);
				player.removeInventoryItem(objId);
				if (toDrop.getProperties() == null) toDrop.setProperties(new HashMap<>());
				toDrop.getProperties().remove("heldBy");
				toDrop.setHeldBy(null);
				toDrop.setX(snapToTile(player.getX()));
				toDrop.setY(snapToTile(player.getY()));
				toDrop.setLocation(player.getLocation() != null ? player.getLocation().getFullPath() : null);
				refreshAgentCarriedItems(player);
				recordCommittedAction(player, "DROP", toDrop.getId() + " at (" + toDrop.getX() + "," + toDrop.getY() + ")");
				res.setResult("Dropped " + toDrop.getName());
			} else {
				res.setResult("Nothing to drop");
			}
			res.setPlayerState(fromAgentWithInventory(player));
			return res;
		}

		if ("use".equalsIgnoreCase(request.getActionType())) {
			String target = request.getTargetAgent();
			if (target == null || target.isBlank()) {
				throw new SmallvilleException("targetAgent required for use");
			}
			String objId = target.startsWith("object:") ? target.substring(7) : target;
			WorldObjectInstance useTarget = objectInstances.get(objId);
			if (useTarget == null) {
				throw new SmallvilleException("Object '" + objId + "' not found");
			}
			Map<String, Object> useProps = useTarget.getProperties() != null ? useTarget.getProperties() : new HashMap<>();
			boolean isConsumable = Boolean.TRUE.equals(useProps.get("consumable"));
			boolean isMachine = Boolean.TRUE.equals(useProps.get("usable")) && useProps.containsKey("produces_item");
			if (!isConsumable && !isMachine) {
				throw new SmallvilleException(useTarget.getName() + " cannot be used");
			}
			if (isConsumable && !isObjectInInventory(player, objId)) {
				throw new SmallvilleException("You must be holding " + useTarget.getName() + " to use it");
			}
			String useNarrative = new WorldStateMutator(world, objectInstances)
				.apply(player, "use", objId, WorldAction.TargetType.OBJECT, null, turnCounter.get());
			player.setCurrentActivity(useNarrative != null ? useNarrative : "used " + useTarget.getName());
			player.applyStressChange(-0.05);
			res.setResult(useNarrative != null ? useNarrative : "Used " + useTarget.getName());
			res.setPlayerState(fromAgentWithInventory(player));
			res.setStressChange(-0.05);
			appendChronicle(player.getFullName(), "player", "use", objId, "object",
				useNarrative != null ? useNarrative : "used " + useTarget.getName(),
				player.getX(), player.getY(), useTarget.getX(), useTarget.getY());
			return res;
		}

		if ("interact".equalsIgnoreCase(request.getActionType()) || "attack".equalsIgnoreCase(request.getActionType())
			|| "punch".equalsIgnoreCase(request.getActionType()) || "kick".equalsIgnoreCase(request.getActionType())
			|| "tackle".equalsIgnoreCase(request.getActionType())) {
			String target = request.getTargetAgent();
			if (target == null) {
				throw new SmallvilleException("targetAgent required for interaction");
			}

			WorldObjectInstance objectTarget = resolveObjectTarget(target);
			if (objectTarget != null) {
				if (request.getPlayerX() != 0 || request.getPlayerY() != 0) {
					if (player.getLocation() != null && player.getLocation().isWithinBounds(request.getPlayerX(), request.getPlayerY())) {
						player.setPosition(request.getPlayerX(), request.getPlayerY());
					}
				}

				String objectFeasibilityError = validateObjectInteractionFeasibility(player, objectTarget);
				if (objectFeasibilityError != null) {
					throw new SmallvilleException(objectFeasibilityError);
				}

				int objectDistance = tileManhattanDistance(player.getX(), player.getY(), objectTarget.getX(), objectTarget.getY());
				String verbDescription = request.getActionDescription() == null || request.getActionDescription().isBlank()
					? "Interacting with " + objectTarget.getName()
					: request.getActionDescription();
				String normalizedVerb = verbDescription.toLowerCase();
				String normalizedFlair = request.getFlair() == null ? "" : request.getFlair().toLowerCase();
				// Gate lock and unlock on the actor holding a tagged key or lockpick
				boolean isLockUnlockVerb = normalizedFlair.contains("action:lock") || normalizedFlair.contains("action:unlock")
					|| normalizedVerb.contains("unlock") || normalizedVerb.startsWith("lock") || normalizedVerb.contains(" lock");
				if (isLockUnlockVerb) {
					if (!actorHasGrant(player, "key")) {
						throw new SmallvilleException("You need a key to lock or unlock this.");
					}
					// For unlock on a locked object: enforce instance-specific key binding via ActionResolver
					boolean isUnlockVerb = normalizedFlair.contains("action:unlock")
						|| (normalizedVerb.contains("unlock") && !normalizedVerb.contains(" lock"));
					if (isUnlockVerb && objectTarget.isLocked()) {
						WorldAction unlockWa = WorldAction.fromPlayerAction(
							player.getFullName(), "open",
							objectTarget.getId(), WorldAction.TargetType.OBJECT,
							null, null, player.getX(), player.getY(), 0);
						ActionResolver.ResolveResult unlockRr = new ActionResolver(
							buildInventoryByActor(), objectInstances, objectTypeDefinitions).resolve(unlockWa);
						if (!unlockRr.permitted) {
							throw new SmallvilleException(unlockRr.explanation != null
								? unlockRr.explanation : "Your key does not open this.");
						}
					}
				}

				// ActionResolver gate: write requires writing_utensil grant; carry checks carriable affordance
				{
					String resolverVerb = null;
					if (normalizedFlair.contains("action:write")
							|| normalizedVerb.startsWith("write on") || normalizedVerb.contains("writing on")) {
						resolverVerb = "write";
					} else if (normalizedFlair.contains("action:carry")
							|| (normalizedVerb.contains("carry") && !normalizedVerb.contains("can't carry"))) {
						resolverVerb = "carry";
					}
					if (resolverVerb != null) {
						WorldAction wa = WorldAction.fromPlayerAction(
							player.getFullName(), resolverVerb,
							objectTarget.getId(), WorldAction.TargetType.OBJECT,
							request.getItem(), request.getSpeakText(),
							player.getX(), player.getY(), 0);
						ActionResolver.ResolveResult rr = new ActionResolver(
							buildInventoryByActor(), objectInstances, objectTypeDefinitions).resolve(wa);
						if (!rr.permitted) {
							throw new SmallvilleException(rr.explanation != null
								? rr.explanation : "Cannot perform action: " + resolverVerb);
						}
					}
				}

				// Lock / unlock (new vocabulary)
				if (normalizedFlair.contains("action:unlock") || (normalizedVerb.contains("unlock") && !normalizedVerb.contains("lock"))) {
					objectTarget.getProperties().put("locked", false);
					objectTarget.getProperties().put("passable", true);
					verbDescription = "Unlocked " + objectTarget.getName();
				} else if (normalizedFlair.contains("action:lock") || (normalizedVerb.startsWith("lock") || normalizedVerb.contains(" lock"))) {
					objectTarget.getProperties().put("locked", true);
					objectTarget.getProperties().put("passable", false);
					verbDescription = "Locked " + objectTarget.getName();
				} else if ("entrance_anchor".equalsIgnoreCase(objectTarget.getType())
					|| asBoolean(objectTarget.getProperties().get("transition_point"), false)
					|| containsTag(objectTarget.getProperties(), "entrance")
					|| containsTag(objectTarget.getProperties(), "door")) {
					// Legacy open/close — kept for backward compat, writes both old and new props
					if (normalizedVerb.contains("close")) {
						objectTarget.getProperties().put("locked", true);
						objectTarget.getProperties().put("passable", false);
						objectTarget.getProperties().put("doorOpen", false);
						verbDescription = "Closed " + objectTarget.getName();
					} else if (normalizedVerb.contains("open")) {
						objectTarget.getProperties().put("locked", false);
						objectTarget.getProperties().put("passable", true);
						objectTarget.getProperties().put("doorOpen", true);
						verbDescription = "Opened " + objectTarget.getName();
					}
				}
				// Write — store player-supplied text in the object's has_writing property
				if (normalizedFlair.contains("action:write") || normalizedVerb.startsWith("write on") || normalizedVerb.contains("writing on")) {
					String textToWrite = request.getSpeakText();
					if (textToWrite != null && !textToWrite.isBlank()) {
						String sanitized = textToWrite.trim();
						if (sanitized.length() > 280) sanitized = sanitized.substring(0, 280);
						if (objectTarget.getProperties() == null) objectTarget.setProperties(new HashMap<>());
						objectTarget.getProperties().put("has_writing", sanitized);
						verbDescription = "Wrote on " + objectTarget.getName() + ": \"" + sanitized + "\"";
					} else {
						verbDescription = "Writing on " + objectTarget.getName() + " (no text supplied)";
					}
				}

				// Read — surface stored writing, no further processing needed
				if (normalizedFlair.contains("action:read") || normalizedVerb.startsWith("read")) {
					Object writingObj = objectTarget.getProperties().get("has_writing");
					String writing = writingObj != null ? writingObj.toString() : null;
					res.setResult(writing != null && !writing.isBlank()
						? "Read: " + writing
						: objectTarget.getName() + " has nothing written on it.");
					res.setPlayerState(fromAgentWithInventory(player));
					return res;
				}

				if (normalizedVerb.contains("carry") || normalizedVerb.contains("steal") || normalizedFlair.contains("action:carry")) {
					addObjectToInventory(player, objectTarget);
					verbDescription = "Picked up " + objectTarget.getName();
				}
				if (normalizedVerb.contains("place object") || normalizedFlair.contains("action:place_object")) {
					WorldObjectInstance placed = placeFirstInventoryObjectAt(player, objectTarget);
					if (placed != null) {
						verbDescription = "Placed " + placed.getName() + " near " + objectTarget.getName();
					}
				}

				player.setCurrentActivity(verbDescription);
				double stressDelta = 0.01;
				String verbLower = verbDescription.toLowerCase();
				String flairLower = normalizedFlair;
				if (verbLower.contains("steal") || verbLower.contains("attack") || flairLower.contains("action:bind")) {
					stressDelta = 0.08;
				} else if (verbLower.contains("sit") || verbLower.contains("rest") || verbLower.contains("sleep")) {
					stressDelta = -0.05;
				} else if (flairLower.contains("action:heal") || flairLower.contains("action:apply_herbs")) {
					stressDelta = -0.10;  // healing reduces stress
				} else if (flairLower.contains("action:study") || flairLower.contains("action:light")) {
					stressDelta = -0.02;  // calm activities
				}
				player.applyStressChange(stressDelta);
				recordCommittedAction(player, "INTERACT_OBJECT", objectTarget.getId() + " | " + verbDescription);

				for (Agent bystander : world.getAgents()) {
					if (bystander.getFullName().equals(player.getFullName())) {
						continue;
					}
					if (bystander.getLocation() != null && objectTarget.getLocation() != null
						&& objectTarget.getLocation().equals(bystander.getLocation().getFullPath())
						&& tileManhattanDistance(bystander.getX(), bystander.getY(), objectTarget.getX(), objectTarget.getY()) <= OBJECT_WITNESS_TILE_DISTANCE) {
						enqueueReactiveEvent(bystander.getFullName(), "noticed player " + verbDescription.toLowerCase(), 3, true);
					}
				}

				res.setPlayerState(fromAgentWithInventory(player));
				res.setStressChange(stressDelta);
				res.setResult("Interacted with " + objectTarget.getName());
				appendChronicle(player.getFullName(), "player", normalizedVerb, objectTarget.getId(), "object",
					verbDescription, player.getX(), player.getY(), objectTarget.getX(), objectTarget.getY());
				return res;
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

			String combatVerb = request.getActionType().toLowerCase();

			// Per-verb range enforcement
			if ("punch".equals(combatVerb) || "kick".equals(combatVerb)) {
				int adjDist = tileManhattanDistance(player.getX(), player.getY(), targetAgent.getX(), targetAgent.getY());
				if (adjDist > 2) {
					throw new SmallvilleException(combatVerb + " requires being adjacent. Move closer first.");
				}
			}
			if ("tackle".equals(combatVerb)) {
				int tackleDist = tileManhattanDistance(player.getX(), player.getY(), targetAgent.getX(), targetAgent.getY());
				if (tackleDist < 2) {
					throw new SmallvilleException("Cannot tackle when already adjacent — use punch or kick instead.");
				}
				if (tackleDist > 3) {
					throw new SmallvilleException("Too far to tackle — move within 3 tiles.");
				}
				// Move player to the adjacent tile of the target that is closest to the player's position
				double[][] adjacentTiles = {
					{targetAgent.getX() + TILE_SIZE, targetAgent.getY()},
					{targetAgent.getX() - TILE_SIZE, targetAgent.getY()},
					{targetAgent.getX(), targetAgent.getY() + TILE_SIZE},
					{targetAgent.getX(), targetAgent.getY() - TILE_SIZE}
				};
				double bestDist = Double.MAX_VALUE;
				double newX = player.getX(), newY = player.getY();
				for (double[] tile : adjacentTiles) {
					double cx = snapToTile(tile[0]);
					double cy = snapToTile(tile[1]);
					double d = Math.abs(cx - player.getX()) + Math.abs(cy - player.getY());
					if (d < bestDist) {
						bestDist = d;
						newX = cx;
						newY = cy;
					}
				}
				player.setPosition(newX, newY);
			}

			// Calculate distance-adjusted duration
			double distance = calculateDistance(player, targetAgent);
			long adjustedDuration = computeDistanceAdjustedDuration(baseDuration, distance);

			boolean isAggressiveVerb = isAggressiveActionType(combatVerb);
			double stressDelta = isAggressiveVerb ? Math.max(intensity * 0.2, 0.10) : intensity * 0.2;
			targetAgent.applyStressChange(stressDelta);
			recordStressEventIfSignificant(targetAgent,
				(request.getActionDescription() != null ? request.getActionDescription() : "player action"));
			player.applyStressChange(isAggressiveVerb ? 0.08 : intensity * 0.05);

			targetAgent.setCurrentActivity(request.getActionDescription() != null ? request.getActionDescription() : "Interacted");
			player.setCurrentActivity(request.getActionDescription() != null ? request.getActionDescription() : "Interacted");

			int eventSeverity = isAggressiveVerb ? 9 : 4;
			enqueueReactiveEvent(targetAgent.getFullName(),
				request.getActionDescription() != null ? request.getActionDescription() : "direct interaction",
				eventSeverity, true);

			if (isAggressiveVerb) {
				int damage = computeVerbDamage(player, combatVerb);
				targetAgent.applyDamage(damage);
				String hitDesc = buildCombatHitDescription(player.getFullName(), combatVerb, damage);
				Observation atkObs = new Observation(hitDesc);
				atkObs.setImportance(9);
				targetAgent.getMemoryStream().add(atkObs);
				ChronicleEvent atkEvt = appendChronicle(
					player.getFullName(), "player", combatVerb,
					targetAgent.getFullName(), "agent",
					hitDesc, player.getX(), player.getY(),
					targetAgent.getX(), targetAgent.getY());
				targetAgent.getEpistemicMemory().ingestObserved(atkEvt);
				triggerAttackedResponse(targetAgent, player.getFullName(), player);
			} else {
				// Non-attack direct interaction — record it so the agent knows who did what
				String interactDesc = request.getActionDescription() != null
					? request.getActionDescription() : "interacted with you";
				ChronicleEvent interactEvt = appendChronicle(
					player.getFullName(), "player", "interact",
					targetAgent.getFullName(), "agent",
					interactDesc, player.getX(), player.getY(),
					targetAgent.getX(), targetAgent.getY());
				targetAgent.getEpistemicMemory().ingestObserved(interactEvt);
				Observation interactObs = new Observation(
					player.getFullName() + " " + interactDesc.toLowerCase() + ".");
				interactObs.setImportance(5);
				targetAgent.getMemoryStream().add(interactObs);
			}
			for (Agent bystander : world.getAgents()) {
				if (bystander.getFullName().equals(player.getFullName()) || bystander.getFullName().equals(targetAgent.getFullName())) {
					continue;
				}
				if (bystander.getLocation() != null && targetAgent.getLocation() != null
					&& bystander.getLocation().getFullPath().equals(targetAgent.getLocation().getFullPath())) {
					enqueueReactiveEvent(bystander.getFullName(),
						"witnessed: " + (request.getActionDescription() == null ? combatVerb : request.getActionDescription()),
						3, true);
				}
			}

			res.setPlayerState(fromAgentWithInventory(player));
			res.setTargetAgentState(mapper.fromAgent(targetAgent));
			res.setStressChange(stressDelta);
			res.setResult(combatVerb + " on " + targetAgent.getFullName()
				+ " — distance: " + String.format("%.1f", distance) + " units");

			return res;
		}

		if ("throw".equalsIgnoreCase(request.getActionType())) {
			Agent throwTarget = request.getTargetAgent() != null
				? world.getAgent(request.getTargetAgent()).orElse(null) : null;
			double targetX = throwTarget != null ? throwTarget.getX() : player.getX() + TILE_SIZE * 2;
			double targetY = throwTarget != null ? throwTarget.getY() : player.getY();
			String throwDesc = executeThrow(player, request.getItem(), targetX, targetY,
				request.getTargetAgent());
			if (throwTarget != null) {
				int throwDamage = computeAttackDamage(player) / 2;
				throwTarget.applyDamage(throwDamage);
				throwTarget.getMemoryStream().add(new Observation(
					player.getFullName() + " threw something at me."));
				triggerAttackedResponse(throwTarget, player.getFullName(), player);
			}
			ChronicleEvent throwEvt = appendChronicle(
				player.getFullName(), "player", "throw",
				request.getTargetAgent() != null ? request.getTargetAgent() : "tile", "object",
				throwDesc, player.getX(), player.getY(), targetX, targetY);
			if (throwTarget != null) {
				throwTarget.getEpistemicMemory().ingestObserved(throwEvt);
				res.setTargetAgentState(mapper.fromAgent(throwTarget));
			}
			res.setPlayerState(fromAgentWithInventory(player));
			res.setResult(throwDesc);
			return res;
		}

		if ("speak".equalsIgnoreCase(request.getActionType()) || (request.getSpeakText() != null && !request.getSpeakText().isEmpty())) {
			// Update player position from request so distance checks use fresh client coords
			if (request.getPlayerX() != 0 || request.getPlayerY() != 0) {
				if (player.getLocation() != null && player.getLocation().isWithinBounds(request.getPlayerX(), request.getPlayerY())) {
					player.setPosition(request.getPlayerX(), request.getPlayerY());
				}
			}
			// speaking: minor stress impacts, record as conversation
			player.setCurrentActivity("Speaking");
			player.applyStressChange(0.01);
			String cleanedSpeak = sanitizeDialogueText(request.getSpeakText());
			Agent dialogueTarget = null;
			int closestTileDistance = Integer.MAX_VALUE;

			if (request.getTargetAgent() != null && !request.getTargetAgent().isBlank()) {
				dialogueTarget = world.getAgent(request.getTargetAgent()).orElse(null);
				if (dialogueTarget == null) {
					throw new SmallvilleException("Target agent not found: " + request.getTargetAgent());
				}
				if (dialogueTarget.getLocation() == null || player.getLocation() == null
					|| !dialogueTarget.getLocation().getFullPath().equals(player.getLocation().getFullPath())) {
					throw new SmallvilleException("Target agent is not in the same location. Move closer before speaking.");
				}
				int targetDistance = tileManhattanDistance(player.getX(), player.getY(), dialogueTarget.getX(), dialogueTarget.getY());
				if (targetDistance > AGENTIC_INITIATE_TILE_DISTANCE) {
					throw new SmallvilleException("Target agent is too far away to talk (" + targetDistance
						+ " tiles). Move within " + AGENTIC_INITIATE_TILE_DISTANCE + " tiles.");
				}
			}

			for (Agent listener : world.getAgents()) {
			    if (listener.getFullName().equals(player.getFullName())) {
				continue;
			    }
			    if (listener.getLocation() != null && player.getLocation() != null
				&& listener.getLocation().getFullPath().equals(player.getLocation().getFullPath())
				&& tileManhattanDistance(player.getX(), player.getY(), listener.getX(), listener.getY()) <= AGENTIC_INITIATE_TILE_DISTANCE) {
				enqueueReactiveEvent(listener.getFullName(), "overheard: " + request.getSpeakText(), 4, true);
				int tileDistance = tileManhattanDistance(player.getX(), player.getY(), listener.getX(), listener.getY());
				if (dialogueTarget == null && tileDistance < closestTileDistance) {
				    dialogueTarget = listener;
				    closestTileDistance = tileDistance;
				}
			    }
			}

			if (dialogueTarget != null && cleanedSpeak != null && !cleanedSpeak.isBlank()) {
				dialogueTarget.setCurrentActivity("Talking with " + player.getFullName());
				recordCommittedAction(dialogueTarget, "TALK", "responding to player dialogue");
				recordConversationTurn(player.getFullName(), dialogueTarget.getFullName(), cleanedSpeak, SimulationTime.now());
				// Store what was said so the agent remembers it in future turns
				Observation heardObs = new Observation(player.getFullName() + " said to me: \"" + cleanedSpeak + "\"");
				heardObs.setImportance(7);
				dialogueTarget.getMemoryStream().add(heardObs);
				res.setTargetAgentState(mapper.fromAgent(dialogueTarget));
				res.setAgentReplySpeaker(dialogueTarget.getFullName());
				try {
				    String contextAwarePrompt = composeContextAwareQuestion(dialogueTarget, cleanedSpeak);
				    String reply = prompts.ask(dialogueTarget, contextAwarePrompt);
				    String cleanedReply = sanitizeDialogueText(reply);
				    if (cleanedReply != null && !cleanedReply.isBlank()) {
				        res.setAgentReplyText(cleanedReply);
						recordConversationTurn(dialogueTarget.getFullName(), player.getFullName(), cleanedReply, SimulationTime.now());
						Observation repliedObs = new Observation("I said to " + player.getFullName() + ": \"" + cleanedReply + "\"");
						repliedObs.setImportance(5);
						dialogueTarget.getMemoryStream().add(repliedObs);
				    } else {
				        res.setAgentReplyText("I need a moment to think about that.");
				    }
				    res.setResult("Dialogue complete");
					ChronicleEvent speakEvt = appendChronicle(player.getFullName(), "player", "speak",
						dialogueTarget.getFullName(), "agent",
						cleanedSpeak, player.getX(), player.getY(), dialogueTarget.getX(), dialogueTarget.getY());
					dialogueTarget.getEpistemicMemory().ingestObserved(speakEvt);
				} catch (Exception e) {
				    LOG.warn("Dialogue ask failed for {}: {}", dialogueTarget.getFullName(), e.getMessage());
				    res.setAgentReplyText("I need a moment to think about that.");
				    res.setResult("Dialogue fallback response");
				}
			} else {
				res.setResult("Spoke: " + (cleanedSpeak == null ? "" : cleanedSpeak));
			}
			res.setPlayerState(fromAgentWithInventory(player));
			return res;
		}

		if ("give".equalsIgnoreCase(request.getActionType())) {
			String recipientId = request.getTargetAgent();
			String itemId = request.getItem();
			if (recipientId == null || recipientId.isBlank()) {
				throw new SmallvilleException("give requires targetAgent");
			}
			if (itemId == null || itemId.isBlank()) {
				throw new SmallvilleException("give requires itemId (the item to hand over)");
			}
			Agent recipient = world.getAgent(recipientId)
				.orElseThrow(() -> new SmallvilleException("Target agent not found: " + recipientId));

			WorldAction wa = WorldAction.fromPlayerAction(
				player.getFullName(), "give", recipientId,
				WorldAction.TargetType.AGENT, itemId, null,
				player.getX(), player.getY(), 0);
			ActionResolver.ResolveResult rr = new ActionResolver(
				buildInventoryByActor(), objectInstances, objectTypeDefinitions).resolve(wa);
			if (!rr.permitted) {
				throw new SmallvilleException(rr.explanation != null ? rr.explanation : "Cannot give that item.");
			}

			InventoryItem transferred = player.removeInventoryItem(itemId);
			if (transferred != null) {
				recipient.addInventoryItem(transferred);
				refreshAgentCarriedItems(player);
				refreshAgentCarriedItems(recipient);
				recipient.getEpistemicMemory().ingestHearsay(
					player.getFullName(),
					player.getFullName() + " gave you " + transferred.getDisplayName(),
					0, 0.95);
				ChronicleEvent giveEvt = appendChronicle(player.getFullName(), "player", "give", recipientId, "agent",
					transferred.getDisplayName(), player.getX(), player.getY(), recipient.getX(), recipient.getY());
				recipient.getEpistemicMemory().ingestObserved(giveEvt);
				res.setResult("Gave " + transferred.getDisplayName() + " to " + recipientId);
				res.setTargetAgentState(mapper.fromAgent(recipient));
			} else {
				throw new SmallvilleException("Item '" + itemId + "' not found in your inventory.");
			}
			res.setPlayerState(fromAgentWithInventory(player));
			return res;
		}

		// default fallback
		player.setCurrentActivity(request.getActionDescription() != null ? request.getActionDescription() : "Acting");
		res.setPlayerState(fromAgentWithInventory(player));
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
			d.setHealth(a.getHealth());
			d.setIncapacitated(a.isIncapacitated());
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
	instance.setId(objectId);
	instance.setType(request.getType());
	instance.setName(request.getName() == null || request.getName().isBlank() ? objectId : request.getName());
	instance.setX(request.getX());
	instance.setY(request.getY());
	Location location = findLocationAt(request.getX(), request.getY());
	instance.setLocation(request.getLocation() != null ? request.getLocation() : (location == null ? null : location.getFullPath()));

	Map<String, Object> merged = new HashMap<>();
	merged.putAll(objectTypeDefinitions.getOrDefault(instance.getType(), new HashMap<>()));
	if (request.getProperties() != null) {
	    merged.putAll(request.getProperties());
	}
	instance.setProperties(merged);

	objectInstances.put(objectId, instance);

	// Auto-add to holder's inventory when heldBy is set (used for seeding starting items)
	Object heldBy = merged.get("heldBy");
	if (heldBy != null && !String.valueOf(heldBy).isBlank()) {
		String holderName = String.valueOf(heldBy).trim();
		instance.setHeldBy(holderName); // sync the dedicated field so isCarried() works
		world.getAgent(holderName).ifPresent(agent -> {
			LinkedHashSet<String> inv = getInventorySet(agent);
			inv.add(objectId);
			refreshAgentCarriedItems(agent);
		});
	}

	return instance.toMap();
    }

    public Map<String, Object> patchObjectProperties(String objectId, Map<String, Object> patch) {
	WorldObjectInstance instance = objectInstances.get(objectId);
	if (instance == null) {
	    throw new SmallvilleException("Unknown object id: " + objectId);
	}
	if (patch != null) {
	    instance.getProperties().putAll(patch);
	}
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
	Location location = findLocationAt(x, y);
	String locationName = location == null ? null : location.getFullPath();
	int tileX = toTile(x);
	int tileY = toTile(y);

	List<Map<String, Object>> objects = new ArrayList<>();

	for (Agent agent : world.getAgents()) {
	    if (agent.getLocation() == null) {
		continue;
	    }
	    boolean sameLocation = locationName != null && locationName.equals(agent.getLocation().getFullPath());
	    boolean sameTile = toTile(agent.getX()) == tileX && toTile(agent.getY()) == tileY;
	    if (sameLocation && sameTile) {
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
	    if (isObjectHeld(instance)) {
		continue;
	    }
	    boolean sameLocation = locationName != null && locationName.equals(instance.getLocation());
	    boolean sameTile = toTile(instance.getX()) == tileX && toTile(instance.getY()) == tileY;
	    if (sameLocation && sameTile) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("kind", "object");
		item.putAll(instance.toMap());
		objects.add(item);
	    }
	}

	return objects;
    }

	public Map<String, Object> getInteractionAffordances(String playerId, double playerX, double playerY, double radius) {
		Agent player = world.getAgent(playerId).orElseThrow(() -> new AgentNotFoundException(playerId));
		int safeRadiusTiles = radius > 0 ? Math.max(1, (int) Math.round(radius)) : DEFAULT_PLAYER_AFFORDANCE_TILE_RADIUS;
		if (player.getLocation() != null) {
			player.setPosition(playerX, playerY);
		}

		Location location = findLocationAt(playerX, playerY);
		String locationPath = location == null ? null : location.getFullPath();
		List<Map<String, Object>> actions = new ArrayList<>();

		for (Agent candidate : world.getAgents()) {
			if (candidate.getFullName().equalsIgnoreCase(playerId)) {
				continue;
			}
			if (candidate.getLocation() == null || locationPath == null) {
				continue;
			}
			if (!locationPath.equals(candidate.getLocation().getFullPath())) {
				continue;
			}
			int distance = tileManhattanDistance(candidate.getX(), candidate.getY(), playerX, playerY);
			if (distance > safeRadiusTiles) {
				continue;
			}

			actions.add(buildAgentAffordance("Talk", "speak", candidate, distance,
				"Speak with " + candidate.getFullName(), "", ""));
			actions.add(buildAgentAffordance("Observe", "interact", candidate, distance,
				"Observe " + candidate.getFullName(), "", ""));
			actions.add(buildAgentAffordance("Attack", "attack", candidate, distance,
				"Attack " + candidate.getFullName(), "", "aggressive"));
		}

		for (WorldObjectInstance obj : objectInstances.values()) {
			if (isObjectHeld(obj)) {
				continue;
			}
			if (obj.getLocation() == null || locationPath == null) {
				continue;
			}
			if (!locationPath.equals(obj.getLocation())) {
				continue;
			}
			int objectDistance = tileManhattanDistance(obj.getX(), obj.getY(), playerX, playerY);
			int interactionRadiusTiles = Math.max(1, toTileDistance(asDouble(obj.getProperties() == null ? null : obj.getProperties().get("interactionRadius"), TILE_SIZE)));
			if (objectDistance > Math.min(safeRadiusTiles, interactionRadiusTiles + 1)) {
				continue;
			}
			actions.addAll(buildObjectAffordanceActions(obj, objectDistance, player));
		}

		actions.sort((a, b) -> Double.compare(
			asDouble(a.get("distance"), 0.0),
			asDouble(b.get("distance"), 0.0)));

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("player", playerId);
		response.put("x", playerX);
		response.put("y", playerY);
		response.put("location", locationPath);
		response.put("radiusTiles", safeRadiusTiles);
		response.put("actions", actions);
		return response;
	}

	public Map<String, Object> getInteractionAffordancesAtCoordinate(String playerId, double x, double y) {
		Agent player = world.getAgent(playerId).orElseThrow(() -> new AgentNotFoundException(playerId));
		Location tileLocation = findLocationAt(x, y);
		String locationPath = tileLocation == null ? null : tileLocation.getFullPath();
		List<Map<String, Object>> actions = new ArrayList<>();

		for (Map<String, Object> row : getObjectsAtCoordinate(x, y)) {
			if (row == null) {
				continue;
			}
			String kind = String.valueOf(row.getOrDefault("kind", ""));
			if ("agent".equals(kind) || "player".equals(kind)) {
				String targetName = String.valueOf(row.getOrDefault("name", ""));
				if (targetName.isBlank() || targetName.equalsIgnoreCase(playerId)) {
					continue;
				}
				Agent target = world.getAgent(targetName).orElse(null);
				if (target == null) {
					continue;
				}
				int distance = tileManhattanDistance(target.getX(), target.getY(), player.getX(), player.getY());
				actions.add(buildAgentAffordance("Talk", "speak", target, distance,
					"Speak with " + target.getFullName(), "", ""));
				actions.add(buildAgentAffordance("Observe", "interact", target, distance,
					"Observe " + target.getFullName(), "", ""));
				actions.add(buildAgentAffordance("Attack", "attack", target, distance,
					"Attack " + target.getFullName(), "", "aggressive"));
			} else if ("object".equals(kind)) {
				String objectId = String.valueOf(row.getOrDefault("id", ""));
				WorldObjectInstance obj = objectInstances.get(objectId);
				if (obj == null) {
					continue;
				}
				int distance = tileManhattanDistance(obj.getX(), obj.getY(), player.getX(), player.getY());
				actions.addAll(buildObjectAffordanceActions(obj, distance, player));
			}
		}

		actions.sort((a, b) -> Double.compare(
			asDouble(a.get("distance"), 0.0),
			asDouble(b.get("distance"), 0.0)));

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("player", playerId);
		response.put("x", x);
		response.put("y", y);
		response.put("tileX", toTile(x));
		response.put("tileY", toTile(y));
		response.put("location", locationPath);
		response.put("actions", actions);
		return response;
	}

	public Map<String, Object> getConversationTranscript(String speakerA, String speakerB, int limit) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("speakerA", speakerA);
		result.put("speakerB", speakerB);
		result.put("limit", Math.max(1, limit));
		result.put("transcript", getRecentConversationTranscript(speakerA, speakerB, Math.max(1, limit)));
		return result;
	}

	private Map<String, Object> buildAgentAffordance(String label, String actionType, Agent target,
		double distance, String description, String item, String flair) {
		Map<String, Object> action = new LinkedHashMap<>();
		action.put("label", label + " " + target.getFullName());
		action.put("actionType", actionType);
		action.put("targetKind", target instanceof Player ? "player" : "agent");
		action.put("targetId", target.getFullName());
		action.put("targetName", target.getFullName());
		action.put("distance", distance);
		action.put("hint", description);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("targetAgent", target.getFullName());
		payload.put("actionDescription", description);
		if (item != null && !item.isBlank()) {
			payload.put("item", item);
		}
		if (flair != null && !flair.isBlank()) {
			payload.put("flair", flair);
		}
		action.put("payload", payload);
		return action;
	}

	private List<Map<String, Object>> buildObjectAffordanceActions(WorldObjectInstance object, double distance, Agent actor) {
		List<Map<String, Object>> actions = new ArrayList<>();
		if (object == null) {
			return actions;
		}
		if (isObjectHeld(object)) {
			return actions;
		}
		Map<String, Object> properties = object.getProperties() == null ? new HashMap<>() : object.getProperties();
		boolean interactive = asBoolean(properties.get("interactive"), true);
		if (!interactive) {
			return actions;
		}

		actions.add(buildObjectAffordanceAction("Inspect", object, distance,
			"Inspecting " + object.getName(), "observant"));

		// Write: any interactive object is writable if actor has a writing tool,
		// unless explicitly marked hard_to_write_on.
		boolean hardToWrite = asBoolean(properties.get("hard_to_write_on"), false);
		if (!hardToWrite && actorHasGrant(actor, "writing_utensil")) {
			actions.add(buildObjectAffordanceAction("Write On", object, distance,
				"Writing on " + object.getName(), "action:write"));
		}
		if (asBoolean(properties.get("flat_surface"), false) && !getInventorySet(actor).isEmpty()) {
			actions.add(buildObjectAffordanceAction("Place Object", object, distance,
				"Placing object on " + object.getName(), "action:place_object"));
		}
		if (asBoolean(properties.get("stealable"), false)) {
			actions.add(buildObjectAffordanceAction("Steal", object, distance,
				"Stealing from " + object.getName(), "sneaky"));
		}
		if (asBoolean(properties.get("carriable"), false)
			&& !asBoolean(properties.get("rooted"), false)
			&& !asBoolean(properties.get("uncarriable"), false)
			&& !isObjectInInventory(actor, object.getId())) {
			actions.add(buildObjectAffordanceAction("Carry", object, distance,
				"Carrying " + object.getName(), "action:carry"));
		}
		if (asBoolean(properties.get("sitAround"), false) || asBoolean(properties.get("sit-able"), false)) {
			actions.add(buildObjectAffordanceAction("Sit", object, distance,
				"Sitting by " + object.getName(), "calm"));
		}
		if (asBoolean(properties.get("performable"), false)) {
			actions.add(buildObjectAffordanceAction("Perform", object, distance,
				"Performing near " + object.getName(), "expressive"));
		}

		boolean isDoorLike = asBoolean(properties.get("can_open_close"), false)
			|| properties.containsKey("doorOpen")
			|| containsTag(properties, "entrance")
			|| containsTag(properties, "door");
		if (isDoorLike) {
			boolean open = asBoolean(properties.get("doorOpen"), true);
			boolean locked = asBoolean(properties.get("locked"), false);
			if (open) {
				actions.add(buildObjectAffordanceAction("Close Door", object, distance,
					"Closing door at " + object.getName(), "close door"));
			} else if (!locked) {
				actions.add(buildObjectAffordanceAction("Open Door", object, distance,
					"Opening door at " + object.getName(), "open door"));
			} else if (locked && actorHasGrant(actor, "key")) {
				// Only offer unlock if actor has a key item
				actions.add(buildObjectAffordanceAction("Unlock Door", object, distance,
					"Unlocking door at " + object.getName(), "unlock"));
			}
		}

		Object activityProp = properties.get("activity");
		if (activityProp instanceof List<?> list) {
			for (Object verb : list) {
				String actionVerb = String.valueOf(verb == null ? "" : verb).trim();
				if (actionVerb.isBlank()) {
					continue;
				}
				String title = actionVerb.substring(0, 1).toUpperCase() + actionVerb.substring(1);
				actions.add(buildObjectAffordanceAction(title, object, distance,
					actionVerb + " at " + object.getName(), "task"));
			}
		}

		return actions;
	}

	private Map<String, Object> buildObjectAffordanceAction(String label, WorldObjectInstance object, double distance,
		String description, String flair) {
		Map<String, Object> action = new LinkedHashMap<>();
		action.put("label", label + " " + object.getName());
		action.put("actionType", "interact");
		action.put("targetKind", "object");
		action.put("targetId", object.getId());
		action.put("targetName", object.getName());
		action.put("distance", distance);
		action.put("hint", description);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("targetAgent", "object:" + object.getId());
		payload.put("actionDescription", description);
		payload.put("flair", flair);
		action.put("payload", payload);
		return action;
	}

	private boolean asBoolean(Object value, boolean defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Boolean b) {
			return b;
		}
		String raw = String.valueOf(value).trim().toLowerCase();
		if (raw.equals("true") || raw.equals("yes") || raw.equals("1")) {
			return true;
		}
		if (raw.equals("false") || raw.equals("no") || raw.equals("0")) {
			return false;
		}
		return defaultValue;
	}

	private boolean containsTag(Map<String, Object> properties, String tag) {
		if (properties == null || tag == null || tag.isBlank()) {
			return false;
		}
		Object tags = properties.get("tags");
		if (!(tags instanceof List<?> list)) {
			return false;
		}
		String needle = tag.trim().toLowerCase();
		for (Object candidate : list) {
			if (candidate != null && needle.equals(String.valueOf(candidate).trim().toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	private double asDouble(Object value, double defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		try {
			return Double.parseDouble(String.valueOf(value));
		} catch (NumberFormatException e) {
			return defaultValue;
		}
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

	// ── Drain completed async cognition results (main thread) ─────────────────
	Runnable pendingApply;
	while ((pendingApply = pendingCognitionApplies.poll()) != null) {
		pendingApply.run();
	}

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
	    AgenticRuntimeState agenticState = agenticStateByAgent.computeIfAbsent(agent.getFullName(), k -> new AgenticRuntimeState());
	    scanEnvironment(agent, agenticState);
	    LocalDate lastOrchestratedDate = state.lastOrchestratedAt == null ? null : state.lastOrchestratedAt.toLocalDate();
	    boolean crossedMidnight = lastOrchestratedDate != null && !lastOrchestratedDate.equals(nowDate);

	    // End-of-day reflection: primary trigger at 23:59 simulation time,
	    // plus fallback when a tick jumps over midnight.
	    if ((isEndOfDayReflectionMinute || crossedMidnight)
		&& (state.lastReflectionDate == null || !state.lastReflectionDate.equals(lastOrchestratedDate == null ? nowDate : lastOrchestratedDate))) {
		try {
		    prompts.runEndOfDayReflection(agent);
		    applyReflectionTraitSignals(agent);
		    state.lastReflectionDate = (lastOrchestratedDate == null ? nowDate : lastOrchestratedDate);
		    llmUpdated.add(agent.getFullName());
		} catch (Exception e) {
		    LOG.warn("[Runtime] Reflection failed for {}: {}", agent.getFullName(), e.getMessage());
		}
	    }

	    boolean cogInFlight = cognitionInFlight.contains(agent.getFullName());
	    boolean dayStart = request.isForceDayStart() || state.lastRoutineDate == null || !state.lastRoutineDate.equals(nowDate);
	    if (cogInFlight) {
		// LLM job in flight — maintain current activity; result applied next turn
		LOG.debug("[AsyncCognition] {} in flight, deferring this turn", agent.getFullName());
		deterministicUpdated.add(agent.getFullName());
	    } else if (dayStart && !agent.hasPendingActions()) {
		// Compute legal actions now (fast, synchronous) so the async job has them ready
		injectLegalActions(agent);
		final Agent agentRef = agent;
		final RuntimeAgentState stateRef = state;
		final LocalDate routineDate = nowDate;
		final boolean agentIsAware = isAware;
		submitAsyncCognition(agent,
			() -> prompts.refreshAgentForNewDay(agentRef),
			() -> {
				stateRef.lastRoutineDate = routineDate;
				stateRef.lastLlmCallAt = SimulationTime.now();
				traceTrackedAgent(agentRef, stateRef, "after-day-start-refresh");
			},
			() -> {
				// Set lastRoutineDate even on failure so dayStart becomes false next tick
				// and the agent transitions to the agentic tool loop rather than
				// retrying refreshAgentForNewDay every single tick forever.
				stateRef.lastRoutineDate = routineDate;
				applyDeterministicCatchUp(agentRef, agentIsAware);
				traceTrackedAgent(agentRef, stateRef, "after-day-start-fallback");
			});
		llmUpdated.add(agent.getFullName());
	    } else if (!dayStart) {
		// If the tool loop has a queued goal (e.g. retaliate/flee from triggerAttackedResponse),
		// drain the reactive event without submitting prompts.react() so cognitionInFlight
		// stays free for runAgenticLoop below.
		if (!agenticState.goalPlan.isEmpty()) {
		    pollReactiveEvent(agent.getFullName()); // consume without processing
		} else {
		ReactiveEvent event = pollReactiveEvent(agent.getFullName());
		if (event != null) {
		    boolean shouldLlmReact = shouldTriggerLlmReaction(event, isAware);
		    if (shouldLlmReact) {
			injectLegalActions(agent);
			final Agent agentRef = agent;
			final RuntimeAgentState stateRef = state;
			final ReactiveEvent eventRef = event;
			final boolean agentIsAware = isAware;
			submitAsyncCognition(agent,
				() -> prompts.react(agentRef, eventRef.description),
				() -> {
					stateRef.lastLlmCallAt = SimulationTime.now();
					traceTrackedAgent(agentRef, stateRef, "after-reactive-llm");
				},
				() -> {
					applyDeterministicReactiveFallback(agentRef, eventRef);
					traceTrackedAgent(agentRef, stateRef, "after-reactive-fallback");
				});
			reacted.add(agent.getFullName());
			llmUpdated.add(agent.getFullName());
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
		} // end else (!goalPlan.isEmpty())

	    runAgenticLoop(agent, state, now, request, lastPlayerAction);
	    }

	    // Advance action queue: promote queued→active, then process movement/activity
	    advanceAgentMovement(agent);

	    // Mark agent as orchestrated after first movement pass
	    agent.setHasBeenOrchestrated(true);

	    state.lastAware = isAware;
	    state.lastOrchestratedAt = SimulationTime.now();
	}

	// Increment turn counter and run perception channel (Item 8)
	int justCompletedTurn = turnCounter.getAndIncrement();
	runPerceptionChannel(justCompletedTurn);

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
        // Severity-8+ events (combat, critical incidents) are handled by the tool loop's
        // pendingIntent and hostile-on-sight logic. Firing prompts.react() for these would
        // block the tool loop with a context-free generic response.
        if (event.severity >= 8) {
            return false;
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
	int distance = tileManhattanDistance(agent.getX(), agent.getY(), playerX, playerY);
	int radiusTiles = radius > 0 ? Math.max(1, (int) Math.round(radius)) : AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE;
	return distance <= radiusTiles;
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
		// Active goal details
		view.put("goalType",        state.activeGoal != null ? state.activeGoal.type        : null);
		view.put("goalTargetId",    state.activeGoal != null ? state.activeGoal.targetId    : null);
		view.put("goalTargetType",  state.activeGoal != null ? state.activeGoal.targetType  : null);
		view.put("goalTopic",       state.activeGoal != null ? state.activeGoal.topic       : null);
		view.put("goalDescription", state.activeGoal != null ? state.activeGoal.description : null);
		view.put("goalPriority",    state.activeGoal != null ? state.activeGoal.priority    : null);
		// History & scoring
		view.put("chatWindowClosedObserved", state.chatWindowClosedObserved);
		view.put("deferredTurns",     state.deferredTurns);
		view.put("recentIgnoreCount", state.recentIgnoreCount);
		view.put("socialFriction", state.socialFriction);
		view.put("lastInitiativeScore", state.lastInitiativeScore);
		view.put("lastOutcome",       state.lastOutcome);
		view.put("lastInitiatedAt",   state.lastInitiatedAt == null ? null : state.lastInitiatedAt.toString());
		view.put("lastRepliedAt",     state.lastRepliedAt   == null ? null : state.lastRepliedAt.toString());
		view.put("cooldownUntil",     state.cooldownUntil   == null ? null : state.cooldownUntil.toString());
		view.put("lastError",         state.lastError);
		return view;
	}

	private void runAgenticLoop(
		Agent agent,
		RuntimeAgentState runtimeState,
		LocalDateTime now,
		RuntimeOrchestrationRequest request,
		PlayerActionRequest lastPlayerAction) {
		if (agent == null || agent instanceof Player) {
			return;
		}
		if (agent.isIncapacitated()) {
			agent.setCurrentActivity("incapacitated");
			return;
		}

		AgenticRuntimeState state = agenticStateByAgent.computeIfAbsent(agent.getFullName(), k -> new AgenticRuntimeState());
		state.lastInitiativeScore = 0.0;

		// Update theory-of-mind beliefs from EpistemicMemory before any phase logic,
		// so the LLM always receives an up-to-date picture of other agents' activities.
		refreshBeliefModels(agent, state);

		// ── Agentic Tool Loop (Tier 3) ────────────────────────────────────────────
		// Legal actions and spatial knowledge are cheap; compute them before any
		// guard so the survival override and the LLM briefing both see fresh data.
		injectLegalActions(agent);
		if (agent.getSpatialKnowledge().isEmpty()) {
			seedSpatialKnowledge(agent);
		}

		// === Deterministic survival override: skip LLM when critically injured ===
		// Placed BEFORE the cognitionInFlight guard so a slow/stuck LLM call
		// cannot freeze a critically injured agent — survival always wins.
		boolean hasCombatGoal = state != null && !state.goalPlan.isEmpty()
			&& state.goalPlan.peek() != null
			&& isAggressiveActionType(state.goalPlan.peek().actionType != null
				? state.goalPlan.peek().actionType : "");
		if (agent.getHealth() < 50 && !hasCombatGoal) {
			// Case 1: Holding consumable food → eat it immediately
			InventoryItem heldFood = agent.getInventory().values().stream()
				.filter(item -> {
					WorldObjectInstance obj = objectInstances.get(item.getId());
					return obj != null && obj.getProperties() != null
						&& Boolean.TRUE.equals(obj.getProperties().get("consumable"))
						&& !Boolean.TRUE.equals(obj.getProperties().get("is_trash"));
				}).findFirst().orElse(null);
			if (heldFood != null) {
				LOG.info("[SurvivalOverride] {} eating {} (hp={})", agent.getFullName(), heldFood.getDisplayName(), agent.getHealth());
				applyTurnResult(agent, TurnResult.committed("use", heldFood.getId(), "object", null, null, new ArrayList<>()));
				return;
			}
			// Case 2: Pick the closer of nearest consumable food item and nearest machine
			WorldObjectInstance nearestFood = findNearestFoodItem(agent);
			WorldObjectInstance nearestMachine = findNearestMachine(agent);
			if (nearestFood == null) {
				nearestFood = nearestMachine;
			} else if (nearestMachine != null) {
				double fdx = nearestFood.getX() - agent.getX(), fdy = nearestFood.getY() - agent.getY();
				double mdx = nearestMachine.getX() - agent.getX(), mdy = nearestMachine.getY() - agent.getY();
				if (mdx * mdx + mdy * mdy < fdx * fdx + fdy * fdy) nearestFood = nearestMachine;
			}
			if (nearestFood != null) {
				double dx = nearestFood.getX() - agent.getX();
				double dy = nearestFood.getY() - agent.getY();
				double dist = Math.sqrt(dx * dx + dy * dy) / TILE_SIZE;
				if (dist <= 4.0) {
					String verb = (nearestFood.getProperties() != null
						&& Boolean.TRUE.equals(nearestFood.getProperties().get("usable"))) ? "use" : "carry";
					LOG.info("[SurvivalOverride] {} {}ing '{}' (hp={}, dist={}t)", agent.getFullName(), verb, nearestFood.getName(), agent.getHealth(), (int)dist);
					applyTurnResult(agent, TurnResult.committed(verb, nearestFood.getInstanceId(), "object", null, null, new ArrayList<>()));
				} else {
					String foodLoc = nearestFood.getLocation() != null ? nearestFood.getLocation() : "";
					if (!foodLoc.isBlank()) {
						world.getLocation(foodLoc).ifPresent(loc -> agent.setTargetLocation(loc.getFullPath()));
					}
					agent.setCurrentActivity("seeking food");
					LOG.info("[SurvivalOverride] {} navigating to '{}' (hp={}, dist={}t)", agent.getFullName(), nearestFood.getName(), agent.getHealth(), (int)dist);
					applyTurnResult(agent, TurnResult.committed("wait", "self", "agent", null, null, new ArrayList<>()));
				}
				return;
			}
		}
		// === End survival override ===

		// Defer new LLM submissions while a prior cognition is still running.
		// (Placed after the survival override so critically injured agents always act.)
		if (cognitionInFlight.contains(agent.getFullName())) {
			LOG.debug("[AgenticLoop] {} cognition in flight — deferring reasoning this turn",
				agent.getFullName());
			return;
		}

		AgentTurnContext ctx = buildAgentTurnContext(agent, now);
		AtomicReference<TurnResult> resultRef = new AtomicReference<>();
		final int currentTurn = turnCounter.get();
		submitAsyncCognition(agent,
			() -> {
				AgentToolExecutor executor = new AgentToolExecutor(
					world, objectInstances, objectTypeDefinitions, currentTurn);
				resultRef.set(new AgentTurnRunner(llm, executor, ctx).run());
			},
			() -> {
				TurnResult r = resultRef.get();
				if (r != null) {
					applyTurnResult(agent, r);
				} else {
					LOG.warn("[AgenticLoop] Null TurnResult for {} — no-op this turn",
						agent.getFullName());
				}
			},
			() -> LOG.warn("[AgenticLoop] Tool loop job failed for {} — no-op this turn",
				agent.getFullName()));
		return;
		// ── End Agentic Tool Loop ─────────────────────────────────────────────────
	}

	/**
	 * Legacy goal-evaluation state machine — kept for reference until the
	 * AgentTurnRunner tool loop is validated in production.
	 * Not called from runAgenticLoop(); remove in a cleanup pass once stable.
	 */
	@SuppressWarnings("unused")
	private void runLegacyStateMachine(Agent agent, RuntimeAgentState runtimeState,
			AgenticRuntimeState state, LocalDateTime now,
			RuntimeOrchestrationRequest request, PlayerActionRequest lastPlayerAction) {
		try {
			switch (state.phase) {
				case IDLE -> {
					// ── Resume plan: pop the next sub-goal without re-evaluating ──
					if (!state.goalPlan.isEmpty()) {
						AgenticGoal nextStep = state.goalPlan.poll();
						state.activeGoal = nextStep;
						state.lastError = null;
						LOG.info("[Agentic] {} resuming plan step: '{}' target={}",
							agent.getFullName(), nextStep.actionDescription, nextStep.targetId);
						if (isWithinInteractionRange(agent, nextStep)) {
							executeInteraction(agent, nextStep, state, now);
						} else {
							transitionAgenticPhase(state, AgenticPhase.MOVING_TO_TARGET, now);
							moveTowardTarget(agent, nextStep);
						}
						return;
					}

					// ── Normal goal evaluation ────────────────────────────────────
					PerceptionSnapshot perception = buildPerceptionSnapshot(agent, request);
					AgenticGoal goal = evaluateGoalFromPerception(agent, state, perception, now);
					if (goal == null) return;
					state.activeGoal = goal;
					state.lastInitiativeScore = goal.priority;
					state.lastError = null;

					// ── Sub-goal decomposition for TOOL_ACTION goals ──────────────
					// Only attempt when 4+ legal actions are available (richer world state)
					List<String> legalForPlan = agent.getLegalActions();
					if ("TOOL_ACTION".equals(goal.type)
							&& legalForPlan != null && legalForPlan.size() >= 4) {
						List<AgenticGoal> plan = decomposeIntoSubGoals(agent, goal);
						if (plan != null && plan.size() > 1) {
							state.activeGoal = plan.get(0);
							for (int i = 1; i < plan.size(); i++) {
								state.goalPlan.add(plan.get(i));
							}
							LOG.info("[Agentic] {} plan decomposed into {} steps", agent.getFullName(), plan.size());
						}
					}

					if (isWithinInteractionRange(agent, state.activeGoal)) {
						executeInteraction(agent, state.activeGoal, state, now);
					} else {
						transitionAgenticPhase(state, AgenticPhase.MOVING_TO_TARGET, now);
						moveTowardTarget(agent, state.activeGoal);
						LOG.info("[Agentic] {} goal={} target={} topic=\"{}\" priority={} phase=MOVING_TO_TARGET",
							agent.getFullName(), goal.type, goal.targetId, goal.topic,
							String.format("%.2f", goal.priority));
					}
				}
				case MOVING_TO_TARGET -> {
					if (state.activeGoal == null) {
						transitionAgenticPhase(state, AgenticPhase.IDLE, now);
						return;
					}
					PerceptionSnapshot perception = buildPerceptionSnapshot(agent, request);
					if (!isGoalTargetVisible(state.activeGoal, perception)
						&& !refreshGoalTargetFromLastSeen(state, now)) {
						abandonGoal(agent, state, now);
						return;
					}
					if (state.activeGoal.targetIsMobile) {
						refreshGoalTargetSnapshot(state.activeGoal, perception);
					}
					moveTowardTarget(agent, state.activeGoal);
					if (isWithinInteractionRange(agent, state.activeGoal)) {
						executeInteraction(agent, state.activeGoal, state, now);
					}
				}
				case AWAITING_OUTCOME -> {
					if (state.activeGoal == null) {
						transitionAgenticPhase(state, AgenticPhase.IDLE, now);
						return;
					}
					evaluatePendingOutcome(agent, state.activeGoal, state, now, request, lastPlayerAction);
				}
				case COOLDOWN -> {
					if (state.cooldownUntil == null || now == null || !now.isBefore(state.cooldownUntil)) {
						transitionAgenticPhase(state, AgenticPhase.IDLE, now);
						state.activeGoal = null;
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

	private WorldObjectInstance findNearestFoodItem(Agent agent) {
		return objectInstances.values().stream()
			.filter(obj -> {
				if (obj.getProperties() == null) return false;
				if (!Boolean.TRUE.equals(obj.getProperties().get("consumable"))) return false;
				if (Boolean.TRUE.equals(obj.getProperties().get("is_trash"))) return false;
				String heldBy = obj.getHeldBy();
				return heldBy == null || heldBy.isBlank();
			})
			.min(Comparator.comparingDouble(obj -> {
				double dx = obj.getX() - agent.getX();
				double dy = obj.getY() - agent.getY();
				return dx * dx + dy * dy;
			}))
			.orElse(null);
	}

	private WorldObjectInstance findNearestMachine(Agent agent) {
		return objectInstances.values().stream()
			.filter(obj -> {
				if (obj.getProperties() == null) return false;
				return Boolean.TRUE.equals(obj.getProperties().get("usable"))
					&& obj.getProperties().containsKey("produces_item");
			})
			.min(Comparator.comparingDouble(obj -> {
				double dx = obj.getX() - agent.getX();
				double dy = obj.getY() - agent.getY();
				return dx * dx + dy * dy;
			}))
			.orElse(null);
	}

	// -------------------------------------------------------------------------
	// Perception layer
	// -------------------------------------------------------------------------

	/**
	 * Builds a snapshot of every entity the agent can perceive this turn.
	 * Currently includes other agents and the player within AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE.
	 * Extend this method to add objects, sounds, or events.
	 */
	private PerceptionSnapshot buildPerceptionSnapshot(Agent agent, RuntimeOrchestrationRequest request) {
		PerceptionSnapshot snapshot = new PerceptionSnapshot();
		if (agent == null) return snapshot;
		snapshot.agentLocationPath = agent.getLocation() != null ? agent.getLocation().getFullPath() : null;
		AgenticRuntimeState runtimeState = agenticStateByAgent.computeIfAbsent(agent.getFullName(), k -> new AgenticRuntimeState());
		LocalDateTime now = SimulationTime.now();

		for (Agent candidate : world.getAgents()) {
			if (candidate.getFullName().equals(agent.getFullName())) continue;

			double cx, cy;
			if (candidate instanceof Player && request != null
					&& request.getPlayerX() != null && request.getPlayerY() != null) {
				cx = request.getPlayerX();
				cy = request.getPlayerY();
			} else {
				cx = candidate.getX();
				cy = candidate.getY();
			}

			int dist = tileManhattanDistance(agent.getX(), agent.getY(), cx, cy);
			if (dist > AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE) continue;
			if (!hasLineOfSight(agent.getX(), agent.getY(), cx, cy)) continue;

			PerceptionEntry entry = new PerceptionEntry();
			entry.entityId   = candidate.getFullName();
			entry.entityType = (candidate instanceof Player) ? "player" : "agent";
			entry.x          = cx;
			entry.y          = cy;
			entry.distance   = dist;
			entry.locationPath = candidate.getLocation() != null ? candidate.getLocation().getFullPath() : null;
			entry.isMobile   = true;
			snapshot.visible.add(entry);
			rememberPerceptionEntry(runtimeState, entry, now);
		}

		for (WorldObjectInstance object : objectInstances.values()) {
			if (object == null || isObjectHeld(object)) {
				continue;
			}
			int dist = tileManhattanDistance(agent.getX(), agent.getY(), object.getX(), object.getY());
			if (dist > AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE) {
				continue;
			}
			if (!hasLineOfSight(agent.getX(), agent.getY(), object.getX(), object.getY())) continue;
			PerceptionEntry entry = new PerceptionEntry();
			entry.entityId = object.getId();
			entry.entityType = "object";
			entry.x = object.getX();
			entry.y = object.getY();
			entry.distance = dist;
			entry.locationPath = object.getLocation();
			entry.isMobile = false;
			snapshot.visible.add(entry);
			rememberPerceptionEntry(runtimeState, entry, now);
		}
		return snapshot;
	}

	private void rememberPerceptionEntry(AgenticRuntimeState state, PerceptionEntry entry, LocalDateTime now) {
		if (state == null || entry == null || entry.entityId == null || entry.entityId.isBlank()) {
			return;
		}
		KnowledgeEntry knowledge = new KnowledgeEntry();
		knowledge.values = List.of(
			entry.entityType == null ? "" : entry.entityType,
			entry.locationPath == null ? "" : entry.locationPath,
			String.valueOf(entry.x),
			String.valueOf(entry.y),
			String.valueOf(entry.isMobile)
		);
		knowledge.confidence = 1.0;
		knowledge.updatedAt = now == null ? SimulationTime.now() : now;
		knowledge.source = "perception";
		state.knowledge.put("last_seen:" + entry.entityId, knowledge);
	}

	private List<PerceptionEntry> getRememberedPerceptionEntries(Agent agent, AgenticRuntimeState state, LocalDateTime now,
			Set<String> alreadyVisibleIds) {
		List<PerceptionEntry> remembered = new ArrayList<>();
		if (agent == null || state == null || state.knowledge == null) {
			return remembered;
		}
		for (Map.Entry<String, KnowledgeEntry> kv : state.knowledge.entrySet()) {
			String key = kv.getKey();
			KnowledgeEntry seen = kv.getValue();
			if (key == null || !key.startsWith("last_seen:")) {
				continue;
			}
			String entityId = key.substring("last_seen:".length());
			if (entityId.isBlank() || (alreadyVisibleIds != null && alreadyVisibleIds.contains(entityId))) {
				continue;
			}
			if (seen == null || !seen.isFresh(now, AGENTIC_LAST_SEEN_TTL_MINUTES) || seen.values == null || seen.values.size() < 5) {
				continue;
			}
			String entityType = seen.values.get(0);
			if ("object".equals(entityType)) {
				WorldObjectInstance instance = objectInstances.get(entityId);
				if (instance == null || isObjectHeld(instance)) {
					continue;
				}
			}

			PerceptionEntry entry = new PerceptionEntry();
			entry.entityId = entityId;
			entry.entityType = entityType;
			entry.locationPath = seen.values.get(1);
			entry.x = asDouble(seen.values.get(2), agent.getX());
			entry.y = asDouble(seen.values.get(3), agent.getY());
			entry.isMobile = asBoolean(seen.values.get(4), false);
			entry.distance = tileManhattanDistance(agent.getX(), agent.getY(), entry.x, entry.y);
			remembered.add(entry);
		}
		return remembered;
	}

	// -------------------------------------------------------------------------
	// Goal evaluation — perception → goal
	// -------------------------------------------------------------------------

	/**
	 * Examines the perception snapshot and returns the highest-priority goal the
	 * agent should pursue, or null if nothing warrants action this turn.
	 *
	 * To add a new goal type: add a new scoring branch inside the per-entry loop
	 * and create the corresponding AgenticGoal with the right type string.
	 */
	private AgenticGoal evaluateGoalFromPerception(Agent agent, AgenticRuntimeState state,
			PerceptionSnapshot perception, LocalDateTime now) {
		if (state.phase != AgenticPhase.IDLE) return null;
		if (state.cooldownUntil != null && now != null && now.isBefore(state.cooldownUntil)) return null;
		if (perception.visible.isEmpty()) return null;

		Set<String> visibleIds = perception.visible.stream()
			.map(e -> e.entityId)
			.filter(id -> id != null && !id.isBlank())
			.collect(Collectors.toSet());

		List<PerceptionEntry> candidates = new ArrayList<>(perception.visible);
		candidates.addAll(getRememberedPerceptionEntries(agent, state, now, visibleIds));
		if (candidates.isEmpty()) {
			return null;
		}

		ToolActionCandidate bestCandidate = null;
		for (ToolActionCandidate candidate : compileToolActionCandidates(agent, state, candidates, now, visibleIds)) {
			if (bestCandidate == null || candidate.score > bestCandidate.score) {
				bestCandidate = candidate;
			}
		}

		if (bestCandidate == null || bestCandidate.score < AGENTIC_MIN_GOAL_PRIORITY) {
			return null;
		}
		return buildGoalFromToolCandidate(agent, bestCandidate);
	}

	private List<ToolActionCandidate> compileToolActionCandidates(
			Agent actor,
			AgenticRuntimeState state,
			List<PerceptionEntry> entries,
			LocalDateTime now,
			Set<String> visibleIds) {
		List<ToolActionCandidate> candidates = new ArrayList<>();
		if (entries == null || entries.isEmpty()) {
			return candidates;
		}

		for (PerceptionEntry entry : entries) {
			if (entry == null || entry.entityId == null || entry.entityId.isBlank()) {
				continue;
			}
			boolean visibleNow = visibleIds != null && visibleIds.contains(entry.entityId);
			double memoryPenalty = visibleNow ? 0.0 : 0.15;

			if ("player".equals(entry.entityType) || "agent".equals(entry.entityType)) {
				Agent target = world.getAgent(entry.entityId).orElse(null);
				if (target == null) {
					continue;
				}

				double socialBase = computeSocialInitiativeScore(actor, target, entry.distance);
				double socialAdjust = computeSocialAppraisalAdjustment(actor, target, state, now);
				double socialScore = clamp01(socialBase + socialAdjust - memoryPenalty);
				ToolActionCandidate speak = new ToolActionCandidate();
				speak.actionType = "speak";
				speak.actionDescription = "Speak with " + entry.entityId;
				speak.actionFlair = "social";
				speak.targetId = entry.entityId;
				speak.targetType = entry.entityType;
				speak.targetIsMobile = true;
				speak.targetX = entry.x;
				speak.targetY = entry.y;
				speak.targetLocation = entry.locationPath;
				speak.reason = "personality+appraisal+proximity";
				speak.score = socialScore;
				candidates.add(speak);

				ToolActionCandidate observe = new ToolActionCandidate();
				observe.actionType = "interact";
				observe.actionDescription = "Observe " + entry.entityId;
				observe.actionFlair = "observe";
				observe.targetId = entry.entityId;
				observe.targetType = entry.entityType;
				observe.targetIsMobile = true;
				observe.targetX = entry.x;
				observe.targetY = entry.y;
				observe.targetLocation = entry.locationPath;
				observe.reason = "low-risk information gathering";
				observe.score = clamp01((socialBase * 0.55) + ((1.0 - actor.getFearfulness()) * 0.25) - memoryPenalty);
				candidates.add(observe);

				ToolActionCandidate attack = new ToolActionCandidate();
				attack.actionType = "attack";
				attack.actionDescription = "Attack " + entry.entityId;
				attack.actionFlair = "aggressive";
				attack.targetId = entry.entityId;
				attack.targetType = entry.entityType;
				attack.targetIsMobile = true;
				attack.targetX = entry.x;
				attack.targetY = entry.y;
				attack.targetLocation = entry.locationPath;
				attack.reason = "aggression-driven branch";
				attack.score = clamp01((actor.getAggression() * 0.65) + (actor.getImpulsivity() * 0.25) - (actor.getCompassion() * 0.4) - memoryPenalty);
				candidates.add(attack);
				continue;
			}

			if (!"object".equals(entry.entityType)) {
				continue;
			}
			WorldObjectInstance object = objectInstances.get(entry.entityId);
			if (object == null || isObjectHeld(object)) {
				continue;
			}

			for (Map<String, Object> action : buildObjectAffordanceActions(object, entry.distance, actor)) {
				if (action == null) {
					continue;
				}
				Map<String, Object> payload = action.get("payload") instanceof Map<?, ?> map
					? (Map<String, Object>) map
					: new HashMap<>();
				ToolActionCandidate candidate = new ToolActionCandidate();
				candidate.actionType = String.valueOf(action.getOrDefault("actionType", "interact"));
				candidate.actionDescription = String.valueOf(payload.getOrDefault("actionDescription", action.getOrDefault("hint", "Interacting")));
				candidate.actionFlair = String.valueOf(payload.getOrDefault("flair", ""));
				candidate.targetId = object.getId();
				candidate.targetType = "object";
				candidate.targetIsMobile = false;
				candidate.targetX = object.getX();
				candidate.targetY = object.getY();
				candidate.targetLocation = object.getLocation();
				candidate.reason = "affordance from perceived tools";
				candidate.score = scoreObjectToolAction(actor, entry, candidate, memoryPenalty);
				candidates.add(candidate);
			}
		}

		return candidates;
	}

	private double scoreObjectToolAction(Agent actor, PerceptionEntry entry, ToolActionCandidate candidate, double memoryPenalty) {
		double proximity = clamp01(1.0 - (entry.distance / AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE));
		double score = (proximity * 0.45) + ((1.0 - actor.getFearfulness()) * 0.15) + (actor.getImpulsivity() * 0.10);
		String desc = candidate.actionDescription == null ? "" : candidate.actionDescription.toLowerCase();
		String flair = candidate.actionFlair == null ? "" : candidate.actionFlair.toLowerCase();

		if (desc.contains("steal") || desc.contains("carry") || flair.contains("action:carry")) {
			score += (actor.getAggression() * 0.10) + (actor.getRiskTolerance() * 0.20);
			score -= actor.getCompassion() * 0.12;
		}
		if (desc.contains("place") || flair.contains("action:place_object")) {
			score += (actor.getCompassion() * 0.12) + (actor.getLoyalty() * 0.08);
		}
		if (desc.contains("open") || desc.contains("close")) {
			score += actor.getRiskTolerance() * 0.08;
		}
		if (desc.contains("inspect") || desc.contains("observe")) {
			score += (1.0 - actor.getImpulsivity()) * 0.08;
		}

		score -= memoryPenalty;
		return clamp01(score);
	}

	private AgenticGoal buildGoalFromToolCandidate(Agent agent, ToolActionCandidate candidate) {
		AgenticGoal goal = new AgenticGoal();
		goal.type = "speak".equalsIgnoreCase(candidate.actionType) ? "SOCIAL_CONTACT" : "TOOL_ACTION";
		goal.targetId = candidate.targetId;
		goal.targetType = candidate.targetType;
		goal.targetIsMobile = candidate.targetIsMobile;
		goal.snapshotX = candidate.targetX;
		goal.snapshotY = candidate.targetY;
		goal.snapshotLocation = candidate.targetLocation;
		goal.actionType = candidate.actionType;
		goal.actionDescription = candidate.actionDescription;
		goal.actionFlair = candidate.actionFlair;
		if ("SOCIAL_CONTACT".equals(goal.type)) {
			goal.topic = buildConversationTopic(agent, candidate.targetId);
			// Generate the opener now — before the approach — so the agent "knows what to say"
			// as they walk over, rather than constructing it at the last second.
			goal.opener = generateSocialOpener(agent, candidate.targetId, goal.topic);
		} else {
			goal.topic = candidate.actionDescription;
		}
		goal.description = "goal from tools/personality: " + candidate.actionDescription + " -> " + candidate.targetId;
		goal.priority = candidate.score;
		return goal;
	}

	/** Scores how motivated this agent is to initiate social contact with a target. */
	private double computeSocialInitiativeScore(Agent agent, Agent target, double distance) {
		if (agent == null || target == null) return 0.0;
		double proximity = clamp01(1.0 - (distance / AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE));
		double sociability = clamp01(
			(agent.getCompassion() * 0.35)
			+ (agent.getSocialDominance() * 0.35)
			+ (agent.getRiskTolerance() * 0.2)
			+ ((1.0 - agent.getFearfulness()) * 0.1));
		return clamp01((proximity * 0.45) + (sociability * 0.55));
	}

	// -------------------------------------------------------------------------
	// Target resolution helpers
	// -------------------------------------------------------------------------

	/**
	 * Returns the current location full-path of a goal's target.
	 * For mobile targets (agents/players) this is re-resolved from the world each call
	 * so pathing always tracks the target's real position.
	 * For stationary targets this returns the snapshot location that was set at goal creation.
	 */
	private String resolveTargetCurrentLocation(AgenticGoal goal) {
		if (goal == null) return null;
		if (goal.targetIsMobile) {
			Agent target = world.getAgent(goal.targetId).orElse(null);
			if (target != null && target.getLocation() != null) {
				return target.getLocation().getFullPath();
			}
		}
		return goal.snapshotLocation;
	}

	/** Updates the cached position snapshot for a mobile goal target from the latest perception data. */
	private void refreshGoalTargetSnapshot(AgenticGoal goal, PerceptionSnapshot perception) {
		if (goal == null || perception == null) return;
		for (PerceptionEntry entry : perception.visible) {
			if (goal.targetId.equalsIgnoreCase(entry.entityId)) {
				goal.snapshotX        = entry.x;
				goal.snapshotY        = entry.y;
				goal.snapshotLocation = entry.locationPath;
				return;
			}
		}
	}

	/** Returns true if the goal's target is present in the agent's current perception snapshot. */
	private boolean isGoalTargetVisible(AgenticGoal goal, PerceptionSnapshot perception) {
		if (goal == null || perception == null) return false;
		return perception.visible.stream()
			.anyMatch(e -> goal.targetId.equalsIgnoreCase(e.entityId));
	}

	private boolean refreshGoalTargetFromLastSeen(AgenticRuntimeState state, LocalDateTime now) {
		if (state == null || state.activeGoal == null || state.activeGoal.targetId == null || state.activeGoal.targetId.isBlank()) {
			return false;
		}
		KnowledgeEntry seen = state.knowledge.get("last_seen:" + state.activeGoal.targetId);
		if (seen == null || !seen.isFresh(now, AGENTIC_LAST_SEEN_TTL_MINUTES) || seen.values == null || seen.values.size() < 4) {
			return false;
		}
		state.activeGoal.snapshotLocation = seen.values.get(1);
		state.activeGoal.snapshotX = asDouble(seen.values.get(2), state.activeGoal.snapshotX);
		state.activeGoal.snapshotY = asDouble(seen.values.get(3), state.activeGoal.snapshotY);
		return true;
	}

	/**
	 * Returns true when the acting agent is within interaction range of the goal's target.
	 * Requires same location AND Manhattan tile distance <= AGENTIC_INITIATE_TILE_DISTANCE.
	 */
	private boolean isWithinInteractionRange(Agent agent, AgenticGoal goal) {
		if (agent == null || goal == null || agent.getLocation() == null) return false;
		String targetLocPath = resolveTargetCurrentLocation(goal);
		if (targetLocPath == null) return false;
		if (!targetLocPath.equals(agent.getLocation().getFullPath())) return false;
		double tx = goal.snapshotX, ty = goal.snapshotY;
		if (goal.targetIsMobile) {
			Agent target = world.getAgent(goal.targetId).orElse(null);
			if (target != null) { tx = target.getX(); ty = target.getY(); }
		}
		return tileManhattanDistance(agent.getX(), agent.getY(), tx, ty) <= AGENTIC_INITIATE_TILE_DISTANCE;
	}

	// -------------------------------------------------------------------------
	// Movement
	// -------------------------------------------------------------------------

	/**
	 * Points the agent toward its goal's target.
	 * For mobile targets the backend location is re-resolved each turn so pathfinding
	 * always tracks a moving entity.  The activity string embeds the target name so
	 * the Godot client can extract it and use the correct entity anchor.
	 */
	private void moveTowardTarget(Agent agent, AgenticGoal goal) {
		if (agent == null || goal == null) return;
		String targetLocation = resolveTargetCurrentLocation(goal);
		if (targetLocation != null) {
			agent.setTargetLocation(targetLocation);
		}
		String intent = goal.actionDescription;
		if ((intent == null || intent.isBlank()) && goal.topic != null && !goal.topic.isBlank()) {
			intent = "discuss " + goal.topic;
		}
		String activity = (intent != null && !intent.isBlank())
			? "agentic: moving toward " + goal.targetId + " to " + intent
			: "agentic: moving toward " + goal.targetId;
		agent.setCurrentActivity(activity);
	}

	// -------------------------------------------------------------------------
	// Interaction dispatch
	// -------------------------------------------------------------------------

	/**
	 * Dispatches to the goal-type-specific interaction handler.
	 * Add new cases here as new goal types are introduced.
	 */
	private void executeInteraction(Agent agent, AgenticGoal goal, AgenticRuntimeState state, LocalDateTime now) {
		if (agent == null || goal == null || state == null) return;
		switch (goal.type) {
			case "SOCIAL_CONTACT" -> {
				Agent target = world.getAgent(goal.targetId).orElse(null);
				if (target == null) { abandonGoal(agent, state, now); return; }
				executeSocialContactInteraction(agent, target, goal, state, now);
			}
			case "TOOL_ACTION" -> executeToolActionInteraction(agent, goal, state, now);
			default -> {
				LOG.warn("[Agentic] Unhandled goal type '{}' for agent {}", goal.type, agent.getFullName());
				abandonGoal(agent, state, now);
			}
		}
	}

	/**
	 * Maps the goal's flair/description to a canonical verb for ActionResolver.
	 * Flair tags (e.g. "action:carry") take priority over description pattern-matching.
	 */
	private String deriveVerbFromGoal(AgenticGoal goal) {
		String flair = goal.actionFlair == null ? "" : goal.actionFlair.toLowerCase();
		if (flair.contains("action:carry"))        return "carry";
		if (flair.contains("action:give"))         return "give";
		if (flair.contains("action:place_object")) return "place_object";
		if (flair.contains("action:write"))        return "write";
		if (flair.contains("action:unlock"))       return "unlock";
		if (flair.contains("action:lock"))         return "lock";
		if (flair.contains("action:open"))         return "open";
		if (flair.contains("action:close"))        return "close";
		if (flair.contains("action:study"))        return "study";
		if (flair.contains("action:light"))        return "light";
		if (flair.contains("action:inspect"))      return "inspect";
		if (flair.contains("action:use"))          return "use";

		String lower = goal.actionDescription == null ? "" : goal.actionDescription.toLowerCase();
		if (lower.contains("carry") || lower.contains("pick up") || lower.contains("steal")) return "carry";
		if (lower.startsWith("give") || lower.contains("hand over") || lower.contains("hand to")) return "give";
		if (lower.contains("place") || lower.contains("drop"))                                return "place_object";
		if (lower.startsWith("write") || lower.contains("writing on"))                        return "write";
		if (lower.contains("unlock") && !lower.contains(" lock"))                             return "unlock";
		if (lower.startsWith("lock") || lower.contains(" lock"))                              return "lock";
		if (lower.contains("open") && !lower.contains("close"))                               return "open";
		if (lower.contains("close"))                                                           return "close";
		if (lower.startsWith("stud"))                                                          return "study";
		if (lower.startsWith("light") || lower.startsWith("illuminat"))                        return "light";
		return "interact";
	}

	// ── Sub-goal decomposition ────────────────────────────────────────────────

	/**
	 * Uses an LLM call to break a TOOL_ACTION primary goal into 2–3 ordered sub-goals.
	 * Uses {@link SubGoalParser} to parse and verify steps against the current legal
	 * action list, ensuring only achievable actions are planned.
	 *
	 * Falls back to {@code null} (caller uses primary goal as-is) when:
	 * - The LLM call fails
	 * - Fewer than 2 legal steps are parsed (single-step goal; no benefit in decomposing)
	 * - An exception occurs
	 */
	private List<AgenticGoal> decomposeIntoSubGoals(Agent agent, AgenticGoal primaryGoal) {
		if (agent == null || primaryGoal == null) return null;
		List<String> legal = agent.getLegalActions();
		if (legal == null || legal.size() < 2) return null;

		StringBuilder prompt = new StringBuilder();
		prompt.append("You are ").append(agent.getFullName()).append(".\n");
		prompt.append("Personality: ").append(agent.getTraits()).append("\n");
		prompt.append("Current location: ").append(agent.getLocation() == null ? "unknown" : agent.getLocation().getFullPath()).append("\n");
		prompt.append("Goal: ").append(primaryGoal.actionDescription).append("\n\n");
		prompt.append("Actions you can take RIGHT NOW:\n");
		for (String a : legal) prompt.append("  - ").append(a).append("\n");
		prompt.append("\nBreak this goal into at most 3 ordered steps using ONLY the listed actions.\n");
		prompt.append("If 1 step is enough, give just 1 step. Never invent actions not in the list.\n\n");
		prompt.append("Respond ONLY with numbered lines (no extra text):\n");
		prompt.append("1. action(target) \u2014 reason\n");
		prompt.append("2. action(target) \u2014 reason\n");
		prompt.append("3. action(target) \u2014 reason");

		try {
			String raw = prompts.sendRawPrompt(prompt.toString(), 0.5);
			if (raw == null || raw.isBlank()) return null;

			List<ParsedStep> steps = SubGoalParser.parse(raw, legal);
			if (steps.size() < 2) return null; // single step — no decomposition benefit

			List<AgenticGoal> subGoals = new ArrayList<>();
			for (ParsedStep step : steps) {
				AgenticGoal sg = new AgenticGoal();
				sg.type = "speak".equalsIgnoreCase(step.verb) ? "SOCIAL_CONTACT" : "TOOL_ACTION";
				sg.actionType = step.verb;
				sg.actionDescription = step.reason;
				sg.actionFlair = "action:" + step.verb.toLowerCase();
				sg.description = step.reason;
				sg.priority = primaryGoal.priority;
				resolveSubGoalTarget(sg, step.targetName, primaryGoal);
				if ("SOCIAL_CONTACT".equals(sg.type)) {
					sg.topic = buildConversationTopic(agent, sg.targetId);
					sg.opener = generateSocialOpener(agent, sg.targetId, sg.topic);
				}
				subGoals.add(sg);
			}
			return subGoals;

		} catch (Exception e) {
			LOG.debug("[SubGoal] Decomposition skipped for {}: {}", agent.getFullName(), e.getMessage());
			return null;
		}
	}

	/**
	 * Resolves a sub-goal's target fields by looking up {@code targetName} in world
	 * objects and agents. Falls back to the primary goal's target if nothing matches.
	 */
	private void resolveSubGoalTarget(AgenticGoal sg, String targetName, AgenticGoal primary) {
		if (targetName != null && !targetName.isBlank()) {
			// Try world objects first (by name, case-insensitive)
			for (WorldObjectInstance obj : objectInstances.values()) {
				if (targetName.equalsIgnoreCase(obj.getName())
						|| targetName.equalsIgnoreCase(obj.getId())) {
					sg.targetId = obj.getId();
					sg.targetType = "object";
					sg.targetIsMobile = false;
					sg.snapshotX = obj.getX();
					sg.snapshotY = obj.getY();
					sg.snapshotLocation = obj.getLocation();
					return;
				}
			}
			// Try agents / players
			for (Agent a : world.getAgents()) {
				if (targetName.equalsIgnoreCase(a.getFullName())) {
					sg.targetId = a.getFullName();
					sg.targetType = a instanceof Player ? "player" : "agent";
					sg.targetIsMobile = true;
					sg.snapshotX = a.getX();
					sg.snapshotY = a.getY();
					sg.snapshotLocation = a.getLocation() == null ? null : a.getLocation().getFullPath();
					return;
				}
			}
		}
		// Fallback: inherit primary goal target
		sg.targetId = primary.targetId;
		sg.targetType = primary.targetType;
		sg.targetIsMobile = primary.targetIsMobile;
		sg.snapshotX = primary.snapshotX;
		sg.snapshotY = primary.snapshotY;
		sg.snapshotLocation = primary.snapshotLocation;
	}

	private void executeToolActionInteraction(Agent agent, AgenticGoal goal, AgenticRuntimeState state, LocalDateTime now) {
		if (agent == null || goal == null || state == null) {
			return;
		}
		String actionType = goal.actionType == null || goal.actionType.isBlank() ? "interact" : goal.actionType;
		String actionDesc = goal.actionDescription == null || goal.actionDescription.isBlank()
			? ("interact with " + goal.targetId)
			: goal.actionDescription;
		String flair = goal.actionFlair == null ? "" : goal.actionFlair.toLowerCase();

		if ("object".equals(goal.targetType)) {
			WorldObjectInstance objectTarget = resolveObjectTarget("object:" + goal.targetId);
			if (objectTarget == null) {
				abandonGoal(agent, state, now);
				return;
			}

			// Same-location guard — prevents cross-wall interactions that range alone won't catch
			if (agent.getLocation() != null && objectTarget.getLocation() != null
					&& !agent.getLocation().getFullPath().equals(objectTarget.getLocation())) {
				enterCooldown(state, now, "tool_action_blocked", 2);
				agent.setCurrentActivity("agentic: blocked — object not in same room");
				return;
			}

			// ActionResolver gate: enforce range, affordance, state compatibility, and grants
			String resolvedVerb = deriveVerbFromGoal(goal);
			WorldAction resolverAction = WorldAction.fromPlayerAction(
				agent.getFullName(), resolvedVerb,
				objectTarget.getId(), WorldAction.TargetType.OBJECT,
				null, null, agent.getX(), agent.getY(), turnCounter.get());
			ActionResolver.ResolveResult resolveResult = new ActionResolver(
				buildInventoryByActor(), objectInstances, objectTypeDefinitions).resolve(resolverAction);
			if (!resolveResult.permitted) {
				agent.getEpistemicMemory().ingestBeliefCorrection(
					turnCounter.get(), resolvedVerb, objectTarget.getName(),
					resolveResult.rejectReason,
					"I believed I could " + resolvedVerb + " " + objectTarget.getName(),
					resolveResult.explanation);
				agent.setCurrentActivity("agentic: blocked — " + resolveResult.explanation);
				LOG.debug("[AgentAction] {} rejected: {} {} — {}", agent.getFullName(),
					resolvedVerb, objectTarget.getName(), resolveResult.explanation);
				rejectGoalStep(agent, state, now);
				return;
			}
			// Consume item if the action required a consumable (e.g. a one-use key)
			if (resolveResult.itemToConsume != null) {
				LinkedHashSet<String> inv = getInventorySet(agent);
				inv.remove(resolveResult.itemToConsume.getId());
				refreshAgentCarriedItems(agent);
			}

			String lower = actionDesc.toLowerCase();
			if (flair.contains("action:unlock") || (lower.contains("unlock") && !lower.contains("lock"))) {
				objectTarget.getProperties().put("locked", false);
				objectTarget.getProperties().put("passable", true);
				actionDesc = "unlocked " + objectTarget.getName();
			} else if (flair.contains("action:lock") || lower.startsWith("lock") || lower.contains(" lock")) {
				objectTarget.getProperties().put("locked", true);
				objectTarget.getProperties().put("passable", false);
				actionDesc = "locked " + objectTarget.getName();
			} else if ("entrance_anchor".equalsIgnoreCase(objectTarget.getType())
				|| asBoolean(objectTarget.getProperties().get("transition_point"), false)
				|| containsTag(objectTarget.getProperties(), "entrance")
				|| containsTag(objectTarget.getProperties(), "door")) {
				if (lower.contains("close")) {
					objectTarget.getProperties().put("locked", true);
					objectTarget.getProperties().put("passable", false);
					objectTarget.getProperties().put("doorOpen", false);
					actionDesc = "closed " + objectTarget.getName();
				} else if (lower.contains("open")) {
					objectTarget.getProperties().put("locked", false);
					objectTarget.getProperties().put("passable", true);
					objectTarget.getProperties().put("doorOpen", true);
					actionDesc = "opened " + objectTarget.getName();
				}
			}

			if (lower.contains("carry") || lower.contains("steal") || flair.contains("action:carry")) {
				addObjectToInventory(agent, objectTarget);
				actionDesc = "picked up " + objectTarget.getName();
			}
			if (lower.contains("place object") || flair.contains("action:place_object")) {
				WorldObjectInstance placed = placeFirstInventoryObjectAt(agent, objectTarget);
				if (placed != null) {
					actionDesc = "placed " + placed.getName() + " near " + objectTarget.getName();
				}
			}

			// Write — agent generates text via LLM and stores it on the object
			if (flair.contains("action:write") || lower.startsWith("write") || lower.contains("writing on")) {
				if (objectTarget.getProperties() == null) objectTarget.setProperties(new HashMap<>());
				String written = generateAgentWritingContent(agent, objectTarget);
				objectTarget.getProperties().put("has_writing", written);
				actionDesc = "wrote on " + objectTarget.getName() + ": \"" + written + "\"";
			}

			// Study — calms agent and adds a memory observation
			if (flair.contains("action:study") || lower.startsWith("stud")) {
				agent.applyStressChange(-0.03);
				agent.getMemoryStream().add(new Observation("Studied " + objectTarget.getName()));
				actionDesc = "studied " + objectTarget.getName();
			}

			// Light — torch/illuminate action (cosmetic, small stress relief)
			if (flair.contains("action:light") || lower.startsWith("illuminat") || lower.startsWith("light")) {
				agent.applyStressChange(-0.01);
				actionDesc = "lit " + objectTarget.getName();
			}

			// Use — consumable item or production machine
			if (flair.contains("action:use") || lower.startsWith("use") || lower.startsWith("eat") || lower.startsWith("drink")) {
				String useNarrative = new WorldStateMutator(world, objectInstances)
					.apply(agent, "use", objectTarget.getInstanceId(),
						WorldAction.TargetType.OBJECT, null, turnCounter.get());
				if (useNarrative != null) {
					actionDesc = useNarrative;
				}
			}

			agent.setCurrentActivity("agentic: " + actionDesc);
			recordCommittedAction(agent, "TOOL_ACTION", objectTarget.getId() + " | " + actionDesc);
			agent.getMemoryStream().add(new Observation("Executed action: " + actionDesc));
			enterCooldown(state, now, "tool_action", 5);
			return;
		}

		Agent targetAgent = world.getAgent(goal.targetId).orElse(null);
		if (targetAgent == null) {
			abandonGoal(agent, state, now);
			return;
		}
		if (isAggressiveActionType(actionType.toLowerCase())) {
			targetAgent.applyStressChange(0.12);
			agent.applyStressChange(0.03);
			int damage = computeVerbDamage(agent, actionType.toLowerCase());
			targetAgent.applyDamage(damage);
			String hitDesc = buildCombatHitDescription(agent.getFullName(), actionType.toLowerCase(), damage);
			Observation agentAtkObs = new Observation(hitDesc);
			agentAtkObs.setImportance(9);
			targetAgent.getMemoryStream().add(agentAtkObs);
			ChronicleEvent agentAtkEvt = appendChronicle(
				agent.getFullName(), "agent", actionType.toLowerCase(),
				targetAgent.getFullName(), "agent",
				hitDesc, agent.getX(), agent.getY(),
				targetAgent.getX(), targetAgent.getY());
			targetAgent.getEpistemicMemory().ingestObserved(agentAtkEvt);
			triggerAttackedResponse(targetAgent, agent.getFullName(), agent);
			recordCommittedAction(agent, "ATTACK", actionType + " toward " + targetAgent.getFullName());
		} else {
			recordCommittedAction(agent, "INTERACT", actionDesc + " -> " + targetAgent.getFullName());
		}
		agent.setCurrentActivity("agentic: " + actionDesc);
		agent.getMemoryStream().add(new Observation("Executed action with " + targetAgent.getFullName() + ": " + actionDesc));
		enterCooldown(state, now, "tool_action", 4);
	}

	/** Generates context-appropriate writing content for an agent writing on an object. */
	private String generateAgentWritingContent(Agent agent, WorldObjectInstance target) {
		try {
			String contextPrompt = "You are " + agent.getFullName() + ". You are writing on " + target.getName()
				+ ". Based on your current activity (\"" + agent.getCurrentActivity() + "\") and personality, "
				+ "write 1-2 concise sentences that fit what your character would write here. Be in-character.";
			String written = prompts.ask(agent, contextPrompt);
			if (written != null && !written.isBlank()) {
				String clean = written.trim();
				if (clean.length() > 280) clean = clean.substring(0, 280);
				return clean;
			}
		} catch (Exception e) {
			LOG.warn("Failed to generate agent writing content: {}", e.getMessage());
		}
		return agent.getFullName() + " was here.";
	}

	/** Executes a SOCIAL_CONTACT interaction: creates a conversation opener and waits for outcome. */
	private void executeSocialContactInteraction(Agent agent, Agent target, AgenticGoal goal,
			AgenticRuntimeState state, LocalDateTime now) {
		// Use the opener generated at goal-commit time (when the agent decided to approach).
		// Fall back to template only if the stored opener is missing (shouldn't happen in normal flow).
		String opener = (goal.opener != null && !goal.opener.isBlank())
			? goal.opener
			: sanitizeDialogueText(buildPersonalityOpening(agent, target, goal.topic));
		if (opener != null && !opener.isBlank()) {
			List<Dialog> lines = new ArrayList<>();
			lines.add(new Dialog(agent.getFullName(), opener));
			world.create(new Conversation(agent.getFullName(), target.getFullName(), lines));
			recordConversationTurn(agent.getFullName(), target.getFullName(), opener, SimulationTime.now());
		}
		agent.setTargetLocation(null);
		agent.setCurrentActivity("agentic: speaking to " + target.getFullName());
		recordCommittedAction(agent, "SPEAK", "initiated conversation with " + target.getFullName());
		agent.getMemoryStream().add(new Observation(
			"Initiated conversation with " + target.getFullName() + ": " + opener));
		transitionAgenticPhase(state, AgenticPhase.AWAITING_OUTCOME, now);
		state.chatWindowClosedObserved = false;
		state.pinnedLastTurn = false;
		state.deferredTurns = 0;
		state.lastInitiatedAt = now;
		state.lastOutcome = "initiated";
		LOG.info("[Agentic] {} initiated social contact with {}", agent.getFullName(), target.getFullName());
	}

	// -------------------------------------------------------------------------
	// Outcome evaluation
	// -------------------------------------------------------------------------

	/**
	 * Dispatches to the goal-type-specific outcome evaluator.
	 * Add new cases as new goal types are introduced.
	 */
	private void evaluatePendingOutcome(
			Agent agent,
			AgenticGoal goal,
			AgenticRuntimeState state,
			LocalDateTime now,
			RuntimeOrchestrationRequest request,
			PlayerActionRequest lastPlayerAction) {
		if (agent == null || goal == null || state == null) return;
		switch (goal.type) {
			case "SOCIAL_CONTACT" -> evaluateSocialContactOutcome(agent, goal, state, now, request, lastPlayerAction);
			case "TOOL_ACTION" -> enterCooldown(state, now, "tool_action_completed", 3);
			default -> enterCooldown(state, now, "unknown_goal_type");
		}
	}

	/** Evaluates whether a SOCIAL_CONTACT interaction has been responded to or timed out. */
	private void evaluateSocialContactOutcome(
			Agent agent,
			AgenticGoal goal,
			AgenticRuntimeState state,
			LocalDateTime now,
			RuntimeOrchestrationRequest request,
			PlayerActionRequest lastPlayerAction) {
		// For now all social contact is with the player; re-resolve them each frame
		Agent player = findPrimaryPlayer();
		if (player == null) { enterCooldown(state, now, "no_player"); return; }

		state.chatWindowClosedObserved = false;
		state.pinnedLastTurn = request != null && request.isPinned(agent.getFullName());

		if (isReplyToAgent(lastPlayerAction, player, agent)) {
			String replyText = lastPlayerAction.getSpeakText() == null ? "" : lastPlayerAction.getSpeakText();
			SocialReplyKind replyKind = classifySocialReply(replyText);
			state.lastRepliedAt = now;

			if (replyKind == SocialReplyKind.REJECTING || replyKind == SocialReplyKind.HOSTILE) {
				agent.setCurrentActivity("agentic: acknowledged boundary");
				state.recentIgnoreCount = Math.min(6, state.recentIgnoreCount + (replyKind == SocialReplyKind.HOSTILE ? 2 : 1));
				state.socialFriction = clamp01(state.socialFriction + (replyKind == SocialReplyKind.HOSTILE ? 0.45 : 0.30));
				state.lastOutcome = replyKind == SocialReplyKind.HOSTILE ? "rebuked" : "declined";
				agent.getMemoryStream().add(new Observation(
					"Player set a conversation boundary: " + summarizeTextForMemory(replyText)));
				recordSocialEpisode(agent, goal.targetId, state.lastOutcome, goal.topic, replyText, now);
				long boundaryCooldown = computeBoundaryCooldownMinutes(agent, replyKind, state);
				enterCooldown(state, now, state.lastOutcome, boundaryCooldown);
				LOG.info("[Agentic] {} social outcome={} target={} cooldown={}m",
					agent.getFullName(), state.lastOutcome, goal.targetId, boundaryCooldown);
				return;
			}

			agent.setCurrentActivity("agentic: engaged in conversation");
			state.lastOutcome = (replyKind == SocialReplyKind.POSITIVE) ? "success" : "neutral";
			state.recentIgnoreCount = Math.max(0, state.recentIgnoreCount - 1);
			state.socialFriction = clamp01(state.socialFriction - (replyKind == SocialReplyKind.POSITIVE ? 0.20 : 0.08));
			if (!replyText.isBlank()) {
				agent.getMemoryStream().add(new Observation("Player replied: " + summarizeTextForMemory(replyText)));
			}
			recordSocialEpisode(agent, goal.targetId, state.lastOutcome, goal.topic, replyText, now);
			enterCooldown(state, now, state.lastOutcome);
			LOG.info("[Agentic] {} social outcome={} target={}", agent.getFullName(), state.lastOutcome, goal.targetId);
			return;
		}

		if (isMoveAwayAction(lastPlayerAction, player, agent)) {
			agent.setCurrentActivity("agentic: conversation declined");
			state.recentIgnoreCount = Math.min(5, state.recentIgnoreCount + 1);
			state.socialFriction = clamp01(state.socialFriction + 0.18);
			state.lastOutcome = "ignored";
			agent.getMemoryStream().add(new Observation("Player disengaged by moving away during conversation."));
			recordSocialEpisode(agent, goal.targetId, "ignored", goal.topic, "moved away", now);
			long ignoreCooldown = computeBoundaryCooldownMinutes(agent, SocialReplyKind.REJECTING, state);
			enterCooldown(state, now, "ignored", ignoreCooldown);
			LOG.info("[Agentic] {} social outcome=ignored target={}", agent.getFullName(), goal.targetId);
			return;
		}

		if (calculateDistance(player, agent) > AGENTIC_DISENGAGE_TILE_DISTANCE) {
			agent.setCurrentActivity("agentic: conversation lapsed");
			state.recentIgnoreCount = Math.min(5, state.recentIgnoreCount + 1);
			state.socialFriction = clamp01(state.socialFriction + 0.10);
			state.lastOutcome = "lapsed";
			recordSocialEpisode(agent, goal.targetId, "lapsed", goal.topic, "out of range", now);
			enterCooldown(state, now, "lapsed", 8);
			LOG.info("[Agentic] {} social outcome=lapsed target={}", agent.getFullName(), goal.targetId);
			return;
		}

		if (lastPlayerAction != null && player.getFullName().equals(lastPlayerAction.getPlayerId())) {
			state.deferredTurns++;
		}

		if (state.deferredTurns >= AGENTIC_MAX_DEFERRED_TURNS) {
			agent.setCurrentActivity("agentic: deferred conversation");
			state.socialFriction = clamp01(state.socialFriction + 0.12);
			state.lastOutcome = "deferred";
			recordSocialEpisode(agent, goal.targetId, "deferred", goal.topic, "no response", now);
			enterCooldown(state, now, "deferred");
			LOG.info("[Agentic] {} social outcome=deferred target={}", agent.getFullName(), goal.targetId);
		}
	}

	private SocialReplyKind classifySocialReply(String text) {
		if (text == null || text.isBlank()) {
			return SocialReplyKind.NEUTRAL;
		}
		String t = text.toLowerCase().trim();
		if (containsAny(t, "shut up", "leave me alone", "stop talking", "go away", "i said stop", "back off")) {
			return SocialReplyKind.HOSTILE;
		}
		if (containsAny(t, "no", "not now", "don't want to talk", "do not want to talk", "please stop", "later", "busy")) {
			return SocialReplyKind.REJECTING;
		}
		if (containsAny(t, "thanks", "thank you", "sure", "okay", "ok", "yes", "sounds good", "good idea", "appreciate")) {
			return SocialReplyKind.POSITIVE;
		}
		return SocialReplyKind.NEUTRAL;
	}

	private boolean containsAny(String value, String... terms) {
		if (value == null || terms == null) {
			return false;
		}
		for (String term : terms) {
			if (term != null && !term.isBlank() && value.contains(term)) {
				return true;
			}
		}
		return false;
	}

	private long computeBoundaryCooldownMinutes(Agent agent, SocialReplyKind kind, AgenticRuntimeState state) {
		if (agent == null) {
			return AGENTIC_SOCIAL_COOLDOWN_MINUTES;
		}
		double persistence = clamp01(
			(agent.getSocialDominance() * 0.40)
			+ (agent.getRiskTolerance() * 0.25)
			+ (agent.getImpulsivity() * 0.20)
			+ (agent.getAggression() * 0.15));
		double boundaryRespect = clamp01(
			(agent.getCompassion() * 0.50)
			+ ((1.0 - agent.getAggression()) * 0.20)
			+ ((1.0 - agent.getSocialDominance()) * 0.30));

		long base = AGENTIC_SOCIAL_COOLDOWN_MINUTES;
		if (kind == SocialReplyKind.HOSTILE) {
			base += 25;
		} else if (kind == SocialReplyKind.REJECTING) {
			base += 12;
		}

		long personalityDelta = Math.round((boundaryRespect * 22.0) - (persistence * 18.0));
		long frictionDelta = state == null ? 0 : Math.round((state.socialFriction + (state.recentIgnoreCount * 0.12)) * 12.0);
		long result = base + personalityDelta + frictionDelta;
		return Math.max(8, Math.min(120, result));
	}

	private String summarizeTextForMemory(String text) {
		if (text == null) {
			return "";
		}
		String cleaned = text.replace("\n", " ").replace("\r", " ").trim();
		cleaned = cleaned.replaceAll("\\s+", " ");
		if (cleaned.length() > 120) {
			return cleaned.substring(0, 117) + "...";
		}
		return cleaned;
	}

	private void recordSocialEpisode(Agent agent, String targetId, String outcome, String topic, String playerReply, LocalDateTime now) {
		if (agent == null || targetId == null || targetId.isBlank()) {
			return;
		}
		SocialEpisode episode = new SocialEpisode();
		episode.target = targetId;
		episode.outcome = outcome == null ? "unknown" : outcome;
		episode.topic = topic == null ? "" : topic;
		episode.playerReply = summarizeTextForMemory(playerReply);
		episode.createdAt = now == null ? SimulationTime.now() : now;
		episode.summary = buildEpisodeSummaryLine(episode);

		Map<String, Deque<SocialEpisode>> byTarget = socialEpisodesByAgent.computeIfAbsent(agent.getFullName(), k -> new ConcurrentHashMap<>());
		Deque<SocialEpisode> episodes = byTarget.computeIfAbsent(targetId, k -> new ArrayDeque<>());
		episodes.addFirst(episode);
		while (episodes.size() > MAX_SOCIAL_EPISODES_PER_TARGET) {
			episodes.removeLast();
		}

		AgenticRuntimeState runtimeState = agenticStateByAgent.computeIfAbsent(agent.getFullName(), k -> new AgenticRuntimeState());
		runtimeState.knowledge.remove("social_appraisal:" + targetId);
	}

	private void recordConversationTurn(String speaker, String listener, String text, LocalDateTime now) {
		if (speaker == null || listener == null || speaker.isBlank() || listener.isBlank()) {
			return;
		}
		String cleanedText = sanitizeDialogueText(text);
		if (cleanedText == null || cleanedText.isBlank()) {
			return;
		}
		String pairKey = conversationPairKey(speaker, listener);
		Deque<ConversationTurn> turns = conversationTurnsByPair.computeIfAbsent(pairKey, k -> new ArrayDeque<>());
		ConversationTurn turn = new ConversationTurn();
		turn.speaker = speaker;
		turn.listener = listener;
		turn.text = cleanedText;
		turn.createdAt = now == null ? SimulationTime.now() : now;
		turns.addFirst(turn);
		while (turns.size() > MAX_CONVERSATION_TURNS_PER_PAIR) {
			turns.removeLast();
		}
	}

	private String getRecentConversationTranscript(String a, String b, int limit) {
		if (a == null || b == null || a.isBlank() || b.isBlank()) {
			return "";
		}
		Deque<ConversationTurn> turns = conversationTurnsByPair.get(conversationPairKey(a, b));
		if (turns == null || turns.isEmpty()) {
			return "";
		}
		List<ConversationTurn> ordered = turns.stream().limit(Math.max(1, limit)).collect(Collectors.toList());
		java.util.Collections.reverse(ordered);
		StringBuilder transcript = new StringBuilder();
		for (ConversationTurn turn : ordered) {
			if (transcript.length() > 0) {
				transcript.append("\n");
			}
			transcript.append(turn.speaker).append(": ").append(turn.text);
		}
		return transcript.toString();
	}

	private String conversationPairKey(String a, String b) {
		String left = a.trim().toLowerCase();
		String right = b.trim().toLowerCase();
		return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
	}

	private String buildEpisodeSummaryLine(SocialEpisode episode) {
		if (episode == null) {
			return "";
		}
		StringBuilder summary = new StringBuilder();
		summary.append("Outcome=").append(episode.outcome);
		if (episode.topic != null && !episode.topic.isBlank()) {
			summary.append(", topic=").append(summarizeTextForMemory(episode.topic));
		}
		if (episode.playerReply != null && !episode.playerReply.isBlank()) {
			summary.append(", reply=").append(episode.playerReply);
		}
		return summary.toString();
	}

	private String getRecentSocialEpisodeDigest(String agentName, String targetId, int limit) {
		Map<String, Deque<SocialEpisode>> byTarget = socialEpisodesByAgent.get(agentName);
		if (byTarget == null) {
			return "";
		}
		Deque<SocialEpisode> episodes = byTarget.get(targetId);
		if (episodes == null || episodes.isEmpty()) {
			return "";
		}
		StringBuilder digest = new StringBuilder();
		int count = 0;
		for (SocialEpisode episode : episodes) {
			if (count++ >= limit) {
				break;
			}
			if (digest.length() > 0) {
				digest.append(" | ");
			}
			digest.append(episode.summary);
		}
		return digest.toString();
	}

	private double computeSocialAppraisalAdjustment(Agent actor, Agent target, AgenticRuntimeState state, LocalDateTime now) {
		if (actor == null || target == null || state == null) {
			return 0.0;
		}
		String key = "social_appraisal:" + target.getFullName();
		KnowledgeEntry cached = state.knowledge.get(key);
		if (cached != null && cached.isFresh(now, SOCIAL_APPRAISAL_TTL_MINUTES) && cached.values != null && !cached.values.isEmpty()) {
			return clampToRange(asDouble(cached.values.get(0), 0.0), -0.25, 0.25);
		}

		String digest = getRecentSocialEpisodeDigest(actor.getFullName(), target.getFullName(), 4);
		if (digest.isBlank()) {
			return 0.0;
		}

		double adjustment = 0.0;
		String rationale = "episode trends";
		try {
			String prompt = "Given this interaction history between " + actor.getFullName() + " and " + target.getFullName()
				+ ", return a single number between -1 and 1 where -1 means avoid initiating and 1 means strongly initiate."
				+ " History: " + digest + " Respond exactly like: score=<number>; reason=<short reason>.";
			String llmReply = prompts.ask(actor, prompt);
			adjustment = clampToRange(extractFirstDouble(llmReply), -1.0, 1.0) * 0.25;
			rationale = summarizeTextForMemory(llmReply);
		} catch (Exception e) {
			LOG.warn("[Agentic] social appraisal ask failed for {}->{}: {}", actor.getFullName(), target.getFullName(), e.getMessage());
			adjustment = heuristicSocialAdjustmentFromDigest(digest);
			rationale = "heuristic fallback";
		}

		KnowledgeEntry stored = new KnowledgeEntry();
		stored.values = List.of(String.valueOf(adjustment));
		stored.confidence = 0.7;
		stored.updatedAt = now == null ? SimulationTime.now() : now;
		stored.source = rationale;
		state.knowledge.put(key, stored);
		return adjustment;
	}

	private double heuristicSocialAdjustmentFromDigest(String digest) {
		if (digest == null || digest.isBlank()) {
			return 0.0;
		}
		String lowered = digest.toLowerCase();
		int positive = 0;
		int negative = 0;
		if (containsAny(lowered, "success", "neutral")) {
			positive += 1;
		}
		if (containsAny(lowered, "declined", "rebuked", "ignored", "deferred")) {
			negative += 2;
		}
		double raw = (positive - negative) * 0.08;
		return clampToRange(raw, -0.22, 0.22);
	}

	private double extractFirstDouble(String value) {
		if (value == null || value.isBlank()) {
			return 0.0;
		}
		String cleaned = value.replace(',', '.');
		StringBuilder token = new StringBuilder();
		for (int i = 0; i < cleaned.length(); i++) {
			char c = cleaned.charAt(i);
			boolean numeric = (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.';
			if (numeric) {
				token.append(c);
			} else if (token.length() > 0) {
				try {
					return Double.parseDouble(token.toString());
				} catch (NumberFormatException ignored) {
				}
				token.setLength(0);
			}
		}
		if (token.length() > 0) {
			try {
				return Double.parseDouble(token.toString());
			} catch (NumberFormatException ignored) {
			}
		}
		return 0.0;
	}

	private double clampToRange(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private String sanitizeDialogueText(String text) {
		if (text == null) {
			return "";
		}
		String cleaned = text.replace("\r", " ").replace("\n", " ").trim();
		if (cleaned.contains("```") && cleaned.lastIndexOf("```") > cleaned.indexOf("```")) {
			int firstFence = cleaned.indexOf("```");
			int lastFence = cleaned.lastIndexOf("```");
			if (lastFence > firstFence) {
				cleaned = cleaned.substring(firstFence + 3, lastFence).trim();
			}
		}
		cleaned = cleaned.replace("```json", " ").replace("```", " ");
		cleaned = cleaned.replace("**", " ").replace("__", " ").replace("`", " ");
		cleaned = cleaned.replaceAll("(?i)^\\s*(assistant|system|user|npc|agent)\\s*:\\s*", "");
		cleaned = cleaned.replaceAll("(?i)^\\s*response\\s*:\\s*", "");
		cleaned = cleaned.replaceAll("(?i)^\\s*answer\\s*:\\s*", "");
		cleaned = cleaned.replaceAll("(?i)^\\s*activity\\s*:\\s*", "");
		cleaned = cleaned.replaceAll("(?i)^\\s*location\\s*:\\s*", "");
		cleaned = cleaned.replaceAll("(?i)^\\s*emoji\\s*:\\s*", "");
		cleaned = cleaned.replaceAll("(?i)^\\s*\\{\\s*\"?message\"?\\s*:\\s*", "");
		cleaned = cleaned.replaceAll("(?i)\\s*location\\s*:\\s*[^.?!;]+", "");
		cleaned = cleaned.replaceAll("(?i)\\s*emoji\\s*:\\s*[^.?!;]+", "");
		cleaned = cleaned.replaceAll("(?i)^\\s*activity\\s*:\\s*", "");
		cleaned = cleaned.replaceAll("\\s+", " ").trim();
		if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
			cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
		}
		if (cleaned.endsWith("}")) {
			int idx = cleaned.indexOf(':');
			if (idx > 0 && cleaned.startsWith("{")) {
				cleaned = cleaned.substring(idx + 1, cleaned.length() - 1).replace("\"", "").trim();
			}
		}
		if (cleaned.length() > 220) {
			cleaned = cleaned.substring(0, 217).trim() + "...";
		}
		return cleaned;
	}

	private String sanitizeActivityText(String text) {
		String cleaned = sanitizeDialogueText(text);
		if (cleaned == null || cleaned.isBlank()) {
			return "";
		}
		cleaned = cleaned.replaceAll("(?i)^\\s*[*_`#>]+\\s*", "");
		cleaned = cleaned.replaceAll("(?i)^\\s*[A-Za-z][A-Za-z'\\- ]+\\s+plan\\s*\\([^)]*\\)\\s*:\\s*", "");
		cleaned = cleaned.replaceAll("(?i)^\\s*\\d{1,2}:\\d{2}\\s*[AaPp][Mm]\\s*(?:[-–—]|to)?\\s*\\d{1,2}:\\d{2}\\s*[AaPp][Mm]\\s*[:\\-]*\\s*", "");
		cleaned = cleaned.replaceAll("^\\s*[-*•]+\\s*", "");
		cleaned = cleaned.replaceAll("^\\s*\\d+\\s*[.)-:]\\s*", "");
		cleaned = cleaned.replaceAll("^\\s*[:*\\-]+\\s*", "");
		cleaned = cleaned.replaceAll("\\s+", " ").trim();
		if (cleaned.length() > 140) {
			cleaned = cleaned.substring(0, 137).trim() + "...";
		}
		return cleaned;
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
		return calculateDistance(player, agent) > AGENTIC_DISENGAGE_TILE_DISTANCE;
	}

	/** Clears the active goal (and any queued sub-goals) and silently returns the agent to IDLE. */
	private void abandonGoal(Agent agent, AgenticRuntimeState state, LocalDateTime now) {
		if (agent != null) agent.setTargetLocation(null);
		if (state != null) {
			state.activeGoal = null;
			state.goalPlan.clear();
			transitionAgenticPhase(state, AgenticPhase.IDLE, now);
			state.lastOutcome = "abandoned";
		}
	}

	/**
	 * Called when ActionResolver rejects an agent's tool action.
	 * Clears the remaining sub-goal plan (the plan is now invalid) and enters
	 * a short cooldown so the agent can re-evaluate with updated beliefs.
	 */
	private void rejectGoalStep(Agent agent, AgenticRuntimeState state, LocalDateTime now) {
		if (state != null) state.goalPlan.clear();
		if (agent != null) agent.setTargetLocation(null);
		enterCooldown(state, now, "action_rejected", 2);
	}

	/**
	 * Puts the agent in COOLDOWN.  Cooldown prevents the agent from starting a new
	 * goal until the cooldown period has elapsed.  Generalised from the old social-only version.
	 */
	private void enterCooldown(AgenticRuntimeState state, LocalDateTime now, String outcome) {
		enterCooldown(state, now, outcome, AGENTIC_SOCIAL_COOLDOWN_MINUTES);
	}

	private void enterCooldown(AgenticRuntimeState state, LocalDateTime now, String outcome, long cooldownMinutes) {
		if (state == null) return;
		long minutes = Math.max(1, cooldownMinutes);
		transitionAgenticPhase(state, AgenticPhase.COOLDOWN, now);
		state.cooldownUntil = now == null ? null : now.plusMinutes(minutes);
		state.activeGoal = null;
		state.chatWindowClosedObserved = false;
		state.pinnedLastTurn = false;
		state.deferredTurns = 0;
		state.lastOutcome = outcome;
	}

	private String buildPersonalityOpening(Agent agent, Agent player) {
		return buildPersonalityOpening(agent, player, null);
	}

	private String buildPersonalityOpening(Agent agent, Agent player, String topic) {
		String playerName = player == null ? "there" : player.getFullName();
		boolean hasTopic = topic != null && !topic.isBlank();
		if (agent.getFearfulness() >= 0.7) {
			return hasTopic
				? "Hey " + playerName + ", I tracked you down — about " + topic + "..."
				: "Hey " + playerName + ", quick check-in... is everything okay around here?";
		}
		if (agent.getSocialDominance() >= 0.7) {
			return hasTopic
				? playerName + ", got a minute? Need your take on " + topic + "."
				: playerName + ", got a minute? I want your take on something.";
		}
		if (agent.getCompassion() >= 0.65) {
			return hasTopic
				? "Hi " + playerName + ", I've been thinking about " + topic + ". Wanted to talk."
				: "Hi " + playerName + ", how are you holding up today?";
		}
		if (agent.getImpulsivity() >= 0.7) {
			return hasTopic
				? "Hey " + playerName + "! I can't stop thinking about " + topic + " — can we talk?"
				: "Hey " + playerName + "! You won't believe what I was just thinking about.";
		}
		return hasTopic
			? "Hey " + playerName + ", mind if we talk about " + topic + "?"
			: "Hey " + playerName + ", want to chat for a moment?";
	}

	private String buildConversationTopic(Agent agent) {
		return buildConversationTopic(agent, null);
	}

	private String buildConversationTopic(Agent agent, String targetName) {
		if (agent == null) {
			return null;
		}
		// Pull the most recent substantive observation as a conversation seed,
		// preferring memories that mention the target if one is given.
		List<Memory> memories = agent.getMemoryStream().getMemories();
		String lowerTarget = targetName != null ? targetName.toLowerCase() : "";
		// First pass: memories that mention the target
		if (!lowerTarget.isBlank()) {
			for (int i = memories.size() - 1; i >= Math.max(0, memories.size() - 12); i--) {
				String desc = memories.get(i).getDescription();
				if (desc != null && !isSystemMemory(desc) && desc.length() > 12
						&& desc.toLowerCase().contains(lowerTarget)) {
					String cleaned = sanitizeConversationTopic(desc);
					if (!cleaned.isBlank()) return cleaned;
				}
			}
		}
		// Second pass: any recent substantive non-system memory
		for (int i = memories.size() - 1; i >= Math.max(0, memories.size() - 6); i--) {
			String desc = memories.get(i).getDescription();
			if (desc != null && !isSystemMemory(desc) && desc.length() > 12) {
				String cleaned = sanitizeConversationTopic(desc);
				if (!cleaned.isBlank()) return cleaned;
			}
		}
		// Fall back to the agent's current scheduled activity if meaningful
		String activity = agent.getCurrentActivity();
		if (activity != null && !activity.isBlank()
				&& !activity.toLowerCase().contains("agentic")
				&& !activity.toLowerCase().contains("idle")
				&& !activity.toLowerCase().contains("routine")) {
			String cleaned = sanitizeConversationTopic(activity);
			if (!cleaned.isBlank()) return cleaned;
		}
		// Personality-based default topic
		if (agent.getCompassion() >= 0.65) return "how things have been going";
		if (agent.getSocialDominance() >= 0.7) return "something on my mind";
		if (agent.getRiskTolerance() >= 0.6) return "something unusual I've noticed lately";
		return "recent happenings around here";
	}

	/** Returns true for system-generated memory strings that should never surface as conversation topics. */
	private boolean isSystemMemory(String desc) {
		if (desc == null) return true;
		String lower = desc.toLowerCase().stripLeading();
		return lower.startsWith("executed action:")
			|| lower.startsWith("instinct response:")
			|| lower.startsWith("deterministic response:")
			|| lower.startsWith("agentic ")
			|| lower.contains("initiated conversation")
			|| lower.startsWith("going to ")
			|| lower.startsWith("following routine");
	}

	/**
	 * Generates a natural conversation opener via LLM at the moment the agent commits to
	 * approaching the target. Falls back to the personality template on any LLM failure.
	 */
	private String generateSocialOpener(Agent agent, String targetName, String topic) {
		Agent target = world.getAgent(targetName).orElse(null);
		// Gather up to 3 relevant memories (target-related, then any recent non-system)
		List<String> relevantMemories = new ArrayList<>();
		List<Memory> memories = agent.getMemoryStream().getMemories();
		String lowerTarget = targetName != null ? targetName.toLowerCase() : "";
		for (int i = memories.size() - 1; i >= Math.max(0, memories.size() - 15) && relevantMemories.size() < 3; i--) {
			String d = memories.get(i).getDescription();
			if (d != null && !isSystemMemory(d) && d.length() > 8
					&& (lowerTarget.isBlank() || d.toLowerCase().contains(lowerTarget))) {
				relevantMemories.add(d);
			}
		}

		StringBuilder prompt = new StringBuilder();
		prompt.append("You are ").append(agent.getFullName()).append(".\n");
		String personality = describePersonality(agent);
		if (!personality.isBlank()) {
			prompt.append("Your personality: ").append(personality).append(".\n");
		}
		if (!relevantMemories.isEmpty()) {
			prompt.append("Things on your mind: ").append(String.join("; ", relevantMemories)).append(".\n");
		}
		if (topic != null && !topic.isBlank()) {
			prompt.append("You want to talk to ").append(targetName).append(" about: ").append(topic).append(".\n");
		} else {
			prompt.append("You want to greet or chat briefly with ").append(targetName).append(".\n");
		}
		prompt.append("Write exactly ONE sentence — the first thing you would actually say. ");
		prompt.append("Sound natural for your personality. No quotes, no stage directions, no extra explanation.");

		try {
			String raw = prompts.sendRawPrompt(prompt.toString(), 0.85);
			String cleaned = sanitizeDialogueText(raw);
			if (cleaned != null && !cleaned.isBlank() && cleaned.length() <= 220) {
				return cleaned;
			}
		} catch (Exception e) {
			LOG.warn("[Agentic] LLM opener failed for {} → {}: {}", agent.getFullName(), targetName, e.getMessage());
		}
		return sanitizeDialogueText(buildPersonalityOpening(agent, target, topic));
	}

	private String describePersonality(Agent agent) {
		List<String> traits = new ArrayList<>();
		if (agent.getCompassion() >= 0.65) traits.add("compassionate");
		if (agent.getSocialDominance() >= 0.7) traits.add("assertive");
		if (agent.getFearfulness() >= 0.7) traits.add("anxious");
		if (agent.getImpulsivity() >= 0.7) traits.add("impulsive");
		if (agent.getAggression() >= 0.6) traits.add("direct");
		if (agent.getLoyalty() >= 0.7) traits.add("loyal");
		if (agent.getRiskTolerance() >= 0.6) traits.add("bold");
		String base = agent.getTraits() != null && !agent.getTraits().isBlank() ? agent.getTraits() : "";
		if (!traits.isEmpty()) {
			base = (base.isBlank() ? "" : base + "; ") + String.join(", ", traits);
		}
		return base;
	}

	private String sanitizeConversationTopic(String raw) {
		if (raw == null) {
			return "";
		}
		String topic = raw.replace("\n", " ").replace("\r", " ").trim();
		topic = topic.replace("```", " ").replace("**", " ").replace("__", " ").replace("`", " ");
		topic = topic.replace("--", " ");
		topic = topic.replaceAll("(?i)\\bbecause of\\b.*$", "");
		topic = topic.replaceAll("\\s+", " ").trim();
		if (topic.startsWith("-")) {
			topic = topic.substring(1).trim();
		}
		if (topic.length() > 64) {
			topic = topic.substring(0, 61).trim() + "...";
		}
		return topic;
	}

	private double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	private void applyDeterministicReactiveFallback(Agent agent, ReactiveEvent event) {
		InstinctDecision decision = evaluateInstinctDecision(agent, event);
		if (decision != null) {
			agent.applyStressChange(decision.stressDelta);
			recordStressEventIfSignificant(agent, event.description);
			agent.setCurrentActivity(sanitizeActivityText(decision.activity));
			if (decision.targetLocation != null && !decision.targetLocation.isBlank()) {
				agent.setTargetLocation(decision.targetLocation);
			}
			recordCommittedAction(agent, decision.action, decision.reason);
			agent.getMemoryStream().add(new Observation("Instinct response: " + decision.action + " because " + decision.reason));
			return;
		}
		// Fallback when no instinct decision matched
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
		return state.phase == AgenticPhase.MOVING_TO_TARGET
			|| state.phase == AgenticPhase.AWAITING_OUTCOME;
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
			double distance = tileManhattanDistance(agent.getX(), agent.getY(), location.getCenterX(), location.getCenterY());
			double score = distance - ("public".equalsIgnoreCase(location.getType()) ? 2.0 : 0.0);
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
			double distance = calculateDistance(agent, other);
			if (distance > AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", other.getFullName());
			row.put("location", other.getLocation() == null ? null : other.getLocation().getFullPath());
			row.put("distanceTiles", distance);
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
		Agent player = findPrimaryPlayer();
		if (player != null) {
			packet.put("recentSocialEpisodesWithPlayer",
				getRecentSocialEpisodeDigest(agent.getFullName(), player.getFullName(), 4));
			packet.put("recentTranscriptWithPlayer",
				getRecentConversationTranscript(agent.getFullName(), player.getFullName(), 8));
		}

		String replyStyle = buildPersonalityReplyStyle(agent);

		// Build a readable memory context block for combat/significant events.
		// Kept outside the packet map so it renders as plain prose, not Java-map noise.
		StringBuilder memoryBlock = new StringBuilder();

		// High-importance or combat-related MemoryStream observations
		List<String> significantMemories = agent.getMemoryStream().getMemories().stream()
			.filter(m -> m.getImportance() >= 6
				|| m.getDescription().toLowerCase().contains("attack")
				|| m.getDescription().toLowerCase().contains("punch")
				|| m.getDescription().toLowerCase().contains("kick")
				|| m.getDescription().toLowerCase().contains("tackl")
				|| m.getDescription().toLowerCase().contains("threw"))
			.sorted(Comparator.comparingDouble(m -> -m.getImportance()))
			.limit(5)
			.map(io.github.nickm980.smallville.memory.Memory::getDescription)
			.collect(Collectors.toList());

		// Recent EpistemicMemory combat events involving this agent (all combat verbs)
		List<String> combatHistory = agent.getEpistemicMemory().recentObserved(15).stream()
			.filter(e -> isAggressiveActionType(e.verb)
				&& (agent.getFullName().equals(e.targetId) || agent.getFullName().equals(e.actorId)))
			.map(io.github.nickm980.smallville.entities.EpistemicMemory.ObservedEvent::toNarrative)
			.collect(Collectors.toList());

		if (!significantMemories.isEmpty() || !combatHistory.isEmpty()) {
			memoryBlock.append("\nYour memories you must not deny or ignore:\n");
			for (String m : significantMemories) {
				memoryBlock.append("- ").append(m).append("\n");
			}
			for (String c : combatHistory) {
				memoryBlock.append("- ").append(c).append("\n");
			}
		}

		return "You must stay consistent with committed actions and current world state. "
			+ "Here is a compact environment packet: " + packet.toString()
			+ memoryBlock
			+ "\nReply style constraints: " + replyStyle
			+ "\nRespond in 1-2 sentences, in-character, with no markdown or role labels."
			+ "\nPlayer says: " + playerQuestion;
	}

	private String buildPersonalityReplyStyle(Agent agent) {
		if (agent == null) {
			return "neutral, concise, and grounded";
		}

		List<String> tones = new ArrayList<>();
		if (agent.getFearfulness() >= 0.7) {
			tones.add("cautious");
		}
		if (agent.getSocialDominance() >= 0.7) {
			tones.add("assertive");
		}
		if (agent.getCompassion() >= 0.65) {
			tones.add("warm");
		}
		if (agent.getImpulsivity() >= 0.7) {
			tones.add("energetic");
		}
		if (agent.getAggression() >= 0.7) {
			tones.add("blunt");
		}

		if (tones.isEmpty()) {
			tones.add("calm");
		}

		return String.join(", ", tones);
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
				agent.setCurrentActivity(sanitizeActivityText(active.getGoal()));
			} else {
				agent.setCurrentActivity(sanitizeActivityText("heading to " + active.getLocation() + " for " + active.getGoal()));
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
				agent.setCurrentActivity(sanitizeActivityText("getting ready for " + upcoming.getGoal()));
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
		if (agent.hasPendingActions()) {
			return;
		}
		if (agent.hasPendingActions()) {
			return;
		}
		Plan currentPlan = findCurrentPlan(agent);
		if (currentPlan == null) {
			return;
		}

		String description = currentPlan.getDescription();
		String activity = stripLeadingTime(description);
		String sanitizedActivity = sanitizeActivityText(activity);
		if (!sanitizedActivity.isBlank()) {
			agent.setCurrentActivity(sanitizedActivity);
		}
		Location scheduledLocation = findMentionedLocation(description);
		queuePlannedActions(agent, activity, scheduledLocation);
	}

	private void queuePlannedActions(Agent agent, String activity, Location scheduledLocation) {
		List<AgentAction> actions = new ArrayList<>();
		if (scheduledLocation != null && (agent.getLocation() == null
				|| !scheduledLocation.getFullPath().equals(agent.getLocation().getFullPath()))) {
			AgentAction move = new AgentAction("move", "Going to " + scheduledLocation.getFullPath());
			move.setTargetLocation(scheduledLocation.getFullPath());
			actions.add(move);
		}
		if (activity != null && !activity.isBlank()) {
			AgentAction perform = new AgentAction("activity", activity);
			perform.setTargetLocation(scheduledLocation == null ? null : scheduledLocation.getFullPath());
			actions.add(perform);
		}
		if (!actions.isEmpty()) {
			agent.replaceActionQueue(actions);
			agent.setCurrentActivity(actions.getFirst().getDescription());
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

	private void advanceAgentMovement(Agent agent) {
		AgentAction activeAction = agent.getActiveAction();
		boolean justPromoted = (activeAction == null);
		if (activeAction == null) {
			activeAction = agent.startNextAction();
		}
		if (activeAction == null) {
			if (agent.hasBeenOrchestrated()) {
				stepAgentRoutine(agent);
			}
			return;
		}

		if (activeAction.getEmoji() != null && !activeAction.getEmoji().isBlank()) {
			agent.setCurrentEmoji(activeAction.getEmoji());
		}
		if (activeAction.getDescription() != null && !activeAction.getDescription().isBlank()) {
			agent.setCurrentActivity(activeAction.getDescription());
		}

		String type = activeAction.getType() == null ? "activity" : activeAction.getType().toLowerCase();
		if ("move".equals(type)) {
			if (!agent.hasBeenOrchestrated()) {
				return;
			}
			Location targetLocation = resolveTargetLocation(agent, activeAction);
			if (targetLocation == null) {
				agent.completeActiveAction();
				return;
			}
			double targetX;
			double targetY;
			if (activeAction.getTargetX() != null && activeAction.getTargetY() != null) {
				targetX = snapToTile(clamp(activeAction.getTargetX(), targetLocation.getMinX(), targetLocation.getMaxX()));
				targetY = snapToTile(clamp(activeAction.getTargetY(), targetLocation.getMinY(), targetLocation.getMaxY()));
			} else if (agent.getLocation() != null && targetLocation.getFullPath().equals(agent.getLocation().getFullPath())) {
				targetX = snapToTile(targetLocation.getCenterX());
				targetY = snapToTile(targetLocation.getCenterY());
			} else {
				targetX = snapToTile(clamp(agent.getX(), targetLocation.getMinX(), targetLocation.getMaxX()));
				targetY = snapToTile(clamp(agent.getY(), targetLocation.getMinY(), targetLocation.getMaxY()));
			}
			boolean arrived = stepAgentToward(agent, targetX, targetY, targetLocation);
			if (arrived) {
				agent.completeActiveAction();
			}
			return;
		}

		if ("activity".equals(type)) {
			// Activity actions persist for one full turn before completing,
			// so the client can observe the active action in the turn it starts.
			if (!justPromoted) {
				agent.completeActiveAction();
			}
			return;
		}

		completeWorldAction(agent, activeAction);
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
			case 0: nextX += TILE_SIZE; break;
			case 1: nextX -= TILE_SIZE; break;
			case 2: nextY += TILE_SIZE; break;
			default: nextY -= TILE_SIZE; break;
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

	private Location resolveTargetLocation(Agent agent, AgentAction action) {
		String targetName = action.getTargetLocation();
		if (targetName == null || targetName.isBlank()) {
			return null;
		}

		agent.setTargetLocation(targetName);
		Location target = world.getLocation(targetName).orElse(null);
		if (target == null) {
			agent.setTargetLocation(null);
			return null;
		}

		if (agent.getLocation() != null && agent.getLocation().getFullPath().equals(target.getFullPath())
				&& toTile(agent.getX()) == toTile(action.getTargetX() == null ? target.getCenterX() : action.getTargetX())
				&& toTile(agent.getY()) == toTile(action.getTargetY() == null ? target.getCenterY() : action.getTargetY())) {
			agent.setTargetLocation(null);
			return null;
		}

		return target;
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
		return new ArrayList<>(sanitized);
	}

	private boolean stepAgentToward(Agent agent, double targetX, double targetY, Location targetLocation) {
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
			return true;
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
			Location candidateLocation = findLocationAt(nextX, nextY);
			if (findBlockingObjectAtTile(candidateLocation != null ? candidateLocation : targetLocation, nextX, nextY) != null) {
				continue;
			}
			agent.setPosition(nextX, nextY);
			Location locationAtNewPosition = findLocationAt(nextX, nextY);
			if (locationAtNewPosition != null) {
				agent.setLocation(locationAtNewPosition);
				if (locationAtNewPosition.getFullPath().equals(targetLocation.getFullPath())
						&& toTile(nextX) == targetTileX && toTile(nextY) == targetTileY) {
					agent.setTargetLocation(null);
					return true;
				}
			}
			return false;
		}
		return false;
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

	private void completeWorldAction(Agent agent, AgentAction action) {
		String type = action.getType() == null ? "activity" : action.getType().toLowerCase();

		// ── ActionResolver gate for object-targeting NPC verbs ───────────────────
		String objectId = action.getTargetAgent();
		if (Set.of("carry", "write", "open", "close", "inspect").contains(type)
				&& objectId != null && objectInstances.containsKey(objectId)) {
			WorldAction wa = WorldAction.fromAgentAction(agent.getFullName(), action, 0);
			wa.setTargetId(objectId);
			wa.setTargetType(WorldAction.TargetType.OBJECT);
			wa.setActorX(agent.getX());
			wa.setActorY(agent.getY());

			ActionResolver.ResolveResult rr = new ActionResolver(
				buildInventoryByActor(), objectInstances, objectTypeDefinitions).resolve(wa);

			if (!rr.permitted) {
				LOG.info("[completeWorldAction] NPC {} '{}' on '{}' rejected: {}",
					agent.getFullName(), type, objectId, rr.explanation);
				agent.completeActiveAction();
				return;
			}

			// Apply state mutations on permit
			WorldObjectInstance obj = objectInstances.get(objectId);
			if (obj != null) {
				switch (type) {
					case "carry" -> {
						final double pickupX = obj.getX();
						final double pickupY = obj.getY();
						final String carriedName = obj.getName();
						obj.setHeldBy(agent.getFullName());
						if (obj.getProperties() == null) obj.setProperties(new HashMap<>());
						obj.getProperties().put("heldBy", agent.getFullName());
						obj.setLocation(null);
						obj.setX(agent.getX());
						obj.setY(agent.getY());
						// Nearby witnesses observe the item being taken
						world.getAgents().stream()
							.filter(a -> !(a instanceof Player) && !a.getFullName().equals(agent.getFullName()))
							.filter(a -> Math.sqrt(Math.pow(a.getX()-pickupX,2)+Math.pow(a.getY()-pickupY,2)) / TILE_SIZE <= 3.0)
							.forEach(witness -> appendChronicle(
								agent.getFullName(), "agent", "carry",
								carriedName, "object", carriedName,
								agent.getX(), agent.getY(), pickupX, pickupY));
					}
					case "write" -> {
						String text = action.getSpeakText() != null && !action.getSpeakText().isBlank()
							? action.getSpeakText() : action.getDescription();
						if (text != null && !text.isBlank()) {
							obj.getProperties().put("has_writing", text);
						}
					}
					case "open" -> {
						obj.setState("isOpen", true);
						obj.setState("isLocked", false);
						obj.getProperties().put("doorOpen", true);
						obj.getProperties().put("passable", true);
						obj.getProperties().put("locked", false);
					}
					case "close" -> {
						obj.setState("isOpen", false);
						obj.getProperties().put("doorOpen", false);
						obj.getProperties().put("passable", false);
					}
				}
			}
		}

		// ── Give verb: agent-to-agent item transfer ───────────────────────────────
		if ("give".equals(type)) {
			String recipientId = action.getTargetAgent();
			String itemId = action.getItem();
			if (recipientId != null && !recipientId.isBlank() && itemId != null && !itemId.isBlank()) {
				WorldAction wa = WorldAction.fromAgentAction(agent.getFullName(), action, 0);
				wa.setTargetId(recipientId);
				wa.setTargetType(WorldAction.TargetType.AGENT);
				wa.setItemId(itemId);
				wa.setActorX(agent.getX());
				wa.setActorY(agent.getY());

				ActionResolver.ResolveResult rr = new ActionResolver(
					buildInventoryByActor(), objectInstances, objectTypeDefinitions).resolve(wa);

				if (!rr.permitted) {
					LOG.info("[give] NPC {} give '{}' to '{}' rejected: {}",
						agent.getFullName(), itemId, recipientId, rr.explanation);
				} else {
					// Transfer item from actor to recipient
					InventoryItem transferred = agent.removeInventoryItem(itemId);
					if (transferred != null) {
						Agent recipient = world.getAgent(recipientId).orElse(null);
						if (recipient != null) {
							recipient.addInventoryItem(transferred);
							// Sync carried item name lists so LLM prompts stay accurate
							refreshAgentCarriedItems(agent);
							refreshAgentCarriedItems(recipient);
							// Notify recipient via hearsay so they know they received something
							recipient.getEpistemicMemory().ingestHearsay(
								agent.getFullName(),
								agent.getFullName() + " gave you " + transferred.getDisplayName(),
								0, 0.95);
							// Chronicle so bystanders also observe the exchange
							appendChronicle(
								agent.getFullName(), "agent", "give",
								recipient.getFullName(), "agent",
								transferred.getDisplayName(),
								agent.getX(), agent.getY(),
								recipient.getX(), recipient.getY());
							LOG.info("[give] {} gave '{}' ({}) to {}",
								agent.getFullName(), transferred.getDisplayName(), itemId, recipientId);
						} else {
							LOG.warn("[give] Recipient agent '{}' not found — item lost", recipientId);
						}
					}
				}
			}
		}

		// ── Throw verb: NPC throws an item at a target ───────────────────────────
		if ("throw".equals(type)) {
			String throwTargetName = action.getTargetAgent();
			Agent throwTarget = throwTargetName != null ? world.getAgent(throwTargetName).orElse(null) : null;
			double throwTargetX = throwTarget != null ? throwTarget.getX() : agent.getX() + TILE_SIZE * 2;
			double throwTargetY = throwTarget != null ? throwTarget.getY() : agent.getY();
			String throwDesc = executeThrow(agent, action.getItem(), throwTargetX, throwTargetY, throwTargetName);
			if (throwTarget != null) {
				int throwDmg = computeAttackDamage(agent) / 2;
				throwTarget.applyDamage(throwDmg);
				throwTarget.getMemoryStream().add(new Observation(
					agent.getFullName() + " threw something at me."));
				triggerAttackedResponse(throwTarget, agent.getFullName(), agent);
			}
			appendChronicle(agent.getFullName(), "agent", "throw",
				throwTargetName != null ? throwTargetName : "tile", "object",
				throwDesc, agent.getX(), agent.getY(), throwTargetX, throwTargetY);
			agent.completeActiveAction();
			return;
		}

		// ── Existing logic ───────────────────────────────────────────────────────
		if ("pickup".equals(type) && action.getItem() != null && agent instanceof Player player) {
			player.addItem(action.getItem());
		}
		if ("drop".equals(type) && action.getItem() != null && agent instanceof Player player) {
			player.removeItem(action.getItem());
		}
		if ("speak".equals(type) && action.getSpeakText() != null && !action.getSpeakText().isBlank()) {
			agent.setCurrentActivity(action.getSpeakText());
		}
		if (action.getDescription() != null && !action.getDescription().isBlank()) {
			agent.setCurrentActivity(action.getDescription());
		}

		// ── Chronicle write (Item 8) ─────────────────────────────────────────────
		String targetId = action.getTargetAgent() != null ? action.getTargetAgent()
			: (action.getTargetLocation() != null ? action.getTargetLocation() : "");
		String payload = action.getSpeakText() != null ? action.getSpeakText() : action.getDescription();
		appendChronicle(agent.getFullName(), "agent", type, targetId, "unknown", payload,
			agent.getX(), agent.getY(), 0, 0);

		agent.completeActiveAction();
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

	private int tileManhattanDistance(double ax, double ay, double bx, double by) {
		int dx = Math.abs(toTile(ax) - toTile(bx));
		int dy = Math.abs(toTile(ay) - toTile(by));
		return dx + dy;
	}

	private int toTileDistance(double worldDistance) {
		return (int) Math.ceil(Math.max(0.0, worldDistance) / TILE_SIZE);
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
		    injectLegalActions(agent);
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
		return tileManhattanDistance(agent1.getX(), agent1.getY(), agent2.getX(), agent2.getY());
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
		double distanceCost = distance * 3.0; // 3 seconds per tile
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

        // Only enforce location match when BOTH agents have known locations.
        // If the initiator's location is null (e.g. not yet synced), skip the check
        // so a valid position-based distance check still gates the action.
        if (initiator.getLocation() != null && target.getLocation() != null
                && !initiator.getLocation().getFullPath().equals(target.getLocation().getFullPath())) {
            return "Agents are not in the same location";
        }

        double distance = calculateDistance(initiator, target);
		if (distance > DEFAULT_INTERACTION_TILE_DISTANCE) {
			return "Target is too far away (distance: " + (int)distance + " tiles)";
        }

        return null; // OK
    }

	private WorldObjectInstance resolveObjectTarget(String targetToken) {
		if (targetToken == null || targetToken.isBlank()) {
			return null;
		}
		String objectId = targetToken;
		if (targetToken.startsWith("object:")) {
			objectId = targetToken.substring("object:".length());
		}
		WorldObjectInstance direct = objectInstances.get(objectId);
		if (direct != null) {
			return direct;
		}
		if (!targetToken.startsWith("object:")) {
			return null;
		}
		return objectInstances.get(targetToken);
	}

	private String validateObjectInteractionFeasibility(Agent initiator, WorldObjectInstance target) {
		if (initiator == null || target == null) {
			return "Initiator or target object is null";
		}
		if (isObjectHeld(target)) {
			return "Object is currently held in inventory";
		}
		if (initiator.getLocation() == null || target.getLocation() == null) {
			return "Player or object has unknown location";
		}
		if (!initiator.getLocation().getFullPath().equals(target.getLocation())) {
			return "Object is not in the same location";
		}
		int radiusTiles = Math.max(1, toTileDistance(asDouble(target.getProperties() == null ? null : target.getProperties().get("interactionRadius"), TILE_SIZE)));
		double distance = tileManhattanDistance(initiator.getX(), initiator.getY(), target.getX(), target.getY());
		if (distance > radiusTiles + 1) {
			return "Object is too far away (distance: " + (int) distance + " tiles)";
		}
		return null;
	}

	private LinkedHashSet<String> getInventorySet(Agent actor) {
		if (actor == null) {
			return new LinkedHashSet<>();
		}
		// Legacy string-id inventory for carried world objects.
		// Agent.inventory (Map<String,InventoryItem>) is the new typed store for tools/keys;
		// ActionResolver reads that. Both coexist during migration.
		return inventoryByAgent.computeIfAbsent(actor.getFullName(), k -> new LinkedHashSet<>());
	}

	private String[] getInventoryArray(Player player) {
		if (player == null) {
			return new String[0];
		}
		return getInventorySet(player).toArray(new String[0]);
	}

	private boolean isObjectInInventory(Agent actor, String objectId) {
		if (actor == null || objectId == null || objectId.isBlank()) {
			return false;
		}
		return getInventorySet(actor).contains(objectId) || actor.getInventory().containsKey(objectId);
	}

	/**
	 * Builds an AgentStateResponse for an agent (or player) with their carried inventory
	 * objects resolved and attached. Use this instead of mapper.fromAgent() for playerState
	 * in action responses so the client keeps its inventory in sync.
	 */
	private AgentStateResponse fromAgentWithInventory(Agent actor) {
		AgentStateResponse r = mapper.fromAgent(actor);
		// Merge typed inventory (InventoryItem map) and legacy string-id set
		// so that items created by applyUseMachine (typed-only) are included.
		Set<String> ids = new LinkedHashSet<>(getInventorySet(actor));
		actor.getInventory().keySet().forEach(ids::add);
		List<Map<String, Object>> invObjects = ids.stream()
			.map(id -> objectInstances.get(id))
			.filter(obj -> obj != null)
			.map(WorldObjectInstance::toMap)
			.collect(Collectors.toList());
		r.setInventoryObjects(invObjects);
		return r;
	}

	/**
	 * Returns true if the actor holds a tool that satisfies the given grant.
	 * Checks the typed InventoryItem system first, then falls back to the legacy
	 * world-object inventory using a direct tag match on properties.tags.
	 * Object definitions on the server are authoritative — if a pencil has
	 * tags:["writing_utensil"] it satisfies actorHasGrant(actor, "writing_utensil").
	 */
	private boolean actorHasGrant(Agent actor, String grant) {
		if (actor == null || grant == null) return false;
		// 1. Typed inventory (InventoryItem grants)
		if (actor.getInventory().values().stream().anyMatch(item -> item.hasGrant(grant))) {
			return true;
		}
		// 2. Legacy inventory: check properties.tags for the exact grant tag
		String grantLower = grant.toLowerCase();
		return getInventorySet(actor).stream()
			.map(id -> objectInstances.get(id))
			.filter(obj -> obj != null)
			.anyMatch(obj -> {
				if (obj.getProperties() != null && containsTag(obj.getProperties(), grantLower)) {
					return true;
				}
				// Name fallback: "house key" contains "key", "pocket knife" contains "knife"
				String nameLower = obj.getName() == null ? "" : obj.getName().toLowerCase();
				return nameLower.contains(grantLower);
			});
	}

	private boolean isObjectHeld(WorldObjectInstance instance) {
		if (instance == null || instance.getProperties() == null) {
			return false;
		}
		Object holder = instance.getProperties().get("heldBy");
		return holder != null && !String.valueOf(holder).isBlank();
	}

	private void addObjectToInventory(Agent actor, WorldObjectInstance object) {
		if (actor == null || object == null) {
			return;
		}
		LinkedHashSet<String> inv = getInventorySet(actor);
		inv.add(object.getId());
		if (object.getProperties() == null) {
			object.setProperties(new HashMap<>());
		}
		object.getProperties().put("heldBy", actor.getFullName());
		object.setHeldBy(actor.getFullName()); // keep isCarried() in sync with ActionResolver
		object.setLocation(null);
		object.setX(actor.getX());
		object.setY(actor.getY());
		refreshAgentCarriedItems(actor);
	}

	/** Keeps agent.carriedItemNames in sync so LLM prompts include current inventory. */
	private void refreshAgentCarriedItems(Agent actor) {
		if (actor == null) return;
		Set<String> allIds = new LinkedHashSet<>(getInventorySet(actor));
		actor.getInventory().keySet().forEach(allIds::add);
		List<String> names = allIds.stream()
			.map(id -> objectInstances.get(id))
			.filter(obj -> obj != null)
			.map(obj -> obj.getName())
			.collect(Collectors.toList());
		actor.setCarriedItemNames(names);
	}

	private WorldObjectInstance placeFirstInventoryObjectAt(Agent actor, WorldObjectInstance anchorObject) {
		return placeInventoryObjectAt(actor, anchorObject, null);
	}

	/** Places a specific inventory item (by id) at the anchor location; falls back to first item if itemId is null. */
	private WorldObjectInstance placeInventoryObjectAt(Agent actor, WorldObjectInstance anchorObject, String itemId) {
		if (actor == null) {
			return null;
		}
		LinkedHashSet<String> inv = getInventorySet(actor);
		if (inv.isEmpty()) {
			return null;
		}
		String objectId;
		if (itemId != null && !itemId.isBlank() && inv.contains(itemId)) {
			objectId = itemId;
		} else {
			objectId = inv.iterator().next();
		}
		WorldObjectInstance held = objectInstances.get(objectId);
		if (held == null) {
			inv.remove(objectId);
			return null;
		}

		double placeX = anchorObject != null ? anchorObject.getX() : actor.getX();
		double placeY = anchorObject != null ? anchorObject.getY() : actor.getY();
		String placeLocation = anchorObject != null ? anchorObject.getLocation() : (actor.getLocation() == null ? null : actor.getLocation().getFullPath());

		held.setX(snapToTile(placeX));
		held.setY(snapToTile(placeY));
		held.setLocation(placeLocation);
		if (held.getProperties() == null) {
			held.setProperties(new HashMap<>());
		}
		held.getProperties().remove("heldBy");
		held.setHeldBy(null);
		inv.remove(objectId);
		refreshAgentCarriedItems(actor);
		return held;
	}

	/**
	 * Throws an inventory item at a target position/entity.
	 * Removes the item from actor's inventory, places it at the target tile,
	 * and applies 1-tile knockback to any entity at the target.
	 */
	private String executeThrow(Agent actor, String itemId, double targetX, double targetY, String targetEntityName) {
		LinkedHashSet<String> inv = getInventorySet(actor);
		String resolvedItemId = (itemId != null && !itemId.isBlank() && inv.contains(itemId))
			? itemId : (inv.isEmpty() ? null : inv.iterator().next());
		if (resolvedItemId == null) return "Nothing to throw";

		WorldObjectInstance thrownItem = objectInstances.get(resolvedItemId);
		if (thrownItem == null) { inv.remove(resolvedItemId); return "Item not found"; }

		// Remove from inventory, place at the target's exact tile (or default tile if no target)
		inv.remove(resolvedItemId);
		double landX = snapToTile(targetX);
		double landY = snapToTile(targetY);
		thrownItem.setX(landX);
		thrownItem.setY(landY);
		thrownItem.setLocation(actor.getLocation() == null ? null : actor.getLocation().getFullPath());
		if (thrownItem.getProperties() == null) thrownItem.setProperties(new HashMap<>());
		thrownItem.getProperties().remove("heldBy");
		thrownItem.setHeldBy(null);
		refreshAgentCarriedItems(actor);

		String desc = "threw " + thrownItem.getName();

		// Knockback: push entity 1 tile away from thrower
		Agent targetEntity = targetEntityName != null && !targetEntityName.isBlank()
			? world.getAgent(targetEntityName).orElse(null) : null;
		if (targetEntity != null) {
			applyKnockback(actor, targetEntity);
			targetEntity.applyStressChange(0.07);
			enqueueReactiveEvent(targetEntity.getFullName(), actor.getFullName() + " threw " + thrownItem.getName() + " and hit them", 7, true);
			desc += " at " + targetEntity.getFullName();
		}

		actor.getMemoryStream().add(new Observation(desc));
		recordCommittedAction(actor, "THROW", desc);
		return desc;
	}

	/** Pushes target 1 tile in the direction away from the source, unless the tile is blocked or the target is large/medium. */
	private void applyKnockback(Agent source, Agent target) {
		if (source == null || target == null) return;
		Map<String, Object> targetProps = new HashMap<>();
		// Don't knock back large/medium entities (we check target's location objects or a size flag)
		// For now, all humanoid agents can be knocked back
		double dx = target.getX() - source.getX();
		double dy = target.getY() - source.getY();
		double len = Math.sqrt(dx * dx + dy * dy);
		if (len < 0.001) return;
		// Quantize direction to cardinal (strongest axis wins)
		int tx = (int) Math.signum(dx);
		int ty = (int) Math.signum(dy);
		if (Math.abs(dx) >= Math.abs(dy)) ty = 0; else tx = 0;

		double tileSize = 32.0;
		double newX = snapToTile(target.getX() + tx * tileSize);
		double newY = snapToTile(target.getY() + ty * tileSize);

		// Only apply if destination is within bounds, not blocked, and not occupied
		Location targetLoc = target.getLocation();
		boolean inBounds = targetLoc == null || targetLoc.isWithinBounds(newX, newY);
		if (inBounds
				&& findBlockingObjectAtTile(targetLoc, newX, newY) == null
				&& findOccupyingAgentAtTile(targetLoc, newX, newY, target) == null) {
			target.setPosition(newX, newY);
			target.getMemoryStream().add(new Observation("Was knocked back by " + source.getFullName()));
		}
	}

	private WorldObjectInstance findBlockingObjectAtTile(Location location, double x, double y) {
		if (location == null) {
			return null;
		}
		int tx = toTile(x);
		int ty = toTile(y);
		for (WorldObjectInstance obj : objectInstances.values()) {
			if (obj == null || obj.getLocation() == null || obj.getProperties() == null) {
				continue;
			}
			if (isObjectHeld(obj)) {
				continue;
			}
			if (!location.getFullPath().equals(obj.getLocation())) {
				continue;
			}
			if (toTile(obj.getX()) != tx || toTile(obj.getY()) != ty) {
				continue;
			}
			if (isObjectBlockingMovement(obj)) {
				return obj;
			}
		}
		return null;
	}

	private boolean isObjectClimbable(WorldObjectInstance obj) {
		if (obj == null || obj.getProperties() == null) return false;
		if (!asBoolean(obj.getProperties().get("flat_surface"), false)) return false;
		String height = String.valueOf(obj.getProperties().getOrDefault("height", "")).toLowerCase();
		return height.equals("low") || height.equals("medium") || height.equals("counter");
	}

	private boolean isObjectBlockingMovement(WorldObjectInstance obj) {
		if (obj == null || obj.getProperties() == null) return false;
		// New vocabulary: passable: false = impassable solid
		if (obj.getProperties().containsKey("passable")) {
			boolean passable = asBoolean(obj.getProperties().get("passable"), true);
			if (!passable) return true;
		}
		// transition_point (door/gate): blocked when locked
		if (asBoolean(obj.getProperties().get("transition_point"), false)) {
			return asBoolean(obj.getProperties().get("locked"), false);
		}
		// Legacy: doorOpen / can_open_close / door tag
		boolean doorLike = containsTag(obj.getProperties(), "door")
			|| containsTag(obj.getProperties(), "entrance")
			|| asBoolean(obj.getProperties().get("can_open_close"), false)
			|| obj.getProperties().containsKey("doorOpen");
		if (doorLike) {
			return !asBoolean(obj.getProperties().get("doorOpen"), true);
		}
		// Legacy: walkable: false
		return !asBoolean(obj.getProperties().get("walkable"), true);
	}

	// ── ActionResolver helpers ────────────────────────────────────────────────

	/**
	 * Build the per-actor typed-inventory map required by ActionResolver.
	 * Uses each Agent's {@code inventory} field (Map&lt;String,InventoryItem&gt;)
	 * which is the authoritative typed store maintained by SimulationService.
	 */
	private Map<String, Map<String, InventoryItem>> buildInventoryByActor() {
		Map<String, Map<String, InventoryItem>> result = new HashMap<>();
		for (Agent agent : world.getAgents()) {
			// Start with typed inventory (InventoryItem grants)
			Map<String, InventoryItem> combined = new HashMap<>(agent.getInventory());
			// Bridge legacy world-object inventory: derive grants from properties.tags + "opens"
			for (String objId : getInventorySet(agent)) {
				if (combined.containsKey(objId)) continue;
				WorldObjectInstance obj = objectInstances.get(objId);
				if (obj != null) combined.put(objId, syntheticInventoryItem(objId, obj));
			}
			result.put(agent.getFullName(), combined);
		}
		return result;
	}

	/**
	 * Convert a legacy WorldObjectInstance held in an agent's inventory into a
	 * synthetic InventoryItem so ActionResolver grant checks work uniformly.
	 * Grants are derived from:
	 *   - properties.tags[]          → one grant per tag string
	 *   - properties.opens = "id"    → grant "opens:<id>" for key-lock binding
	 */
	@SuppressWarnings("unchecked")
	private InventoryItem syntheticInventoryItem(String objId, WorldObjectInstance obj) {
		InventoryItem item = new InventoryItem();
		item.setId(objId);
		item.setTypeId(obj.getTypeId());
		item.setDisplayName(obj.getName());
		item.setConsumable(false);
		Map<String, Object> props = obj.getProperties();
		if (props != null) {
			Object tagsObj = props.get("tags");
			if (tagsObj instanceof List<?> tags) {
				for (Object t : tags) item.addGrant(String.valueOf(t));
			}
			Object opensObj = props.get("opens");
			if (opensObj != null) {
				String target = String.valueOf(opensObj).strip();
				if (!target.isBlank()) item.addGrant("opens:" + target);
			}
		}
		return item;
	}

	/**
	 * Compute the set of legal actions for a player given their current position.
	 * Returns structured descriptors that the Godot client uses to filter its
	 * context menu — server is authoritative, especially for key-lock binding.
	 */
	public List<Map<String, String>> getPlayerLegalActions(String playerName, double x, double y) {
		Agent agent = world.getAgent(playerName)
			.orElseThrow(() -> new AgentNotFoundException(playerName));

		Map<String, double[]> nearbyAgents = world.getAgents().stream()
			.filter(a -> !a.getFullName().equals(playerName))
			.filter(a -> {
				double dx = a.getX() - x;
				double dy = a.getY() - y;
				return Math.sqrt(dx * dx + dy * dy) / TILE_SIZE <= AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE;
			})
			.collect(Collectors.toMap(Agent::getFullName, a -> new double[]{a.getX(), a.getY()}));

		ActionResolver resolver = new ActionResolver(
			buildInventoryByActor(), objectInstances, objectTypeDefinitions);
		return resolver.legalDescriptorsFor(playerName, x, y, nearbyAgents).stream()
			.map(ActionResolver.ActionDescriptor::toMap)
			.collect(Collectors.toList());
	}

	/**
	 * GET /agents/{name}/epistemic — returns the agent's current EpistemicMemory.
	 * Includes recent observed events, hearsay, and the latest belief correction.
	 */
	public Map<String, Object> getAgentEpistemicState(String name) {
		Agent agent = world.getAgent(name)
			.orElseThrow(() -> new AgentNotFoundException(name));
		EpistemicMemory em = agent.getEpistemicMemory();

		List<Map<String, Object>> observed = em.recentObserved(20).stream()
			.map(o -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("turn", o.turnNumber);
				m.put("actor", o.actorId);
				m.put("verb", o.verb);
				m.put("target", o.targetId);
				m.put("payload", o.payload != null ? o.payload : "");
				m.put("actorX", o.actorX);
				m.put("actorY", o.actorY);
				return m;
			})
			.collect(Collectors.toList());

		List<Map<String, Object>> hearsay = em.recentHearsay(20).stream()
			.map(h -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("turn", h.turnNumber);
				m.put("source", h.sourceActorId);
				m.put("content", h.content);
				m.put("confidence", h.confidence);
				return m;
			})
			.collect(Collectors.toList());

		EpistemicMemory.BeliefCorrection corr = em.latestCorrection();
		Object correction = null;
		if (corr != null) {
			Map<String, Object> cm = new LinkedHashMap<>();
			cm.put("turn", corr.turnNumber);
			cm.put("verb", corr.attemptedVerb);
			cm.put("target", corr.targetId);
			cm.put("reason", corr.rejectReason.toString());
			cm.put("believed", corr.believed);
			cm.put("reality", corr.reality);
			correction = cm;
		}

		Map<String, Object> counts = new LinkedHashMap<>();
		counts.put("observed", em.observedCount());
		counts.put("hearsay", em.hearsayCount());
		counts.put("corrections", em.correctionCount());

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("agent", name);
		result.put("counts", counts);
		result.put("observed", observed);
		result.put("hearsay", hearsay);
		result.put("latestCorrection", correction);
		return result;
	}

	/**
	 * Gracefully shut down the cognition executor. Called from the JVM shutdown hook.
	 * Logs any agents with in-flight cognition jobs that will not complete.
	 */
	public void shutdown() {
		if (!cognitionInFlight.isEmpty()) {
			LOG.info("[Shutdown] {} agent(s) had in-flight cognition: {}",
				cognitionInFlight.size(), String.join(", ", cognitionInFlight));
		}
		cognitionExecutor.shutdown();
		try {
			if (!cognitionExecutor.awaitTermination(8, java.util.concurrent.TimeUnit.SECONDS)) {
				List<Runnable> cancelled = cognitionExecutor.shutdownNow();
				LOG.info("[Shutdown] Forced shutdown — {} pending task(s) discarded", cancelled.size());
			}
		} catch (InterruptedException e) {
			cognitionExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
		LOG.info("[Shutdown] Cognition executor terminated.");
	}

	/**
	 * Submit a cognition job for an agent to the background thread pool.
	 *
	 * The job runs LLM calls on the worker thread and posts result lambdas to
	 * {@code pendingCognitionApplies}. The main thread drains that queue at the
	 * start of each orchestration pass (single-threaded agent mutation).
	 *
	 * @param agent        the agent needing cognition
	 * @param cognitionJob the LLM work to run (may mutate agent directly)
	 * @param onComplete   callback posted to pendingCognitionApplies after success
	 * @param onError      callback posted to pendingCognitionApplies on failure
	 */
	// ── Chronicle helpers (Item 8) ────────────────────────────────────────────

	/**
	 * Append a ChronicleEvent. WitnessIds are computed as all agents within
	 * social awareness range of the event origin. Returns the new event so
	 * callers can directly inject it into a target's EpistemicMemory.
	 */
	private ChronicleEvent appendChronicle(String actorId, String actorType, String verb,
								  String targetId, String targetType, String payload,
								  double actorX, double actorY,
								  double targetX, double targetY) {
		int turn = turnCounter.get();
		Set<String> witnesses = world.getAgents().stream()
			.filter(a -> {
				double dx = a.getX() - actorX;
				double dy = a.getY() - actorY;
				return Math.sqrt(dx * dx + dy * dy) / TILE_SIZE <= AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE;
			})
			.map(Agent::getFullName)
			.collect(Collectors.toSet());
		ChronicleEvent evt = new ChronicleEvent(turn, actorId, actorType, verb,
			targetId, targetType, payload, actorX, actorY, targetX, targetY, witnesses);
		chronicle.add(evt);
		if (chronicle.size() > MAX_CHRONICLE_SIZE) {
			chronicle.subList(0, chronicle.size() - MAX_CHRONICLE_SIZE).clear();
		}
		return evt;
	}

	/**
	 * Perception channel: for each ChronicleEvent from the just-completed turn,
	 * admit it into the EpistemicMemory of every witness agent (not players).
	 * Called once per orchestration pass after action processing.
	 *
	 * @param processedTurn the turn number that just finished (before increment)
	 */
	private void runPerceptionChannel(int processedTurn) {
		for (ChronicleEvent evt : chronicle) {
			if (evt.getTurnNumber() != processedTurn) continue;
			for (String witnessId : evt.getWitnessIds()) {
				world.getAgent(witnessId).ifPresent(witness -> {
					if (witness instanceof Player) return;
					witness.getEpistemicMemory().ingestObserved(evt);
				});
			}
		}
	}

	private void submitAsyncCognition(Agent agent, Runnable cognitionJob,
									  Runnable onComplete, Runnable onError) {
		String name = agent.getFullName();
		if (cognitionInFlight.contains(name)) return;
		cognitionInFlight.add(name);
		cognitionExecutor.submit(() -> {
			try {
				cognitionJob.run();
				pendingCognitionApplies.offer(onComplete);
			} catch (Exception e) {
				LOG.warn("[AsyncCognition] {} failed: {}", name, e.getMessage());
				pendingCognitionApplies.offer(onError);
			} finally {
				cognitionInFlight.remove(name);
			}
		});
	}

	private void injectLegalActions(Agent agent) {
		if (agent == null) return;
		try {
			Map<String, double[]> nearbyAgents = world.getAgents().stream()
				.filter(a -> a != agent)
				.filter(a -> {
					double dx = a.getX() - agent.getX();
					double dy = a.getY() - agent.getY();
					return Math.sqrt(dx * dx + dy * dy) / TILE_SIZE <= AGENTIC_SOCIAL_AWARENESS_TILE_DISTANCE;
				})
				.collect(Collectors.toMap(Agent::getFullName, a -> new double[]{a.getX(), a.getY()}));

			ActionResolver resolver = new ActionResolver(
				buildInventoryByActor(), objectInstances, objectTypeDefinitions);
			List<String> legal = resolver.legalActionsFor(
				agent.getFullName(), agent.getX(), agent.getY(), nearbyAgents);
			agent.setLegalActions(legal);
			LOG.debug("[LegalActions] {} → {}", agent.getFullName(), legal);
		} catch (Exception e) {
			LOG.warn("[LegalActions] Failed to compute legal actions for {}: {}", agent.getFullName(), e.getMessage());
			agent.setLegalActions(List.of("wait"));
		}
	}

	/**
	 * Populates an agent's spatial knowledge with the full world state at the current turn.
	 * Called once on first tool-loop entry (when spatialKnowledge is empty).
	 * Gives agents "map knowledge" — they know where everything is at the start,
	 * and observations from scan_nearby update stale entries over time.
	 */
	private void seedSpatialKnowledge(Agent agent) {
		int turn = turnCounter.get();
		for (WorldObjectInstance obj : objectInstances.values()) {
			if (obj.getHeldBy() != null && !obj.getHeldBy().isBlank()) continue;
			String loc = obj.getLocation() != null ? obj.getLocation() : null;
			agent.updateSpatialKnowledge(obj.getInstanceId(), obj.getName(), "object",
				obj.getX(), obj.getY(), loc, turn);
		}
		for (Agent other : world.getAgents()) {
			if (other == agent) continue;
			String loc = other.getLocation() != null ? other.getLocation().getFullPath() : null;
			String type = other instanceof Player ? "player" : "agent";
			agent.updateSpatialKnowledge(other.getFullName(), other.getFullName(), type,
				other.getX(), other.getY(), loc, turn);
		}
		LOG.info("[SpatialKnowledge] Seeded {} entries for {}", agent.getSpatialKnowledge().size(), agent.getFullName());
	}

	/**
	 * Scans this agent's EpistemicMemory and updates the belief models in its
	 * AgenticRuntimeState, then writes a human-readable narrative to
	 * {@code agent.beliefSummary} so LLM prompts reflect the agent's theory of mind.
	 *
	 * Called alongside injectLegalActions() before each LLM planning invocation.
	 *
	 * Sources used:
	 *  - ObservedEvents: direct observations of other agents acting — high confidence
	 *  - Hearsay: things spoken about others — lower confidence
	 */
	private void refreshBeliefModels(Agent agent, AgenticRuntimeState state) {
		if (agent == null || state == null) return;
		try {
			Set<String> knownAgentNames = world.getAgents().stream()
				.filter(a -> a != agent)
				.map(Agent::getFullName)
				.collect(Collectors.toSet());

			// ── ObservedEvents → update from direct observation ───────────────
			for (io.github.nickm980.smallville.entities.EpistemicMemory.ObservedEvent evt
					: agent.getEpistemicMemory().recentObserved(30)) {
				if (!knownAgentNames.contains(evt.actorId)) continue;
				io.github.nickm980.smallville.entities.AgentBeliefModel model =
					state.beliefModels.computeIfAbsent(evt.actorId,
						io.github.nickm980.smallville.entities.AgentBeliefModel::new);
				model.updateFromObservation(evt.verb, evt.payload);
			}

			// ── Hearsay → look for mentions of known agent names ──────────────
			for (io.github.nickm980.smallville.entities.EpistemicMemory.Hearsay h
					: agent.getEpistemicMemory().recentHearsay(10)) {
				if (h.content == null || h.content.isBlank()) continue;
				for (String targetName : knownAgentNames) {
					if (h.content.contains(targetName)) {
						io.github.nickm980.smallville.entities.AgentBeliefModel model =
							state.beliefModels.computeIfAbsent(targetName,
								io.github.nickm980.smallville.entities.AgentBeliefModel::new);
						String note = h.sourceActorId + " said: \"" + h.content + "\"";
						model.addHearsay(note, h.confidence);
					}
				}
			}

			// ── Serialise to narrative string for LLM prompt ─────────────────
			if (state.beliefModels.isEmpty()) {
				agent.setBeliefSummary("");
				return;
			}
			StringBuilder sb = new StringBuilder();
			for (io.github.nickm980.smallville.entities.AgentBeliefModel model
					: state.beliefModels.values()) {
				sb.append("- ").append(model.toNarrative()).append("\n");
			}
			agent.setBeliefSummary(sb.toString().trim());
			LOG.debug("[BeliefModels] {} → {} belief entries", agent.getFullName(), state.beliefModels.size());
		} catch (Exception e) {
			LOG.warn("[BeliefModels] Failed to refresh belief models for {}: {}", agent.getFullName(), e.getMessage());
			agent.setBeliefSummary("");
		}
	}

	// ── Combat helpers ───────────────────────────────────────────────────────

	/** Returns base damage for an attack: 25 if actor carries a weapon grant, else 15. */
	private int computeAttackDamage(Agent actor) {
		boolean hasWeapon = actor.getInventory().values().stream()
			.anyMatch(i -> i.hasGrant("weapon") || i.hasGrant("blunt") || i.hasGrant("sharp"));
		return hasWeapon ? 25 : 15;
	}

	private int computeVerbDamage(Agent actor, String verb) {
		boolean hasWeapon = actor.getInventory().values().stream()
			.anyMatch(i -> i.hasGrant("weapon") || i.hasGrant("blunt") || i.hasGrant("sharp"));
		return switch (verb) {
			case "punch"  -> hasWeapon ? 15 : 10;
			case "kick"   -> hasWeapon ? 28 : 20;
			case "tackle" -> hasWeapon ? 12 : 8;
			default       -> hasWeapon ? 25 : 15; // attack / fallback
		};
	}

	private boolean isAggressiveActionType(String verb) {
		return switch (verb) {
			case "attack", "punch", "kick", "tackle", "throw" -> true;
			default -> false;
		};
	}

	private String buildCombatHitDescription(String actorName, String verb, int damage) {
		String intensity = damage >= 20 ? " hard" : "";
		return switch (verb) {
			case "punch"  -> actorName + " punched me" + intensity + ".";
			case "kick"   -> actorName + " kicked me" + intensity + ".";
			case "tackle" -> actorName + " tackled me" + intensity + ".";
			default       -> actorName + " attacked me" + (damage > 15 ? " with a weapon" : "") + ".";
		};
	}

	/**
	 * Called after a successful attack on {@code target}.
	 * Decides flee vs retaliate based on traits and health, then injects a goal into the
	 * target's goalPlan and records a long-term memory.
	 */
	private void triggerAttackedResponse(Agent target, String attackerName, Agent attacker) {
		if (target instanceof Player) return;
		AgenticRuntimeState state = agenticStateByAgent.computeIfAbsent(target.getFullName(), k -> new AgenticRuntimeState());
		double fear = target.getFearfulness();
		double aggression = target.getAggression();
		boolean lowHealth = target.getHealth() < 30;

		if (lowHealth || fear > aggression) {
			List<Location> elsewhere = getLocationsCached().stream()
				.filter(l -> target.getLocation() == null || !l.getFullPath().equals(target.getLocation().getFullPath()))
				.collect(java.util.stream.Collectors.toList());
			if (!elsewhere.isEmpty()) {
				Location safe = elsewhere.get(0);
				AgenticGoal flee = new AgenticGoal();
				flee.type = "TOOL_ACTION";
				flee.actionType = "move";
				flee.targetId = safe.getFullPath();
				flee.snapshotLocation = safe.getFullPath();
				flee.actionDescription = "Fleeing from " + attackerName;
				flee.description = "Fleeing from " + attackerName;
				state.goalPlan.clear();
				state.goalPlan.add(flee);
				state.phase = AgenticPhase.IDLE;
				LOG.info("[Combat] {} is fleeing from {}", target.getFullName(), attackerName);
			}
		} else {
			AgenticGoal retaliate = new AgenticGoal();
			retaliate.type = "TOOL_ACTION";
			retaliate.actionType = "attack";
			retaliate.targetId = attackerName;
			retaliate.targetType = "agent";
			retaliate.targetIsMobile = true;
			retaliate.actionDescription = "Retaliating against " + attackerName;
			retaliate.description = "Retaliating against " + attackerName;
			retaliate.snapshotLocation = target.getLocation() != null ? target.getLocation().getFullPath() : "";
			state.goalPlan.clear();
			state.goalPlan.add(retaliate);
			state.phase = AgenticPhase.IDLE;
			LOG.info("[Combat] {} is retaliating against {}", target.getFullName(), attackerName);
		}
		// Social agents also queue a verbal confrontation after their primary response
		if (attacker instanceof Player && target.getSocialDominance() >= 0.4) {
			AgenticGoal converse = new AgenticGoal();
			converse.type = "TOOL_ACTION";
			converse.actionType = "speak";
			converse.targetId = attackerName;
			converse.targetType = "player";
			converse.actionDescription = "Confronting " + attackerName + " about recent violence";
			converse.description = "Approaching to speak with " + attackerName;
			state.goalPlan.add(converse); // queued AFTER the flee/retaliate goal
		}
		target.getMemoryStream().add(new Observation(
			"I was attacked by " + attackerName + ". My health is now " + target.getHealth() + "."));
	}

	// ── Environment scan ─────────────────────────────────────────────────────

	private static final double SCAN_RADIUS_TILES = 4.0;
	private static final double READ_RADIUS_TILES = 2.0;

	/**
	 * Passive environment scan: run each turn for each NPC before runAgenticLoop().
	 * Detects nearby object state changes and readable writing, then injects
	 * natural-language Observations filtered through the agent's personality traits.
	 */
	private void scanEnvironment(Agent agent, AgenticRuntimeState state) {
		if (agent == null || agent instanceof Player || objectInstances == null) return;
		double ax = agent.getX(), ay = agent.getY();
		for (WorldObjectInstance obj : objectInstances.values()) {
			if (obj.getHeldBy() != null && !obj.getHeldBy().isBlank()) continue;
			double dist = Math.sqrt(Math.pow(obj.getX()-ax,2)+Math.pow(obj.getY()-ay,2)) / TILE_SIZE;
			if (dist > SCAN_RADIUS_TILES) continue;

			String id = obj.getInstanceId();
			// Build combined view of mutable state (state map + has_writing from properties)
			Map<String, Object> curState = new java.util.HashMap<>(obj.getStateMap());
			Object writing = obj.getProperties() != null ? obj.getProperties().get("has_writing") : null;
			if (writing != null) curState.put("has_writing", writing);

			Map<String, Object> prevState = state.objectStateSnapshot.getOrDefault(id, new java.util.HashMap<>());

			// 1. Detect property changes vs previous snapshot
			for (String key : curState.keySet()) {
				Object cur = curState.get(key);
				Object prev = prevState.get(key);
				if (prev != null && !cur.equals(prev) && shouldNotice(agent, key, cur)) {
					String observation = describeStateChange(obj.getName(), key, prev, cur);
					if (observation != null) {
						agent.getMemoryStream().add(new Observation(observation));
					}
				}
			}

			// 2. Read writing on nearby objects (within 2 tiles, once per object)
			if (dist <= READ_RADIUS_TILES && writing != null
					&& !state.alreadyReadObjects.contains(id)
					&& shouldNotice(agent, "has_writing", writing)) {
				agent.getMemoryStream().add(new Observation(
					"I noticed writing on " + obj.getName() + " near me: \"" + writing + "\""));
				state.alreadyReadObjects.add(id);
			}

			state.objectStateSnapshot.put(id, new java.util.HashMap<>(curState));
		}
	}

	/**
	 * Returns true if this agent's personality makes them care about a given state change.
	 * Prevents every NPC from being flooded with irrelevant observations.
	 */
	private boolean shouldNotice(Agent agent, String stateKey, Object newValue) {
		switch (stateKey) {
			case "isLocked":
				return agent.getFearfulness() > 0.4 || agent.getSocialDominance() > 0.5;
			case "isOpen":
				return agent.getFearfulness() > 0.5 || agent.getImpulsivity() > 0.6;
			case "has_writing":
				return agent.getCompassion() > 0.4 || agent.getImpulsivity() > 0.5;
			default:
				return agent.getImpulsivity() > 0.7;
		}
	}

	/**
	 * Converts a boolean property change into a natural-language observation string.
	 * Returns null if the change is not worth reporting.
	 */
	private String describeStateChange(String objectName, String stateKey, Object prev, Object cur) {
		switch (stateKey) {
			case "isOpen":
				return Boolean.TRUE.equals(cur)
					? "The " + objectName + " nearby was opened."
					: "The " + objectName + " nearby was closed.";
			case "isLocked":
				return Boolean.TRUE.equals(cur)
					? "The " + objectName + " nearby was locked."
					: "The " + objectName + " nearby was unlocked.";
			default:
				return null;
		}
	}

	/**
	 * Reads the agent's recent memories, asks the LLM for trait signal adjustments,
	 * and applies them to the agent's mutable trait deltas.
	 *
	 * Called once per end-of-day reflection cycle after runEndOfDayReflection().
	 * No-ops silently on parse failure so reflection is never blocked.
	 */
	private void applyReflectionTraitSignals(Agent agent) {
		if (agent == null) return;
		String templateText = SmallvilleConfig.getPrompts().getAgent().getTraitSignals();
		if (templateText == null || templateText.isBlank()) {
			LOG.warn("[TraitSignals] traitSignals prompt template is missing — skipping for {}", agent.getFullName());
			return;
		}
		try {
			// Build template data — mirrors TemplateMapper.fromAgent() for the keys used in traitSignals
			Map<String, Object> agentData = new HashMap<>();
			agentData.put("name", agent.getFullName());
			agentData.put("recentMemories", agent.getMemoryStream().getRecentMemories());
			Map<String, Object> data = new HashMap<>();
			data.put("agent", agentData);
			String renderedPrompt = new TemplateEngine().format(templateText, data);
			String raw = prompts.sendRawPrompt(renderedPrompt, 0.3);
			if (raw == null || raw.isBlank()) return;

			// Strip markdown fences if present
			String json = raw.trim();
			if (json.startsWith("```")) {
				json = json.replaceAll("(?s)^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").trim();
			}

			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> signals = objectMapper.readValue(json, java.util.Map.class);
			for (java.util.Map.Entry<String, Object> entry : signals.entrySet()) {
				if (entry.getValue() instanceof Number) {
					double delta = ((Number) entry.getValue()).doubleValue();
					agent.applyTraitDelta(entry.getKey(), delta);
					LOG.debug("[TraitSignals] {} → {}={}", agent.getFullName(), entry.getKey(), delta);
				}
			}
			LOG.info("[TraitSignals] Applied {} trait signal(s) for {}", signals.size(), agent.getFullName());
		} catch (Exception e) {
			LOG.warn("[TraitSignals] Failed to apply trait signals for {}: {}", agent.getFullName(), e.getMessage());
		}
	}

	/**
	 * Supercover Bresenham LOS — checks every cell the ray passes through,
	 * including both corner cells on diagonal steps (no corner tunneling).
	 * Solid non-transparent objects block vision; start tile excluded, end tile visible.
	 */
	private boolean hasLineOfSight(double fromX, double fromY, double toX, double toY) {
		int fx = toTile(fromX), fy = toTile(fromY);
		int tx = toTile(toX),   ty = toTile(toY);
		if (fx == tx && fy == ty) return true;
		if (Math.abs(tx - fx) > 10 || Math.abs(ty - fy) > 10) return false;

		// Build LOS-blocking tile set: solid + not transparent
		java.util.Set<Long> losSet = new java.util.HashSet<>();
		for (WorldObjectInstance obj : objectInstances.values()) {
			if (obj == null || isObjectHeld(obj)) continue;
			if (!isObjectBlockingMovement(obj)) continue;
			if (obj.getProperties() != null && asBoolean(obj.getProperties().get("transparent"), false)) continue;
			int bx = toTile(obj.getX()), by = toTile(obj.getY());
			losSet.add(((long) bx << 32) | (by & 0xFFFFFFFFL));
		}

		int dx = Math.abs(tx - fx), dy = Math.abs(ty - fy);
		int sx = tx > fx ? 1 : -1, sy = ty > fy ? 1 : -1;
		int cx = fx, cy = fy;
		int err = dx - dy;
		while (cx != tx || cy != ty) {
			int e2 = 2 * err;
			boolean stepX = e2 >= -dy;
			boolean stepY = e2 <= dx;
			if (stepX && stepY) {
				// Diagonal — check both corner tiles
				long kx = ((long)(cx + sx) << 32) | (cy & 0xFFFFFFFFL);
				long ky = ((long) cx       << 32) | ((cy + sy) & 0xFFFFFFFFL);
				if (losSet.contains(kx) || losSet.contains(ky)) return false;
			}
			if (stepX) { err -= dy; cx += sx; }
			if (stepY) { err += dx; cy += sy; }
			if (cx == tx && cy == ty) return true;
			long key = ((long) cx << 32) | (cy & 0xFFFFFFFFL);
			if (losSet.contains(key)) return false;
		}
		return true;
	}
}
