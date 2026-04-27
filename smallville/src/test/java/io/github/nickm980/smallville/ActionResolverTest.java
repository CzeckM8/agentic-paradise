package io.github.nickm980.smallville;

import io.github.nickm980.smallville.api.v1.ActionResolver;
import io.github.nickm980.smallville.entities.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ActionResolver.
 *
 * Covers:
 *  - legalActionsFor() format (enriched with context in brackets)
 *  - resolve() permit/reject paths for each rejection reason
 *  - Edge cases: null inputs, empty inventories, boundary distances
 */
class ActionResolverTest {

    private Map<String, Map<String, InventoryItem>> inventory;
    private Map<String, WorldObjectInstance> objects;
    private Map<String, Map<String, Object>> typeDefs;

    @BeforeEach
    void setUp() {
        inventory = new HashMap<>();
        objects = new HashMap<>();
        typeDefs = new HashMap<>();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Place an object at (32, 32) — 1 tile from actor at (32, 32). */
    private WorldObjectInstance objectAt(String id, String name, Map<String, Object> props) {
        WorldObjectInstance obj = new WorldObjectInstance(id, id + "_type", name, 32, 32, "Room");
        if (props != null) obj.setProperties(new HashMap<>(props));
        objects.put(id, obj);
        return obj;
    }

    /** Place an object at a specific pixel position. */
    private WorldObjectInstance objectAt(String id, String name, double x, double y, Map<String, Object> props) {
        WorldObjectInstance obj = new WorldObjectInstance(id, id + "_type", name, x, y, "Room");
        if (props != null) obj.setProperties(new HashMap<>(props));
        objects.put(id, obj);
        return obj;
    }

    private WorldAction carryAction(String actorId, String targetId, double ax, double ay) {
        return WorldAction.fromPlayerAction(actorId, "carry", targetId,
            WorldAction.TargetType.OBJECT, null, null, ax, ay, 1);
    }

    // ── legalActionsFor: format ───────────────────────────────────────────────

    @Test
    void legalActionsFor_alwaysIncludesWaitWithContext() {
        ActionResolver resolver = new ActionResolver(inventory, objects, typeDefs);
        List<String> actions = resolver.legalActionsFor("Alex", 0, 0, List.of());

        assertTrue(actions.stream().anyMatch(a -> a.startsWith("wait [") && a.contains("always available")),
            "wait must include context suffix. Got: " + actions);
    }

    @Test
    void legalActionsFor_carriableObjectHasContextWithDistance() {
        objectAt("key_01", "KeyCard", Map.of("carriable", true));

        ActionResolver resolver = new ActionResolver(inventory, objects, typeDefs);
        List<String> actions = resolver.legalActionsFor("Alex", 32, 32, List.of());

        assertTrue(actions.stream().anyMatch(a -> a.startsWith("carry(KeyCard) [")
                && (a.contains("adjacent") || a.contains("nearby") || a.contains("close by"))),
            "carry action must include distance context. Got: " + actions);
    }

    @Test
    void legalActionsFor_excludesObjectBeyondInteractionRange() {
        // 400 tiles away — well beyond 3-tile max
        objectAt("far_01", "FarBox", 400 * 32.0, 400 * 32.0, Map.of("carriable", true));

        ActionResolver resolver = new ActionResolver(inventory, objects, typeDefs);
        List<String> actions = resolver.legalActionsFor("Alex", 0, 0, List.of());

        assertFalse(actions.stream().anyMatch(a -> a.contains("FarBox")),
            "Out-of-range object must not appear in legal actions. Got: " + actions);
    }

    @Test
    void legalActionsFor_includesNearbyAgentAsSpeakTarget() {
        ActionResolver resolver = new ActionResolver(inventory, objects, typeDefs);
        List<String> actions = resolver.legalActionsFor("Alex", 0, 0, List.of("John", "Maria"));

        assertTrue(actions.stream().anyMatch(a -> a.startsWith("speak(John)")));
        assertTrue(actions.stream().anyMatch(a -> a.startsWith("speak(Maria)")));
    }

    @Test
    void legalActionsFor_writeRequiresWritingUtensilGrant() {
        objectAt("board_01", "Whiteboard", Map.of("writable", true));

        // Without tool
        assertFalse(new ActionResolver(inventory, objects, typeDefs)
            .legalActionsFor("Alex", 32, 32, List.of())
            .stream().anyMatch(a -> a.startsWith("write(")),
            "write must not appear without writing_utensil grant");

        // Grant the tool
        InventoryItem pencil = new InventoryItem("pencil", "Pencil", Set.of("writing_utensil"));
        inventory.put("Alex", Map.of(pencil.getId(), pencil));

        assertTrue(new ActionResolver(inventory, objects, typeDefs)
            .legalActionsFor("Alex", 32, 32, List.of())
            .stream().anyMatch(a -> a.startsWith("write(Whiteboard) [")),
            "write must appear when writing_utensil is held");
    }

    @Test
    void legalActionsFor_openLockedDoorOnlyWithMatchingKey() {
        WorldObjectInstance door = objectAt("door_01", "StorageDoor", Map.of("can_open_close", true));
        door.setState("isLocked", true);

        // Without key — door should not appear as openable
        assertFalse(new ActionResolver(inventory, objects, typeDefs)
            .legalActionsFor("Alex", 32, 32, List.of())
            .stream().anyMatch(a -> a.contains("StorageDoor")));

        // Give actor a matching key
        InventoryItem key = new InventoryItem("k1", "StoreKey", Set.of("opens:door_01"));
        inventory.put("Alex", Map.of(key.getId(), key));

        assertTrue(new ActionResolver(inventory, objects, typeDefs)
            .legalActionsFor("Alex", 32, 32, List.of())
            .stream().anyMatch(a -> a.startsWith("open(StorageDoor)")));
    }

    @Test
    void legalActionsFor_alreadyCarriedObjectNotOfferedAsCarriable() {
        WorldObjectInstance obj = objectAt("gem_01", "Gem", Map.of("carriable", true));
        obj.setHeldBy("Maria"); // already carried by someone else

        List<String> actions = new ActionResolver(inventory, objects, typeDefs)
            .legalActionsFor("Alex", 32, 32, List.of());

        assertFalse(actions.stream().anyMatch(a -> a.startsWith("carry(Gem)")),
            "Already-carried object must not appear as carry target");
    }

    // ── resolve: permit paths ─────────────────────────────────────────────────

    @Test
    void resolve_permitsCarryOnCarriableObject() {
        objectAt("key_01", "KeyCard", Map.of("carriable", true));

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(carryAction("Alex", "key_01", 32, 32));

        assertTrue(r.permitted);
        assertNull(r.rejectReason);
    }

    @Test
    void resolve_permitsOpenOnClosedUnlockedDoor() {
        WorldObjectInstance door = objectAt("door_01", "Door", Map.of("can_open_close", true));
        door.setState("isOpen", false);
        door.setState("isLocked", false);

        WorldAction wa = WorldAction.fromPlayerAction("Alex", "open", "door_01",
            WorldAction.TargetType.OBJECT, null, null, 32, 32, 1);

        assertTrue(new ActionResolver(inventory, objects, typeDefs).resolve(wa).permitted);
    }

    @Test
    void resolve_permitsOpenOnLockedDoorWithMatchingKey() {
        WorldObjectInstance door = objectAt("door_01", "Door", Map.of("can_open_close", true));
        door.setState("isOpen", false);
        door.setState("isLocked", true);

        InventoryItem key = new InventoryItem("k1", "StoreKey", Set.of("opens:door_01"));
        inventory.put("Alex", Map.of(key.getId(), key));

        WorldAction wa = WorldAction.fromPlayerAction("Alex", "open", "door_01",
            WorldAction.TargetType.OBJECT, null, null, 32, 32, 1);

        assertTrue(new ActionResolver(inventory, objects, typeDefs).resolve(wa).permitted);
    }

    // ── resolve: reject paths ─────────────────────────────────────────────────

    @Test
    void resolve_rejectsTargetNotFound() {
        WorldAction wa = WorldAction.fromPlayerAction("Alex", "carry", "nonexistent",
            WorldAction.TargetType.OBJECT, null, null, 32, 32, 1);

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs).resolve(wa);

        assertFalse(r.permitted);
        assertEquals(RejectReason.TARGET_NOT_FOUND, r.rejectReason);
        assertNotNull(r.explanation);
    }

