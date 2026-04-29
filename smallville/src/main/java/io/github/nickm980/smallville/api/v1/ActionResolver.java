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
        // "use" is checked dynamically (consumable OR usable+produces_item)
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

        // 6. Use: must be consumable OR a usable machine that produces an item
        if ("use".equals(verb)) {
            Map<String, Object> props = mergedProperties(target);
            boolean isConsumable = Boolean.TRUE.equals(props.get("consumable"));
            boolean isMachine = Boolean.TRUE.equals(props.get("usable")) && props.containsKey("produces_item");
            if (!isConsumable && !isMachine) {
                return ResolveResult.reject(RejectReason.AFFORDANCE_DENIED,
                    "'" + target.getName() + "' cannot be used (not consumable or a production machine)");
            }
            // Consumable must be held by the actor
            if (isConsumable) {
                Map<String, InventoryItem> inv = inventoryByActor.getOrDefault(action.getActorId(), Map.of());
                if (!inv.containsKey(target.getInstanceId())) {
                    return ResolveResult.reject(RejectReason.MISSING_GRANT,
                        "'" + action.getActorId() + "' must be holding '" + target.getName() + "' to use it");
                }
            }
        }

        return ResolveResult.permit();
    }

    // ── Entity-targeted actions (speak, attack, give, etc.) ──────────────────

    private ResolveResult resolveEntityAction(WorldAction action) {
        String verb = action.getVerb();

        // give: actor must hold the item specified in action.getItemId()
        if ("give".equals(verb)) {
            String itemId = action.getItemId();
            if (itemId == null || itemId.isBlank()) {
                return ResolveResult.reject(RejectReason.AFFORDANCE_DENIED,
                        "give requires an itemId (the item to hand over)");
            }
            Map<String, InventoryItem> inventory = inventoryByActor.getOrDefault(
                    action.getActorId(), Map.of());
            if (!inventory.containsKey(itemId)) {
                return ResolveResult.reject(RejectReason.MISSING_GRANT,
                        "'" + action.getActorId() + "' does not hold item '" + itemId + "'");
            }
            return ResolveResult.permit();
        }

        // For speak, attack: range is checked against the entity position at the
        // simulation layer; ActionResolver permits all other entity-targeted verbs.
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
     * so the client doesn't have to parse "verb(name)" strings, and by the LLM
     * prompt builder so agents understand *why* each action is available.
     */
    public static class ActionDescriptor {
        public final String verb;
        public final String targetId;
        public final String targetName;
        public final String targetKind; // "object", "agent"
        public final String label;
        /** Human-readable context for LLM: distance, required item, state. */
        public final String reason;

        public ActionDescriptor(String verb, String targetId, String targetName,
                                String targetKind, String label, String reason) {
            this.verb = verb;
            this.targetId = targetId;
            this.targetName = targetName;
            this.targetKind = targetKind;
            this.label = label;
            this.reason = reason;
        }

        public java.util.Map<String, String> toMap() {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            m.put("verb", verb);
            m.put("targetId", targetId);
            m.put("targetName", targetName);
            m.put("targetKind", targetKind);
            m.put("label", label);
            if (reason != null && !reason.isEmpty()) m.put("reason", reason);
            return m;
        }
    }

    /**
     * Position-aware overload: filters tackle to agents that are NOT adjacent (≥2 tiles).
     * Used by SimulationService when agent positions are available.
     *
     * @param nearbyAgents map of agentId → [x, y]
     */
    public List<ActionDescriptor> legalDescriptorsFor(String actorId, double actorX, double actorY,
                                                      Map<String, double[]> nearbyAgents) {
        // Build base descriptors via the list overload (handles speak/punch/kick/throw/give/tackle)
        // then strip tackle entries for adjacent targets and re-add only for 2+ tile targets.
        List<String> ids = new ArrayList<>(nearbyAgents.keySet());
        List<ActionDescriptor> base = legalDescriptorsFor(actorId, actorX, actorY, ids);

        // Remove tackle entries (they were added without distance filtering in the list overload)
        base.removeIf(d -> "tackle".equals(d.verb));

        // Re-add tackle only for non-adjacent targets
        for (Map.Entry<String, double[]> entry : nearbyAgents.entrySet()) {
            String entityId = entry.getKey();
            double[] pos = entry.getValue();
            int dist = tileManhattanDist(actorX, actorY, pos[0], pos[1]);
            if (dist >= 2) {
                base.add(new ActionDescriptor("tackle", entityId, entityId, "agent", "Tackle",
                        dist + " tiles, closes to adjacent"));
            }
        }
        return base;
    }

    private int tileManhattanDist(double ax, double ay, double bx, double by) {
        int dx = Math.abs((int) Math.floor(ax / TILE_SIZE) - (int) Math.floor(bx / TILE_SIZE));
        int dy = Math.abs((int) Math.floor(ay / TILE_SIZE) - (int) Math.floor(by / TILE_SIZE));
        return dx + dy;
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

            String distStr = distTiles <= 1.0 ? "adjacent" : distTiles <= 2.0 ? "nearby" : "close by";
            Map<String, Object> props = mergedProperties(obj);
            String id = obj.getInstanceId();
            String name = obj.getName();

            if (Boolean.TRUE.equals(props.get("interactive"))) {
                descriptors.add(new ActionDescriptor("inspect", id, name, "object", "Inspect",
                        distStr + ", interactive"));
            }

            if (Boolean.TRUE.equals(props.get("can_open_close"))) {
                if (!obj.isOpen()) {
                    if (!obj.isLocked()) {
                        descriptors.add(new ActionDescriptor("open", id, name, "object", "Open",
                                distStr + ", closed"));
                    } else {
                        boolean hasKey = inventory.values().stream().anyMatch(i -> i.opensInstance(id));
                        if (hasKey) descriptors.add(new ActionDescriptor("open", id, name, "object", "Open",
                                distStr + ", locked — you hold the key"));
                    }
                } else {
                    descriptors.add(new ActionDescriptor("close", id, name, "object", "Close",
                            distStr + ", open"));
                }
            }

            // transition_point (door/gate): lock/unlock based on locked state and key
            if (Boolean.TRUE.equals(props.get("transition_point"))) {
                boolean locked = Boolean.TRUE.equals(props.get("locked"));
                if (locked) {
                    boolean hasKey = inventory.values().stream().anyMatch(i -> i.opensInstance(id));
                    if (hasKey) {
                        descriptors.add(new ActionDescriptor("unlock", id, name, "object", "Unlock",
                                distStr + ", you hold the matching key"));
                    }
                } else {
                    boolean hasKey = !inventory.isEmpty() && inventory.values().stream()
                            .anyMatch(i -> i.hasGrant("key") || i.opensInstance(id));
                    if (hasKey) {
                        descriptors.add(new ActionDescriptor("lock", id, name, "object", "Lock",
                                distStr + ", you hold a key"));
                    }
                }
            }

            if (Boolean.TRUE.equals(props.get("carriable")) && !obj.isCarried()) {
                descriptors.add(new ActionDescriptor("carry", id, name, "object", "Carry",
                        distStr + ", carriable"));
            }

            if (Boolean.TRUE.equals(props.get("writable"))) {
                boolean hasTool = inventory.values().stream().anyMatch(i -> i.hasGrant("writing_utensil"));
                if (hasTool) descriptors.add(new ActionDescriptor("write", id, name, "object", "Write",
                        distStr + ", you hold a writing utensil"));
            }

            if (Boolean.TRUE.equals(props.get("flat_surface")) && !inventory.isEmpty()) {
                descriptors.add(new ActionDescriptor("place_object", id, name, "object", "Place Item",
                        distStr + ", flat surface — you are carrying something"));
            }

            // Production machine (e.g. coffee machine) — usable from nearby
            if (Boolean.TRUE.equals(props.get("usable")) && props.containsKey("produces_item")) {
                String produces = String.valueOf(props.get("produces_item"));
                descriptors.add(new ActionDescriptor("use", id, name, "object", "Use " + name,
                        distStr + ", produces " + produces));
            }
        }

        // Consumable items the actor is holding — always available regardless of range
        for (InventoryItem heldItem : inventory.values()) {
            WorldObjectInstance heldObj = objectInstances.get(heldItem.getId());
            if (heldObj != null) {
                Map<String, Object> heldProps = mergedProperties(heldObj);
                if (Boolean.TRUE.equals(heldProps.get("consumable"))) {
                    int heal = heldProps.get("heal_amount") instanceof Number
                        ? ((Number) heldProps.get("heal_amount")).intValue() : 20;
                    descriptors.add(new ActionDescriptor("use", heldItem.getId(), heldObj.getName(), "object",
                            "Eat/Drink " + heldObj.getName(), "in inventory, restores " + heal + " HP"));
                }
            }
        }

        Map<String, InventoryItem> actorInventory = inventoryByActor.getOrDefault(actorId, Map.of());

        for (String entityId : nearbyAgentIds) {
            descriptors.add(new ActionDescriptor("speak", entityId, entityId, "agent", "Talk",
                    "in range"));
            descriptors.add(new ActionDescriptor("punch", entityId, entityId, "agent", "Punch",
                    "adjacent only"));
            descriptors.add(new ActionDescriptor("kick", entityId, entityId, "agent", "Kick",
                    "adjacent only, more damage"));
            descriptors.add(new ActionDescriptor("tackle", entityId, entityId, "agent", "Tackle",
                    "2–4 tiles, closes distance"));
            if (!actorInventory.isEmpty()) {
                descriptors.add(new ActionDescriptor("throw", entityId, entityId, "agent", "Throw at",
                        "you have items to throw"));
            }
            // give: one entry per (agent × carried item) so the LLM knows item + recipient
            for (InventoryItem item : actorInventory.values()) {
                // targetName encodes "RecipientName,ItemName" — SimulationService splits on ','
                String compoundTarget = entityId + "," + item.getDisplayName();
                descriptors.add(new ActionDescriptor("give", entityId, compoundTarget, "agent",
                        "Give " + item.getDisplayName() + " to " + entityId,
                        "you hold " + item.getDisplayName()));
            }
        }

        descriptors.add(new ActionDescriptor("wait", "", "", "none", "Wait", "always available"));
        return descriptors;
    }

    /**
     * Returns a list of human-readable legal action descriptions for a given actor,
     * with context explaining why each action is available. Format:
     * "carry(KeyCard) [0.8 tiles, carriable]"
     *
     * Used by the LLM prompt builder to constrain NPC plan generation.
     */
    public List<String> legalActionsFor(String actorId, double actorX, double actorY,
                                        List<String> nearbyAgentIds) {
        return legalDescriptorsFor(actorId, actorX, actorY, nearbyAgentIds).stream()
                .map(d -> {
                    String base = d.targetId.isEmpty() ? d.verb : d.verb + "(" + d.targetName + ")";
                    return (d.reason != null && !d.reason.isEmpty()) ? base + " [" + d.reason + "]" : base;
                })
                .collect(Collectors.toList());
    }

    /** Position-aware overload — filters tackle to non-adjacent targets. */
    public List<String> legalActionsFor(String actorId, double actorX, double actorY,
                                        Map<String, double[]> nearbyAgents) {
        return legalDescriptorsFor(actorId, actorX, actorY, nearbyAgents).stream()
                .map(d -> {
                    String base = d.targetId.isEmpty() ? d.verb : d.verb + "(" + d.targetName + ")";
                    return (d.reason != null && !d.reason.isEmpty()) ? base + " [" + d.reason + "]" : base;
                })
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
