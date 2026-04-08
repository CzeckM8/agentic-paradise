package io.github.nickm980.smallville.entities;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A specific physical object instance in the world.
 *
 * instanceId — unique UUID identifying this exact object (e.g. "cellar_door_3f9a").
 *              Two doors of the same typeId are different instances.
 * typeId     — the kind of object ("iron_door", "pencil", "iron_key").
 *              Determines base affordances from the object type registry.
 *
 * Key-lock binding: a key that opens this instance carries
 * "opens:<instanceId>" in its InventoryItem.grants set.
 */
public class WorldObjectInstance {

    private String instanceId;
    private String typeId;
    private String name;
    private double x;
    private double y;
    private String location;

    /**
     * Type-level affordance properties inherited from objectTypeDefinitions
     * (e.g. "interactive": true, "writable": true). Merged with state at
     * resolve time; do not mutate directly — copy from type registry on creation.
     */
    private Map<String, Object> properties = new HashMap<>();

    /**
     * Instance-specific mutable state.
     * Examples: "isLocked": true, "isOpen": false, "hasText": "meet at dawn",
     *           "occupiedBy": "Alex", "durability": 3
     */
    private Map<String, Object> state = new HashMap<>();

    /**
     * Name of the agent or player currently carrying this object, or null
     * if it is resting in the world at (x, y).
     */
    private String heldBy;

    public WorldObjectInstance() {
        this.instanceId = UUID.randomUUID().toString();
    }

    public WorldObjectInstance(String typeId, String name, double x, double y, String location) {
        this.instanceId = UUID.randomUUID().toString();
        this.typeId = typeId;
        this.name = name;
        this.x = x;
        this.y = y;
        this.location = location;
    }

    /** Constructor for cases where instanceId must be stable (e.g. load from save). */
    public WorldObjectInstance(String instanceId, String typeId, String name,
                               double x, double y, String location) {
        this.instanceId = instanceId;
        this.typeId = typeId;
        this.name = name;
        this.x = x;
        this.y = y;
        this.location = location;
    }

    // ── Convenience state helpers ────────────────────────────────────────────

    public boolean isLocked() {
        Object v = state.get("isLocked");
        return Boolean.TRUE.equals(v);
    }

    public boolean isOpen() {
        Object v = state.get("isOpen");
        return Boolean.TRUE.equals(v);
    }

    public boolean isCarried() {
        return heldBy != null && !heldBy.isBlank();
    }

    public void setState(String key, Object value) {
        state.put(key, value);
    }

    public Object getState(String key) {
        return state.get(key);
    }

    // ── Serialisation ────────────────────────────────────────────────────────

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instanceId);
        result.put("id", instanceId);       // backward-compat for Godot client
        result.put("typeId", typeId);
        result.put("type", typeId);         // backward-compat for Godot client
        result.put("name", name);
        result.put("x", x);
        result.put("y", y);
        result.put("location", location);
        result.put("properties", properties);
        result.put("state", state);
        if (heldBy != null) result.put("heldBy", heldBy);
        return result;
    }

    // ── Getters / setters ────────────────────────────────────────────────────

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    /** Backward-compat alias for {@link #getInstanceId()}. Prefer getInstanceId() in new code. */
    public String getId() { return instanceId; }
    public void setId(String id) { this.instanceId = id; }

    public String getTypeId() { return typeId; }
    public void setTypeId(String typeId) { this.typeId = typeId; }

    /** Backward-compat alias for {@link #getTypeId()}. Prefer getTypeId() in new code. */
    public String getType() { return typeId; }
    public void setType(String type) { this.typeId = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public Map<String, Object> getProperties() { return properties; }
    public void setProperties(Map<String, Object> properties) { this.properties = properties; }

    public Map<String, Object> getStateMap() { return state; }
    public void setStateMap(Map<String, Object> state) { this.state = state; }

    public String getHeldBy() { return heldBy; }
    public void setHeldBy(String heldBy) { this.heldBy = heldBy; }
}
