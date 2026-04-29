package io.github.nickm980.smallville.api.v1;

import io.github.nickm980.smallville.World;
import io.github.nickm980.smallville.entities.*;
import io.github.nickm980.smallville.memory.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Applies world-state mutations for committed agent actions from the tool loop.
 *
 * Each {@code apply()} call handles the verb-specific side effects of a
 * {@link TurnResult} that was permitted by {@link ActionResolver}. The caller
 * ({@code SimulationService.applyTurnResult}) is responsible for activity/memory
 * bookkeeping; this class handles physical world state only.
 *
 * Thread safety: call only from the main simulation thread (inside
 * {@code pendingCognitionApplies} drain).
 */
public class WorldStateMutator {

    private static final Logger LOG = LoggerFactory.getLogger(WorldStateMutator.class);
    private static final double TILE_SIZE = 32.0;
    private static final int MAX_WRITING_LENGTH = 280;

    private final World world;
    private final Map<String, WorldObjectInstance> objectInstances;

    public WorldStateMutator(World world, Map<String, WorldObjectInstance> objectInstances) {
        this.world = world;
        this.objectInstances = objectInstances;
    }

    /**
     * Apply verb-specific world mutations for a committed action.
     *
     * @param agent      the acting agent
     * @param verb       action verb (carry, give, open, close, unlock, lock, write, speak, place_object, …)
     * @param targetId   target entity id or agent name
     * @param targetType target entity type
     * @param payload    optional speech text, item id/name, or written content
     * @param turnNumber current simulation turn (for hearsay timestamps)
     * @return short narrative suitable for a memory Observation, or null for no-op verbs
     */
    public String apply(Agent agent, String verb, String targetId,
                        WorldAction.TargetType targetType, String payload, int turnNumber) {
        if (verb == null || agent == null) return null;
        switch (verb.toLowerCase()) {
            case "carry":        return applyCarry(agent, targetId);
            case "give":         return applyGive(agent, targetId, payload, turnNumber);
            case "open":         return applyOpen(targetId, false);
            case "close":        return applyClose(targetId);
            case "unlock":       return applyUnlock(targetId);
            case "lock":         return applyLock(targetId);
            case "write":        return applyWrite(targetId, payload);
            case "speak":        return applySpeak(agent, targetId, payload, turnNumber);
            case "place_object": return applyPlaceObject(agent, targetId, payload);
            case "use":          return applyUse(agent, targetId, turnNumber);
            // Combat: damage applied by applyTurnResult with apply-time range validation
            case "punch": case "kick": case "tackle": case "attack":
                return null;
            // Observation/inspection verbs leave no physical trace
            case "observe": case "inspect": case "sit": case "wait":
                return null;
            default:
                LOG.debug("[WorldStateMutator] No mutation handler for verb '{}' — skipping", verb);
                return null;
        }
    }

    // ── Verb handlers ─────────────────────────────────────────────────────────

    private String applyCarry(Agent agent, String objectId) {
        WorldObjectInstance obj = objectInstances.get(objectId);
        if (obj == null) {
            LOG.warn("[WorldStateMutator] carry: object '{}' not found for {}", objectId, agent.getFullName());
            return null;
        }
        if (obj.getProperties() == null) obj.setProperties(new HashMap<>());
        obj.getProperties().put("heldBy", agent.getFullName());
        obj.setHeldBy(agent.getFullName());
        obj.setLocation(null);
        obj.setX(agent.getX());
        obj.setY(agent.getY());
        // Add to typed inventory if not already held.
        // item.id must equal objectId so objectInstances.get(item.getId()) resolves correctly.
        if (!agent.getInventory().containsKey(objectId)) {
            InventoryItem item = new InventoryItem(objectId, obj.getName());
            item.setId(objectId);
            agent.addInventoryItem(item);
        }
        refreshCarriedItems(agent);
        LOG.info("[WorldStateMutator] {} picked up '{}'", agent.getFullName(), obj.getName());
        return "picked up " + obj.getName();
    }

