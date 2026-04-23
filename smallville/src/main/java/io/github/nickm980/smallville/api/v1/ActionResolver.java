package io.github.nickm980.smallville.api.v1;

import io.github.nickm980.smallville.entities.InventoryItem;
import io.github.nickm980.smallville.entities.RejectReason;
import io.github.nickm980.smallville.entities.WorldAction;
import io.github.nickm980.smallville.entities.WorldObjectInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single enforcement point for all world actions.
 *
 * Both player and NPC WorldActions pass through resolve() before any state
 * is mutated. The resolver is blind to actorType — the same affordance,
 * inventory, range, and conflict rules apply to all actors.
 *
 * On rejection, a RejectResult is returned carrying a reason code that
 * the caller must write to the actor's EpistemicMemory as a BeliefCorrection,
 * then enqueue an immediate PlanReplace cognition job.
 */
public class ActionResolver {

    private static final Logger LOG = LoggerFactory.getLogger(ActionResolver.class);

    private static final double DEFAULT_INTERACTION_RANGE_TILES = 3.0;
    private static final double TILE_SIZE = 32.0;

    // ── Verb → required affordance property on the target object ─────────────
    // If a verb is listed here, the target object must have this property set to
    // true in its properties map for the action to be permitted.
    private static final Map<String, String> VERB_REQUIRES_PROPERTY = Map.of(
        "write",       "writable",
        "sit",         "sit-able",
        "open",        "can_open_close",
        "close",       "can_open_close",
        "carry",       "carriable",
        "place_object","flat_surface",
        "observe",     "can_observe",
        "inspect",     "interactive"
    );

    // ── Verb → required inventory grant ──────────────────────────────────────
    // If a verb is listed here, the actor must hold an item with this grant.
    // "open" is handled separately (instance-specific grant check).
    private static final Map<String, String> VERB_REQUIRES_GRANT = Map.of(
        "write",       "writing_utensil",
        "cut",         "cutting_tool"
    );

    public static class ResolveResult {
        public final boolean permitted;
        public final RejectReason rejectReason;
        /** Human-readable explanation — included in BeliefCorrection payload. */
        public final String explanation;
        /** Item that should be consumed if consumable (null if no consumption). */
        public final InventoryItem itemToConsume;

        private ResolveResult(boolean permitted, RejectReason reason, String explanation,
                              InventoryItem itemToConsume) {
            this.permitted = permitted;
            this.rejectReason = reason;
            this.explanation = explanation;
            this.itemToConsume = itemToConsume;
        }

        public static ResolveResult permit(InventoryItem itemToConsume) {
            return new ResolveResult(true, null, null, itemToConsume);
        }

        public static ResolveResult permit() {
            return new ResolveResult(true, null, null, null);
        }

        public static ResolveResult reject(RejectReason reason, String explanation) {
            return new ResolveResult(false, reason, explanation, null);
        }
    }

    private final Map<String, Map<String, InventoryItem>> inventoryByActor;
    private final Map<String, WorldObjectInstance> objectInstances;
    private final Map<String, Map<String, Object>> objectTypeDefinitions;

    public ActionResolver(
            Map<String, Map<String, InventoryItem>> inventoryByActor,
            Map<String, WorldObjectInstance> objectInstances,
            Map<String, Map<String, Object>> objectTypeDefinitions) {
        this.inventoryByActor = inventoryByActor;
        this.objectInstances = objectInstances;
        this.objectTypeDefinitions = objectTypeDefinitions;
    }

    /**
     * Resolve a WorldAction. Returns a ResolveResult indicating whether the
     * action is permitted and, if not, why.
     *
     * Does NOT mutate world state — callers apply state changes after a permit.
     */
    public ResolveResult resolve(WorldAction action) {
        String verb = action.getVerb();
        if (verb == null || verb.isBlank()) {
            return ResolveResult.reject(RejectReason.AFFORDANCE_DENIED, "verb is null or blank");
        }

        switch (action.getTargetType()) {
            case OBJECT:
                return resolveObjectAction(action);
            case AGENT:
            case PLAYER:
                return resolveEntityAction(action);
            case TILE:
                return resolveTileAction(action);
            default:
                return ResolveResult.reject(RejectReason.AFFORDANCE_DENIED,
                        "unknown target type: " + action.getTargetType());
        }
    }