    @Test
    void resolve_rejectsOutOfRange() {
        // Object is 400 tiles away (> 3 tile max)
        objectAt("box_01", "Box", 400 * 32.0, 400 * 32.0, Map.of("carriable", true));

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(carryAction("Alex", "box_01", 0, 0));

        assertFalse(r.permitted);
        assertEquals(RejectReason.OUT_OF_RANGE, r.rejectReason);
        assertTrue(r.explanation.contains("tiles"), "Explanation should mention distance. Got: " + r.explanation);
    }

    @Test
    void resolve_rejectsCarryOnNonCarriableObject() {
        objectAt("rock_01", "Rock", Map.of(/* no carriable */));

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(carryAction("Alex", "rock_01", 32, 32));

        assertFalse(r.permitted);
        assertEquals(RejectReason.AFFORDANCE_DENIED, r.rejectReason);
    }

    @Test
    void resolve_rejectsCarryOnAlreadyCarriedObject() {
        WorldObjectInstance obj = objectAt("gem_01", "Gem", Map.of("carriable", true));
        obj.setHeldBy("Maria");

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(carryAction("Alex", "gem_01", 32, 32));

        assertFalse(r.permitted);
        assertEquals(RejectReason.STATE_INCOMPATIBLE, r.rejectReason);
        assertTrue(r.explanation.contains("Maria"), "Explanation should name who holds it. Got: " + r.explanation);
    }