    private String applyGive(Agent agent, String recipientId, String itemId, int turnNumber) {
        if (itemId == null || itemId.isBlank()) {
            // Try giving the first held item if no specific item is named
            Map<String, InventoryItem> inv = agent.getInventory();
            if (inv.isEmpty()) {
                LOG.warn("[WorldStateMutator] give: {} has no items to give to '{}'", agent.getFullName(), recipientId);
                return null;
            }
            itemId = inv.keySet().iterator().next();
        }

        InventoryItem item = agent.removeInventoryItem(itemId);
        if (item == null) {
            // Try by display name as fallback
            String searchName = itemId;
            item = agent.getInventory().values().stream()
                .filter(i -> i.getDisplayName() != null && i.getDisplayName().equalsIgnoreCase(searchName))
                .findFirst().orElse(null);
            if (item != null) {
                agent.removeInventoryItem(item.getId());
            }
        }
        if (item == null) {
            LOG.warn("[WorldStateMutator] give: {} does not hold item '{}' to give to '{}'",
                agent.getFullName(), itemId, recipientId);
            return null;
        }

        Agent recipient = world.getAgent(recipientId).orElse(null);
        if (recipient == null) {
            LOG.warn("[WorldStateMutator] give: recipient '{}' not found — item lost", recipientId);
            return null;
        }

        recipient.addInventoryItem(item);
        refreshCarriedItems(agent);
        refreshCarriedItems(recipient);

        String itemName = item.getDisplayName() != null ? item.getDisplayName() : item.getId();
        // Notify recipient via hearsay
        recipient.getEpistemicMemory().ingestHearsay(
            agent.getFullName(),
            agent.getFullName() + " gave you " + itemName,
            turnNumber, 0.95);
        // Give recipient a memory observation too
        recipient.getMemoryStream().add(new Observation(agent.getFullName() + " gave me " + itemName));

        LOG.info("[WorldStateMutator] {} gave '{}' to {}", agent.getFullName(), itemName, recipient.getFullName());
        return "gave " + itemName + " to " + recipient.getFullName();
    }

    /**
     * Open an object; also used for unlock+open when {@code alsoUnlock} is true.
     */
    private String applyOpen(String objectId, boolean alsoUnlock) {
        WorldObjectInstance obj = objectInstances.get(objectId);
        if (obj == null) {
            LOG.warn("[WorldStateMutator] open: object '{}' not found", objectId);
            return null;
        }
        obj.setState("isOpen", true);
        obj.setState("isLocked", false);
        if (obj.getProperties() == null) obj.setProperties(new HashMap<>());
        obj.getProperties().put("doorOpen", true);
        obj.getProperties().put("passable", true);
        obj.getProperties().put("locked", false);
        LOG.debug("[WorldStateMutator] opened '{}'", objectId);
        return "opened " + obj.getName();
    }

    private String applyClose(String objectId) {
        WorldObjectInstance obj = objectInstances.get(objectId);
        if (obj == null) {
            LOG.warn("[WorldStateMutator] close: object '{}' not found", objectId);
            return null;
        }
        obj.setState("isOpen", false);
        if (obj.getProperties() == null) obj.setProperties(new HashMap<>());
        obj.getProperties().put("doorOpen", false);
        obj.getProperties().put("passable", false);
        LOG.debug("[WorldStateMutator] closed '{}'", objectId);
        return "closed " + obj.getName();
    }

    private String applyUnlock(String objectId) {
        WorldObjectInstance obj = objectInstances.get(objectId);
        if (obj == null) {
            LOG.warn("[WorldStateMutator] unlock: object '{}' not found", objectId);
            return null;
        }
        obj.setState("isLocked", false);
        if (obj.getProperties() == null) obj.setProperties(new HashMap<>());
        obj.getProperties().put("locked", false);
        obj.getProperties().put("passable", true);
        LOG.debug("[WorldStateMutator] unlocked '{}'", objectId);
        return "unlocked " + obj.getName();
    }

    private String applyLock(String objectId) {
        WorldObjectInstance obj = objectInstances.get(objectId);
        if (obj == null) {
            LOG.warn("[WorldStateMutator] lock: object '{}' not found", objectId);
            return null;
        }
        obj.setState("isLocked", true);
        obj.setState("isOpen", false);
        if (obj.getProperties() == null) obj.setProperties(new HashMap<>());
        obj.getProperties().put("locked", true);
        obj.getProperties().put("passable", false);
        obj.getProperties().put("doorOpen", false);
        LOG.debug("[WorldStateMutator] locked '{}'", objectId);
        return "locked " + obj.getName();
    }