    // ── Object-targeted actions ───────────────────────────────────────────────

    private ResolveResult resolveObjectAction(WorldAction action) {
        String verb = action.getVerb();
        WorldObjectInstance target = objectInstances.get(action.getTargetId());

        if (target == null) {
            return ResolveResult.reject(RejectReason.TARGET_NOT_FOUND,
                    "object instance '" + action.getTargetId() + "' does not exist");
        }

        // 1. Range check
        ResolveResult rangeCheck = checkRange(action, target.getX(), target.getY());
        if (!rangeCheck.permitted) return rangeCheck;

        // 2. Affordance check — does this object type permit this verb?
        ResolveResult affordanceCheck = checkObjectAffordance(verb, target);
        if (!affordanceCheck.permitted) return affordanceCheck;

        // 3. State compatibility check
        ResolveResult stateCheck = checkObjectState(verb, target);
        if (!stateCheck.permitted) return stateCheck;

        // 4. Open/close: instance-specific key check
        if ("open".equals(verb) && target.isLocked()) {
            return checkKey(action, target);
        }

        // 5. Generic inventory grant check (e.g. write requires writing_utensil)
        if (VERB_REQUIRES_GRANT.containsKey(verb)) {
            return checkGenericGrant(action, verb);
        }

        return ResolveResult.permit();
    }

    // ── Entity-targeted actions (speak, attack, give, etc.) ──────────────────

    private ResolveResult resolveEntityAction(WorldAction action) {
        String verb = action.getVerb();

        // For speak, attack, give: range is checked against the entity position.
        // We accept these as permitted at the resolver level (the simulation layer
        // handles dialogue turns and social outcomes). Future: look up entity tile
        // position from world state for strict range enforcement.

        if (VERB_REQUIRES_GRANT.containsKey(verb)) {
            return checkGenericGrant(action, verb);
        }

        return ResolveResult.permit();
    }

    // ── Tile-targeted actions (move) ─────────────────────────────────────────

    private ResolveResult resolveTileAction(WorldAction action) {
        // Move actions: walkability and occupancy checks belong in the grid layer.
        // ActionResolver permits all tile-targeted verbs and lets the TurnEngine
        // apply occupancy and collision rules.
        return ResolveResult.permit();
    }

    // ── Check helpers ─────────────────────────────────────────────────────────

    private ResolveResult checkRange(WorldAction action, double targetX, double targetY) {
        double dx = action.getActorX() - targetX;
        double dy = action.getActorY() - targetY;
        double distanceTiles = Math.sqrt(dx * dx + dy * dy) / TILE_SIZE;
        if (distanceTiles > DEFAULT_INTERACTION_RANGE_TILES) {
            return ResolveResult.reject(RejectReason.OUT_OF_RANGE,
                    String.format("%.1f tiles from target; max is %.1f",
                            distanceTiles, DEFAULT_INTERACTION_RANGE_TILES));
        }
        return ResolveResult.permit();
    }

    private ResolveResult checkObjectAffordance(String verb, WorldObjectInstance target) {
        String requiredProperty = VERB_REQUIRES_PROPERTY.get(verb);
        if (requiredProperty == null) return ResolveResult.permit(); // verb has no property gate

        Map<String, Object> props = mergedProperties(target);
        Object v = props.get(requiredProperty);
        if (!Boolean.TRUE.equals(v)) {
            return ResolveResult.reject(RejectReason.AFFORDANCE_DENIED,
                    "object '" + target.getName() + "' does not have property '" + requiredProperty
                    + "' required for verb '" + verb + "'");
        }
        return ResolveResult.permit();
    }

