package io.github.nickm980.smallville.api.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nickm980.smallville.World;
import io.github.nickm980.smallville.entities.*;
import io.github.nickm980.smallville.memory.Memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes tool calls on behalf of an agent during its turn loop.
 *
 * Read-only tools (query_memory, scan_nearby, etc.) are safe to call from the
 * async cognition thread — they do not mutate world state.
 *
 * commit_action validates the action via ActionResolver but does NOT apply the
 * mutation; it returns the resolve result inside TurnResult so that
 * SimulationService.applyTurnResult() can apply it safely on the main thread.
 */
public class AgentToolExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AgentToolExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double TILE_SIZE = 32.0;
    private static final int DEFAULT_SCAN_RADIUS = 10;
    private static final int MAX_SCAN_RADIUS = 20;
    private static final int DEFAULT_EVENT_LIMIT = 5;

    private final World world;
    private final Map<String, WorldObjectInstance> objectInstances;
    private final Map<String, Map<String, Object>> objectTypeDefinitions;
    private final int currentTurn;

    public AgentToolExecutor(World world,
                             Map<String, WorldObjectInstance> objectInstances,
                             Map<String, Map<String, Object>> objectTypeDefinitions,
                             int currentTurn) {
        this.world = world;
        this.objectInstances = objectInstances;
        this.objectTypeDefinitions = objectTypeDefinitions;
        this.currentTurn = currentTurn;
    }

    // ── Read-only tools ───────────────────────────────────────────────────────

    public String executeQueryMemory(Agent agent, String query) {
        if (query == null || query.isBlank()) return "No query provided.";
        List<Memory> memories = agent.getMemoryStream().getRelevantMemories(query, 0);
        if (memories.isEmpty()) return "No relevant memories found for: " + query;

        StringBuilder sb = new StringBuilder("Relevant memories:\n");
        int count = 0;
        for (Memory m : memories) {
            if (count++ >= 5) break;
            sb.append("- ").append(m.getDescription()).append("\n");
        }
        return sb.toString().trim();
    }

    public String executeScanNearby(Agent agent, Integer radiusTiles) {
        int radius = (radiusTiles == null || radiusTiles <= 0) ? DEFAULT_SCAN_RADIUS
                   : Math.min(radiusTiles, MAX_SCAN_RADIUS);
        double radiusWorld = radius * TILE_SIZE;

        List<Map<String, Object>> nearby = new ArrayList<>();

        // Scan other agents — update spatial knowledge with fresh positions
        for (Agent other : world.getAgents()) {
            if (other.getFullName().equals(agent.getFullName())) continue;
            double dist = distance(agent.getX(), agent.getY(), other.getX(), other.getY());
            if (dist <= radiusWorld) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", other.getFullName());
                entry.put("type", other instanceof Player ? "player" : "agent");
                entry.put("distance_tiles", Math.round(dist / TILE_SIZE * 10.0) / 10.0);
                entry.put("current_activity", other.getCurrentActivity());
                nearby.add(entry);
                // Update mental map
                String loc = other.getLocation() != null ? other.getLocation().getFullPath() : null;
                agent.updateSpatialKnowledge(other.getFullName(), other.getFullName(),
                    other instanceof Player ? "player" : "agent",
                    other.getX(), other.getY(), loc, currentTurn);
            }
        }

        // Scan world objects — update spatial knowledge with fresh positions
        for (WorldObjectInstance obj : objectInstances.values()) {
            double dist = distance(agent.getX(), agent.getY(), obj.getX(), obj.getY());
            if (dist <= radiusWorld) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", obj.getId());
                entry.put("type", "object");
                entry.put("type_id", obj.getTypeId());
                entry.put("distance_tiles", Math.round(dist / TILE_SIZE * 10.0) / 10.0);
                entry.put("state", obj.getStateMap());
                nearby.add(entry);
                // Update mental map
                agent.updateSpatialKnowledge(obj.getInstanceId(), obj.getName(), "object",
                    obj.getX(), obj.getY(), obj.getLocation(), currentTurn);
            }
        }

        if (nearby.isEmpty()) return "Nothing visible within " + radius + " tiles.";

        try {
            return "Nearby entities:\n" + MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nearby);
        } catch (Exception e) {
            return "Nearby: " + nearby.stream()
                .map(m -> m.get("id") + "(" + m.get("type") + ")")
                .collect(Collectors.joining(", "));
        }
    }

    public String executeQueryKnownLocations(Agent agent, String query) {
        List<Map<String, Object>> matches = agent.querySpatialKnowledge(query);
        if (matches.isEmpty()) return "No known location for: " + query;
        StringBuilder sb = new StringBuilder("Known locations");
        if (query != null && !query.isBlank()) sb.append(" for '").append(query).append("'");
        sb.append(":\n");
        for (Map<String, Object> e : matches) {
            int staleness = currentTurn - ((Number) e.getOrDefault("turn", 0)).intValue();
            String freshness = staleness == 0 ? "just seen" : staleness + " turns ago";
            sb.append("  - ").append(e.get("name"))
              .append(" [").append(e.get("type")).append("]")
              .append(" tile (").append(e.get("tx")).append(", ").append(e.get("ty")).append(")")
              .append(" in ").append(e.get("location"))
              .append(" — ").append(freshness).append("\n");
        }
        return sb.toString().trim();
    }

    public String executeCheckInventory(Agent agent) {
        Map<String, InventoryItem> inv = agent.getInventory();
        if (inv.isEmpty()) {
            List<String> legacy = agent.getCarriedItemNames();
            if (legacy.isEmpty()) return "Inventory is empty.";
            return "Carrying: " + String.join(", ", legacy);
        }
        StringBuilder sb = new StringBuilder("Inventory:\n");
        for (InventoryItem item : inv.values()) {
            sb.append("- ").append(item.getId());
            if (!item.getGrants().isEmpty()) {
                sb.append(" [grants: ").append(String.join(", ", item.getGrants())).append("]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public String executeReadBeliefs(Agent agent, String targetName) {
        String summary = agent.getBeliefSummary();
        if (summary == null || summary.isBlank()) return "No beliefs formed yet about other agents.";
        if (targetName == null || targetName.isBlank()) return summary;

        // Filter to lines mentioning the target name
        String[] lines = summary.split("\n");
        List<String> relevant = Arrays.stream(lines)
            .filter(l -> l.toLowerCase().contains(targetName.toLowerCase()))
            .collect(Collectors.toList());

        if (relevant.isEmpty()) return "No specific beliefs about " + targetName + " yet.";
        return "Beliefs about " + targetName + ":\n" + String.join("\n", relevant);
    }

    public String executeReadEpistemicEvents(Agent agent, Integer limit) {
        int max = (limit == null || limit <= 0) ? DEFAULT_EVENT_LIMIT : Math.min(limit, 20);

        EpistemicMemory em = agent.getEpistemicMemory();
        List<EpistemicMemory.ObservedEvent> observedList = em.recentObserved(max);

        if (observedList.isEmpty()) {
            List<EpistemicMemory.Hearsay> hearsayList = em.recentHearsay(max);
            if (hearsayList.isEmpty()) return "No witnessed or hearsay events yet.";
            StringBuilder sb = new StringBuilder("Recent hearsay:\n");
            for (EpistemicMemory.Hearsay h : hearsayList) {
                sb.append("- ").append(h.toNarrative()).append("\n");
            }
            return sb.toString().trim();
        }

        StringBuilder sb = new StringBuilder("Recent witnessed events:\n");
        for (EpistemicMemory.ObservedEvent e : observedList) {
            sb.append("- ").append(e.toNarrative()).append("\n");
        }
        return sb.toString().trim();
    }

    public String executeUpdatePlan(Agent agent, String activity, String targetLocation) {
        if (activity == null || activity.isBlank()) return "activity cannot be blank.";
        agent.setCurrentActivity(activity);
        if (targetLocation != null && !targetLocation.isBlank()) {
            world.getLocation(targetLocation).ifPresentOrElse(
                loc -> agent.setTargetLocation(loc.getFullPath()),
                () -> LOG.warn("[ToolExecutor] Unknown location '{}' for {}", targetLocation, agent.getFullName())
            );
        }
        return "Plan updated: " + activity;
    }

    // ── commit_action: validate via ActionResolver, return result (no mutation) ─

    /**
     * Validates the action via ActionResolver and returns the result.
     * Does NOT mutate world state — that is done by SimulationService.applyTurnResult()
     * on the main thread when the TurnResult is drained from pendingCognitionApplies.
     */
    public CommitValidationResult executeCommitValidation(Agent agent,
                                                           String verb,
                                                           String targetId,
                                                           String targetType,
                                                           String payload,
                                                           int turnNumber) {
        if (verb == null || verb.isBlank()) {
            return CommitValidationResult.rejected("verb cannot be blank — use 'wait' with target_id='self' to do nothing");
        }
        // Normalise wait: allow any self-referencing target (self, agent name, blank) → canonical form
        if ("wait".equalsIgnoreCase(verb)) {
            WorldAction waitAction = WorldAction.fromPlayerAction(
                agent.getFullName(), "wait", agent.getFullName(),
                WorldAction.TargetType.AGENT, null, null,
                agent.getX(), agent.getY(), turnNumber);
            return CommitValidationResult.permitted(waitAction);
        }
        if (targetId == null || targetId.isBlank()) {
            return CommitValidationResult.rejected("target_id cannot be blank — use the exact entity id from the Nearby list");
        }

        WorldAction.TargetType wtt = parseTargetType(targetType);
        if (wtt == null) {
            return CommitValidationResult.rejected("unknown target_type: " + targetType + ". Use 'agent', 'player', or 'object'");
        }

        // Range enforcement for combat verbs (mirrors player combat checks)
        if (wtt == WorldAction.TargetType.AGENT || wtt == WorldAction.TargetType.PLAYER) {
            Agent combatTarget = world.getAgent(targetId).orElse(null);
            if (combatTarget != null) {
                String verbLower = verb.toLowerCase();
                int dist = tileManhattanDistance(agent.getX(), agent.getY(), combatTarget.getX(), combatTarget.getY());
                if ("punch".equals(verbLower) || "kick".equals(verbLower)) {
                    if (dist > 2) {
                        return CommitValidationResult.rejected(verb + " requires being adjacent (≤2 tiles). Current distance: " + dist + " tiles.");
                    }
                } else if ("tackle".equals(verbLower)) {
                    if (dist < 2) {
                        return CommitValidationResult.rejected("Cannot tackle when already adjacent — use punch or kick instead.");
                    }
                    if (dist > 3) {
                        return CommitValidationResult.rejected("Too far to tackle (" + dist + " tiles) — move within 3 tiles.");
                    }
                }
            }
        }

        // ActionResolver is actor-type-blind; fromPlayerAction is safe here
        WorldAction action = WorldAction.fromPlayerAction(
            agent.getFullName(), verb, targetId, wtt, null, payload,
            agent.getX(), agent.getY(), turnNumber);

        // Build a minimal inventoryByActor using this agent's typed inventory only
        Map<String, Map<String, InventoryItem>> inventoryByActor = new HashMap<>();
        inventoryByActor.put(agent.getFullName(), agent.getInventory());

        ActionResolver resolver = new ActionResolver(inventoryByActor, objectInstances, objectTypeDefinitions);
        ActionResolver.ResolveResult result = resolver.resolve(action);

        if (result.permitted) {
            LOG.info("[ToolExecutor] commit_action PERMITTED: {} {} {} (agent={})",
                verb, targetType, targetId, agent.getFullName());
            return CommitValidationResult.permitted(action);
        } else {
            LOG.info("[ToolExecutor] commit_action REJECTED ({}): {} — agent={}",
                result.rejectReason, result.explanation, agent.getFullName());
            return CommitValidationResult.rejected(
                result.rejectReason + ": " + result.explanation);
        }
    }

    private WorldAction.TargetType parseTargetType(String raw) {
        if (raw == null) return null;
        switch (raw.toLowerCase()) {
            case "agent": return WorldAction.TargetType.AGENT;
            case "player": return WorldAction.TargetType.PLAYER;
            case "object": return WorldAction.TargetType.OBJECT;
            default: return null;
        }
    }

    private double distance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private int tileManhattanDistance(double ax, double ay, double bx, double by) {
        int dx = Math.abs((int) Math.floor(ax / TILE_SIZE) - (int) Math.floor(bx / TILE_SIZE));
        int dy = Math.abs((int) Math.floor(ay / TILE_SIZE) - (int) Math.floor(by / TILE_SIZE));
        return dx + dy;
    }

    // ── Result container for commit validation ────────────────────────────────

    public static class CommitValidationResult {
        public final boolean permitted;
        public final WorldAction action;   // non-null when permitted
        public final String rejectMessage; // non-null when rejected

        private CommitValidationResult(boolean permitted, WorldAction action, String rejectMessage) {
            this.permitted = permitted;
            this.action = action;
            this.rejectMessage = rejectMessage;
        }

        public static CommitValidationResult permitted(WorldAction action) {
            return new CommitValidationResult(true, action, null);
        }

        public static CommitValidationResult rejected(String message) {
            return new CommitValidationResult(false, null, message);
        }
    }
}