    @Test
    void resolve_rejectsOpenOnAlreadyOpenDoor() {
        WorldObjectInstance door = objectAt("door_01", "Door", Map.of("can_open_close", true));
        door.setState("isOpen", true);

        WorldAction wa = WorldAction.fromPlayerAction("Alex", "open", "door_01",
            WorldAction.TargetType.OBJECT, null, null, 32, 32, 1);

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs).resolve(wa);
        assertFalse(r.permitted);
        assertEquals(RejectReason.STATE_INCOMPATIBLE, r.rejectReason);
    }

    @Test
    void resolve_rejectsCloseOnAlreadyClosedDoor() {
        WorldObjectInstance door = objectAt("door_01", "Door", Map.of("can_open_close", true));
        door.setState("isOpen", false);

        WorldAction wa = WorldAction.fromPlayerAction("Alex", "close", "door_01",
            WorldAction.TargetType.OBJECT, null, null, 32, 32, 1);

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs).resolve(wa);
        assertFalse(r.permitted);
        assertEquals(RejectReason.STATE_INCOMPATIBLE, r.rejectReason);
    }

    @Test
    void resolve_rejectsWriteWithoutWritingUtensil() {
        objectAt("board_01", "Whiteboard", Map.of("writable", true));

        WorldAction wa = WorldAction.fromPlayerAction("Alex", "write", "board_01",
            WorldAction.TargetType.OBJECT, null, null, 32, 32, 1);

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs).resolve(wa);
        assertFalse(r.permitted);
        assertEquals(RejectReason.MISSING_GRANT, r.rejectReason);
    }

    @Test
    void resolve_permitsWriteWhenWritingUtensilHeld() {
        objectAt("board_01", "Whiteboard", Map.of("writable", true));
        InventoryItem pencil = new InventoryItem("pencil", "Pencil", Set.of("writing_utensil"));
        inventory.put("Alex", Map.of(pencil.getId(), pencil));

        WorldAction wa = WorldAction.fromPlayerAction("Alex", "write", "board_01",
            WorldAction.TargetType.OBJECT, null, null, 32, 32, 1);

        assertTrue(new ActionResolver(inventory, objects, typeDefs).resolve(wa).permitted);
    }

    @Test
    void resolve_rejectsMissingKeyForLockedDoor() {
        WorldObjectInstance door = objectAt("door_01", "Door", Map.of("can_open_close", true));
        door.setState("isOpen", false);
        door.setState("isLocked", true);

        WorldAction wa = WorldAction.fromPlayerAction("Alex", "open", "door_01",
            WorldAction.TargetType.OBJECT, null, null, 32, 32, 1);

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs).resolve(wa);
        assertFalse(r.permitted);
        assertEquals(RejectReason.MISSING_KEY, r.rejectReason);
    }

    @Test
    void resolve_rejectsOpenLockedDoorWithWrongKey() {
        WorldObjectInstance door = objectAt("door_01", "Door", Map.of("can_open_close", true));
        door.setState("isOpen", false);
        door.setState("isLocked", true);

        // Key opens door_02, not door_01
        InventoryItem wrongKey = new InventoryItem("k1", "WrongKey", Set.of("opens:door_02"));
        inventory.put("Alex", Map.of(wrongKey.getId(), wrongKey));

        WorldAction wa = WorldAction.fromPlayerAction("Alex", "open", "door_01",
            WorldAction.TargetType.OBJECT, null, null, 32, 32, 1);

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs).resolve(wa);
        assertFalse(r.permitted);
        assertEquals(RejectReason.MISSING_KEY, r.rejectReason);
    }

    // ── Boundary distance ─────────────────────────────────────────────────────

    @Test
    void resolve_permitsActionAtExactlyThreeTileRange() {
        // 3 tiles = 96 pixels exactly — should be permitted (≤ 3.0)
        objectAt("box_01", "Box", 96 + 32.0, 32.0, Map.of("carriable", true));
        // actor at (32, 32), box at (128, 32) → dx = 96px = 3 tiles exactly
        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(carryAction("Alex", "box_01", 32, 32));
        assertTrue(r.permitted, "Action at exactly 3 tiles must be permitted");
    }

    @Test
    void resolve_rejectsActionJustBeyondThreeTileRange() {
        // 3 tiles + 1 pixel → out of range
        objectAt("box_01", "Box", 96 + 32.0 + 1, 32.0, Map.of("carriable", true));
        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(carryAction("Alex", "box_01", 32, 32));
        assertFalse(r.permitted);
        assertEquals(RejectReason.OUT_OF_RANGE, r.rejectReason);
    }

    // ── Type-level affordances from objectTypeDefinitions ────────────────────

    @Test
    void resolve_inheritsAffordanceFromTypeDefinition() {
        // Object has no instance properties, but its type says carriable
        WorldObjectInstance obj = new WorldObjectInstance("rock_01", "pebble_type", "Pebble", 32, 32, "Room");
        objects.put("rock_01", obj);
        typeDefs.put("pebble_type", Map.of("carriable", true));

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(carryAction("Alex", "rock_01", 32, 32));

        assertTrue(r.permitted, "Type-level carriable affordance must be honoured");
    }

    @Test
    void resolve_instancePropertyOverridesTypeDefinition() {
        // Type says carriable, but instance overrides to false
        WorldObjectInstance obj = new WorldObjectInstance("boulder_01", "pebble_type", "Boulder", 32, 32, "Room");
        obj.getProperties().put("carriable", false);
        objects.put("boulder_01", obj);
        typeDefs.put("pebble_type", Map.of("carriable", true));

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(carryAction("Alex", "boulder_01", 32, 32));

        assertFalse(r.permitted, "Instance-level property must override type-level");
        assertEquals(RejectReason.AFFORDANCE_DENIED, r.rejectReason);
    }

    // ── give verb ─────────────────────────────────────────────────────────────

    private WorldAction giveAction(String actorId, String recipientId, String itemId, double ax, double ay) {
        return WorldAction.fromPlayerAction(actorId, "give", recipientId,
            WorldAction.TargetType.AGENT, itemId, null, ax, ay, 1);
    }

    @Test
    void resolve_permitsGiveWhenActorHoldsItem() {
        InventoryItem pencil = new InventoryItem("pencil", "Pencil", Set.of());
        inventory.put("Alex", Map.of(pencil.getId(), pencil));

        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(giveAction("Alex", "John", pencil.getId(), 32, 32));

        assertTrue(r.permitted, "give must be permitted when actor holds the item");
        assertNull(r.rejectReason);
    }

    @Test
    void resolve_rejectsGiveWhenActorLacksItem() {
        // Alex has no inventory at all
        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(giveAction("Alex", "John", "nonexistent_item", 32, 32));

        assertFalse(r.permitted);
        assertEquals(RejectReason.MISSING_GRANT, r.rejectReason,
            "give without the item must be MISSING_GRANT");
    }

    @Test
    void resolve_rejectsGiveWithBlankItemId() {
        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(giveAction("Alex", "John", "", 32, 32));

        assertFalse(r.permitted);
        assertEquals(RejectReason.AFFORDANCE_DENIED, r.rejectReason,
            "give with blank itemId must be AFFORDANCE_DENIED");
    }

    @Test
    void resolve_rejectsGiveWithNullItemId() {
        ActionResolver.ResolveResult r = new ActionResolver(inventory, objects, typeDefs)
            .resolve(giveAction("Alex", "John", null, 32, 32));

        assertFalse(r.permitted);
        assertEquals(RejectReason.AFFORDANCE_DENIED, r.rejectReason);
    }

    @Test
    void legalActionsFor_includesGiveWhenActorHoldsItemAndNearbyAgentPresent() {
        InventoryItem pencil = new InventoryItem("pencil", "Pencil", Set.of());
        inventory.put("Alex", Map.of(pencil.getId(), pencil));

        List<String> actions = new ActionResolver(inventory, objects, typeDefs)
            .legalActionsFor("Alex", 0, 0, List.of("John"));

        assertTrue(actions.stream().anyMatch(a -> a.startsWith("give(John,Pencil)")),
            "give action must appear when actor holds item and target agent is nearby. Got: " + actions);
    }

    @Test
    void legalActionsFor_noGiveWhenInventoryIsEmpty() {
        // Actor has no items
        List<String> actions = new ActionResolver(inventory, objects, typeDefs)
            .legalActionsFor("Alex", 0, 0, List.of("John"));

        assertFalse(actions.stream().anyMatch(a -> a.startsWith("give(")),
            "give must not appear when actor has no inventory. Got: " + actions);
    }

    @Test
    void legalActionsFor_noGiveWhenNoNearbyAgents() {
        InventoryItem pencil = new InventoryItem("pencil", "Pencil", Set.of());
        inventory.put("Alex", Map.of(pencil.getId(), pencil));

        List<String> actions = new ActionResolver(inventory, objects, typeDefs)
            .legalActionsFor("Alex", 0, 0, List.of()); // no nearby agents

        assertFalse(actions.stream().anyMatch(a -> a.startsWith("give(")),
            "give must not appear when no agents are nearby. Got: " + actions);
    }

    @Test
    void legalActionsFor_multipleItemsProduceMultipleGiveEntries() {
        InventoryItem pencil = new InventoryItem("pencil", "Pencil", Set.of());
        InventoryItem key = new InventoryItem("key_01", "StoreKey", Set.of("opens:door_01"));
        inventory.put("Alex", Map.of(pencil.getId(), pencil, key.getId(), key));

        List<String> actions = new ActionResolver(inventory, objects, typeDefs)
            .legalActionsFor("Alex", 0, 0, List.of("John"));

        long giveCount = actions.stream().filter(a -> a.startsWith("give(John,")).count();
        assertEquals(2, giveCount,
            "One give entry per carried item expected. Got: " + actions);
    }
}