    private ResolveResult checkObjectState(String verb, WorldObjectInstance target) {
        switch (verb) {
            case "open":
                if (target.isOpen()) {
                    return ResolveResult.reject(RejectReason.STATE_INCOMPATIBLE,
                            "'" + target.getName() + "' is already open");
                }
                break;
            case "close":
                if (!target.isOpen()) {
                    return ResolveResult.reject(RejectReason.STATE_INCOMPATIBLE,
                            "'" + target.getName() + "' is already closed");
                }
                break;
            case "carry":
                if (target.isCarried()) {
                    return ResolveResult.reject(RejectReason.STATE_INCOMPATIBLE,
                            "'" + target.getName() + "' is already being carried by "
                            + target.getHeldBy());
                }
                break;
        }
        return ResolveResult.permit();
    }

    private ResolveResult checkKey(WorldAction action, WorldObjectInstance target) {
        Map<String, InventoryItem> inventory = inventoryByActor.getOrDefault(
                action.getActorId(), Map.of());

        Optional<InventoryItem> key = inventory.values().stream()
                .filter(item -> item.opensInstance(target.getInstanceId()))
                .findFirst();

        if (key.isEmpty()) {
            return ResolveResult.reject(RejectReason.MISSING_KEY,
                    "'" + action.getActorId() + "' holds no key that opens '"
                    + target.getName() + "' (instanceId=" + target.getInstanceId() + ")");
        }

        InventoryItem k = key.get();
        LOG.debug("ActionResolver: {} opens {} with item {} (consumable={})",
                action.getActorId(), target.getName(), k.getId(), k.isConsumable());
        return ResolveResult.permit(k.isConsumable() ? k : null);
    }

    private ResolveResult checkGenericGrant(WorldAction action, String verb) {
        String requiredGrant = VERB_REQUIRES_GRANT.get(verb);
        Map<String, InventoryItem> inventory = inventoryByActor.getOrDefault(
                action.getActorId(), Map.of());

        Optional<InventoryItem> tool = inventory.values().stream()
                .filter(item -> item.hasGrant(requiredGrant))
                .findFirst();

        if (tool.isEmpty()) {
            return ResolveResult.reject(RejectReason.MISSING_GRANT,
                    "'" + action.getActorId() + "' needs an item granting '"
                    + requiredGrant + "' to perform '" + verb + "'");
        }

        InventoryItem t = tool.get();
        return ResolveResult.permit(t.isConsumable() ? t : null);
    }

    // ── Pre-compute legal actions for LLM prompt ──────────────────────────────

    /**
     * Structured descriptor for one legal action. Used by the client endpoint
     * so the client doesn't have to parse "verb(name)" strings.
     */
    public static class ActionDescriptor {
        public final String verb;
        public final String targetId;
        public final String targetName;
        public final String targetKind; // "object", "agent"
        public final String label;

        public ActionDescriptor(String verb, String targetId, String targetName,
                                String targetKind, String label) {
            this.verb = verb;
            this.targetId = targetId;
            this.targetName = targetName;
            this.targetKind = targetKind;
            this.label = label;
        }

        public java.util.Map<String, String> toMap() {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("verb", verb);
            m.put("targetId", targetId);
            m.put("targetName", targetName);
            m.put("targetKind", targetKind);
            m.put("label", label);
            return m;
        }
    }