    private String applyWrite(String objectId, String text) {
        WorldObjectInstance obj = objectInstances.get(objectId);
        if (obj == null) {
            LOG.warn("[WorldStateMutator] write: object '{}' not found", objectId);
            return null;
        }
        if (text == null || text.isBlank()) {
            LOG.warn("[WorldStateMutator] write: no text supplied for '{}'", objectId);
            return null;
        }
        String sanitized = text.trim().length() > MAX_WRITING_LENGTH
            ? text.trim().substring(0, MAX_WRITING_LENGTH) : text.trim();
        if (obj.getProperties() == null) obj.setProperties(new HashMap<>());
        obj.getProperties().put("has_writing", sanitized);
        LOG.debug("[WorldStateMutator] wrote on '{}': {}", objectId, sanitized.substring(0, Math.min(40, sanitized.length())));
        return "wrote on " + obj.getName();
    }

    private String applySpeakImpl(Agent agent, String targetId, String text, int turnNumber) {
        if (text == null || text.isBlank()) return null;
        world.getAgent(targetId).ifPresent(target -> {
            if (!(target instanceof Player)) {
                target.getEpistemicMemory().ingestHearsay(
                    agent.getFullName(), text, turnNumber, 0.8);
                target.getMemoryStream().add(new Observation(
                    agent.getFullName() + " said to me: \"" + text + "\""));
            }
        });
        return "spoke to " + targetId;
    }

    private String applySpeak(Agent agent, String targetId, String text, int turnNumber) {
        return applySpeakImpl(agent, targetId, text, turnNumber);
    }

    private String applyPlaceObject(Agent agent, String surfaceId, String itemId) {
        Map<String, InventoryItem> inv = agent.getInventory();
        if (inv.isEmpty()) {
            LOG.warn("[WorldStateMutator] place_object: {} has nothing to place", agent.getFullName());
            return null;
        }

        // Use itemId (payload) if specified, otherwise first item
        String objectId = (itemId != null && !itemId.isBlank() && inv.containsKey(itemId))
            ? itemId : inv.keySet().iterator().next();

        WorldObjectInstance obj = objectInstances.get(objectId);
        if (obj == null) {
            inv.remove(objectId);
            agent.removeInventoryItem(objectId);
            return null;
        }

        // Anchor to surface position if surface target is valid
        WorldObjectInstance surface = surfaceId != null ? objectInstances.get(surfaceId) : null;
        double placeX = surface != null ? snapToTile(surface.getX()) : snapToTile(agent.getX());
        double placeY = surface != null ? snapToTile(surface.getY()) : snapToTile(agent.getY());
        String placeLocation = surface != null ? surface.getLocation()
            : (agent.getLocation() != null ? agent.getLocation().getFullPath() : null);

        // If placing on the trash receptacle, remove the item from the world entirely
        if (surface != null && isTrashReceptacle(surface)) {
            objectInstances.remove(objectId);
            agent.removeInventoryItem(objectId);
            refreshCarriedItems(agent);
            LOG.info("[WorldStateMutator] {} disposed of '{}' in trash bin", agent.getFullName(), obj.getName());
            return "disposed of " + obj.getName() + " in the trash";
        }

        obj.setX(placeX);
        obj.setY(placeY);
        obj.setLocation(placeLocation);
        if (obj.getProperties() == null) obj.setProperties(new HashMap<>());
        obj.getProperties().remove("heldBy");
        obj.setHeldBy(null);

        agent.removeInventoryItem(objectId);
        refreshCarriedItems(agent);

        LOG.info("[WorldStateMutator] {} placed '{}' at ({},{})", agent.getFullName(), obj.getName(), placeX, placeY);
        return "placed " + obj.getName();
    }

    private boolean isTrashReceptacle(WorldObjectInstance obj) {
        if (obj == null || obj.getProperties() == null) return false;
        return Boolean.TRUE.equals(obj.getProperties().get("is_trash_receptacle"));
    }

    /**
     * Use a consumable item (food/drink → heal HP and leave trash) or
     * interact with a production machine (coffee machine → brew coffee into inventory).
     */
    private String applyUse(Agent agent, String targetId, int turnNumber) {
        WorldObjectInstance obj = objectInstances.get(targetId);
        if (obj == null) {
            // Try resolving by display name from inventory
            for (InventoryItem item : agent.getInventory().values()) {
                if (item.getId().equals(targetId) ||
                        (item.getDisplayName() != null && item.getDisplayName().equalsIgnoreCase(targetId))) {
                    obj = objectInstances.get(item.getId());
                    if (obj != null) break;
                }
            }
        }
        if (obj == null) {
            LOG.warn("[WorldStateMutator] use: object '{}' not found for {}", targetId, agent.getFullName());
            return null;
        }

        Map<String, Object> props = obj.getProperties() != null ? obj.getProperties() : new HashMap<>();

        // Production machine (e.g. coffee machine) → create item in inventory
        if (Boolean.TRUE.equals(props.get("usable")) && props.containsKey("produces_item")) {
            return applyUseMachine(agent, obj, turnNumber);
        }

        // Consumable item → heal HP, convert to trash
        if (Boolean.TRUE.equals(props.get("consumable"))) {
            return applyUseConsumable(agent, obj);
        }

        LOG.warn("[WorldStateMutator] use: '{}' is neither usable nor consumable", obj.getName());
        return null;
    }