    /**
     * Returns structured action descriptors for a given actor — used by the
     * player context-action endpoint so the client can filter its UI by
     * server-authoritative grants (e.g. instance-specific key binding).
     */
    public List<ActionDescriptor> legalDescriptorsFor(String actorId, double actorX, double actorY,
                                                      List<String> nearbyAgentIds) {
        List<ActionDescriptor> descriptors = new ArrayList<>();
        Map<String, InventoryItem> inventory = inventoryByActor.getOrDefault(actorId, Map.of());

        for (WorldObjectInstance obj : objectInstances.values()) {
            double dx = actorX - obj.getX();
            double dy = actorY - obj.getY();
            double distTiles = Math.sqrt(dx * dx + dy * dy) / TILE_SIZE;
            if (distTiles > DEFAULT_INTERACTION_RANGE_TILES) continue;

            Map<String, Object> props = mergedProperties(obj);
            String id = obj.getInstanceId();
            String name = obj.getName();

            if (Boolean.TRUE.equals(props.get("interactive"))) {
                descriptors.add(new ActionDescriptor("inspect", id, name, "object", "Inspect"));
            }

            if (Boolean.TRUE.equals(props.get("can_open_close"))) {
                if (!obj.isOpen()) {
                    if (!obj.isLocked()) {
                        descriptors.add(new ActionDescriptor("open", id, name, "object", "Open"));
                    } else {
                        boolean hasKey = inventory.values().stream().anyMatch(i -> i.opensInstance(id));
                        if (hasKey) descriptors.add(new ActionDescriptor("open", id, name, "object", "Open"));
                    }
                } else {
                    descriptors.add(new ActionDescriptor("close", id, name, "object", "Close"));
                }
            }

            // transition_point (door/gate): lock/unlock based on locked state and key
            if (Boolean.TRUE.equals(props.get("transition_point"))) {
                boolean locked = Boolean.TRUE.equals(props.get("locked"));
                if (locked) {
                    boolean hasKey = inventory.values().stream().anyMatch(i -> i.opensInstance(id));
                    if (hasKey) {
                        descriptors.add(new ActionDescriptor("unlock", id, name, "object", "Unlock"));
                    }
                } else {
                    boolean hasKey = !inventory.isEmpty() && inventory.values().stream()
                            .anyMatch(i -> i.hasGrant("key") || i.opensInstance(id));
                    if (hasKey) {
                        descriptors.add(new ActionDescriptor("lock", id, name, "object", "Lock"));
                    }
                }
            }

            if (Boolean.TRUE.equals(props.get("carriable")) && !obj.isCarried()) {
                descriptors.add(new ActionDescriptor("carry", id, name, "object", "Carry"));
            }

            if (Boolean.TRUE.equals(props.get("writable"))) {
                boolean hasTool = inventory.values().stream().anyMatch(i -> i.hasGrant("writing_utensil"));
                if (hasTool) descriptors.add(new ActionDescriptor("write", id, name, "object", "Write"));
            }
        }

        for (String entityId : nearbyAgentIds) {
            descriptors.add(new ActionDescriptor("speak", entityId, entityId, "agent", "Talk"));
        }

        descriptors.add(new ActionDescriptor("wait", "", "", "none", "Wait"));
        return descriptors;
    }

    /**
     * Returns a list of human-readable legal action descriptions for a given actor.
     * Includes object interactions (filtered by affordance + inventory), speak
     * actions for nearby agents, and always includes "wait" as a fallback.
     *
     * Used by the LLM prompt builder to constrain NPC plan generation.
     */
    public List<String> legalActionsFor(String actorId, double actorX, double actorY,
                                        List<String> nearbyAgentIds) {
        return legalDescriptorsFor(actorId, actorX, actorY, nearbyAgentIds).stream()
                .map(d -> d.targetId.isEmpty() ? d.verb : d.verb + "(" + d.targetName + ")")
                .collect(Collectors.toList());
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Merge type-level properties with instance-level properties.
     * Instance properties override type-level ones.
     */
    private Map<String, Object> mergedProperties(WorldObjectInstance instance) {
        Map<String, Object> merged = new java.util.HashMap<>();
        Map<String, Object> typeDef = objectTypeDefinitions.getOrDefault(
                instance.getTypeId(), Map.of());
        merged.putAll(typeDef);
        merged.putAll(instance.getProperties()); // instance overrides type
        return merged;
    }
}