    private String applyUseMachine(Agent agent, WorldObjectInstance machine, int turnNumber) {
        Map<String, Object> props = machine.getProperties();
        String produces = String.valueOf(props.getOrDefault("produces_item", "coffee"));
        int healAmount = props.get("produces_heal_amount") instanceof Number
            ? ((Number) props.get("produces_heal_amount")).intValue() : 30;

        String cupId = "item_" + produces + "_" + Math.abs((agent.getFullName() + turnNumber).hashCode() % 100000);
        WorldObjectInstance cup = new WorldObjectInstance(cupId, "consumable",
            produces.equals("coffee") ? "Coffee" : produces,
            agent.getX(), agent.getY(),
            agent.getLocation() != null ? agent.getLocation().getFullPath() : null);

        Map<String, Object> cupProps = new HashMap<>();
        cupProps.put("carriable", true);
        cupProps.put("consumable", true);
        cupProps.put("heal_amount", healAmount);
        cupProps.put("heldBy", agent.getFullName());
        cupProps.put("description", "A freshly brewed coffee.");
        cup.setProperties(cupProps);
        cup.setHeldBy(agent.getFullName());
        objectInstances.put(cupId, cup);

        // item.id must equal cupId so objectInstances.get(item.getId()) resolves correctly.
        InventoryItem item = new InventoryItem(cupId, cup.getName());
        item.setId(cupId);
        agent.addInventoryItem(item);
        refreshCarriedItems(agent);

        LOG.info("[WorldStateMutator] {} brewed {} ({})", agent.getFullName(), cup.getName(), cupId);
        return "used " + machine.getName() + " and received a " + cup.getName();
    }

    private String applyUseConsumable(Agent agent, WorldObjectInstance obj) {
        Map<String, Object> props = obj.getProperties() != null ? obj.getProperties() : new HashMap<>();
        int healAmount = props.get("heal_amount") instanceof Number
            ? ((Number) props.get("heal_amount")).intValue() : 20;
        String itemName = obj.getName();

        agent.recoverHealth(healAmount);
        agent.applyStressChange(-0.1); // eating is calming

        // Remove from typed inventory
        agent.removeInventoryItem(obj.getInstanceId());

        // Convert object to trash in place (don't remove from world)
        obj.setTypeId("trash_item");
        obj.setName(itemName + " wrapper");
        if (obj.getProperties() == null) obj.setProperties(new HashMap<>());
        obj.getProperties().put("consumable", false);
        obj.getProperties().put("is_trash", true);
        obj.getProperties().put("carriable", true);
        obj.getProperties().remove("heal_amount");
        obj.getProperties().remove("heldBy");
        obj.setHeldBy(null);
        obj.setX(snapToTile(agent.getX()));
        obj.setY(snapToTile(agent.getY()));
        obj.setLocation(agent.getLocation() != null ? agent.getLocation().getFullPath() : null);

        refreshCarriedItems(agent);

        LOG.info("[WorldStateMutator] {} consumed '{}' (+{} HP, health={})",
            agent.getFullName(), itemName, healAmount, agent.getHealth());
        return "consumed " + itemName + " and recovered " + healAmount + " HP";
    }

    // ── Inventory helpers ─────────────────────────────────────────────────────

    /** Sync agent.carriedItemNames so LLM prompts reflect current inventory. */
    private void refreshCarriedItems(Agent actor) {
        if (actor == null) return;
        List<String> names = actor.getInventory().values().stream()
            .map(item -> item.getDisplayName() != null ? item.getDisplayName() : item.getId())
            .collect(Collectors.toList());
        actor.setCarriedItemNames(names);
    }

    private double snapToTile(double coord) {
        return Math.round(coord / TILE_SIZE) * TILE_SIZE;
    }
}
