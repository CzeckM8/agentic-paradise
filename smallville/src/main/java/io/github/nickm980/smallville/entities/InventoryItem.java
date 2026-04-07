package io.github.nickm980.smallville.entities;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A specific physical item in an entity's inventory.
 *
 * id          — unique instance UUID for this exact item.
 * typeId      — the kind of item ("iron_key", "pencil", "bread").
 * grants      — affordances this item enables when held. Examples:
 *                 "writing_utensil"        → allows write on writable objects
 *                 "cutting_tool"           → allows cut/carve verbs
 *                 "opens:<instanceId>"     → allows open on ONE specific lock instance
 *                 "reads:<instanceId>"     → allows open a sealed letter instance
 *               Multiple "opens:" grants = master key behaviour.
 * consumable  — if true the item is removed from inventory on successful use.
 * durability  — remaining uses; -1 means infinite.
 *
 * ActionResolver checks grants when a verb requires a specific affordance.
 * Two items of the same typeId carry DIFFERENT instance ids and potentially
 * DIFFERENT grants (e.g. two iron keys that open different locks).
 */
public class InventoryItem {

    private String id;
    private String typeId;
    private String displayName;
    private Set<String> grants = new HashSet<>();
    private boolean consumable = false;
    private int durability = -1;

    public InventoryItem() {
        this.id = UUID.randomUUID().toString();
    }

    public InventoryItem(String typeId, String displayName) {
        this.id = UUID.randomUUID().toString();
        this.typeId = typeId;
        this.displayName = displayName;
    }

    public InventoryItem(String typeId, String displayName, Set<String> grants) {
        this.id = UUID.randomUUID().toString();
        this.typeId = typeId;
        this.displayName = displayName;
        this.grants = new HashSet<>(grants);
    }

    /** Constructor for stable id (e.g. load from save). */
    public InventoryItem(String id, String typeId, String displayName, Set<String> grants,
                         boolean consumable, int durability) {
        this.id = id;
        this.typeId = typeId;
        this.displayName = displayName;
        this.grants = new HashSet<>(grants);
        this.consumable = consumable;
        this.durability = durability;
    }

    // ── Grant helpers ────────────────────────────────────────────────────────

    /** True if this item grants the exact affordance string. */
    public boolean hasGrant(String grant) {
        return grants.contains(grant);
    }

    /**
     * True if this item can open the given object instance.
     * Checks for "opens:<instanceId>" in grants.
     */
    public boolean opensInstance(String instanceId) {
        return grants.contains("opens:" + instanceId);
    }

    /**
     * Consume one use. Returns true if the item should be removed from inventory
     * (consumable and durability reached zero).
     */
    public boolean consumeUse() {
        if (!consumable) return false;
        if (durability == -1) return true; // infinite consumable: remove on use
        durability--;
        return durability <= 0;
    }

    public void addGrant(String grant) {
        grants.add(grant);
    }

    public void removeGrant(String grant) {
        grants.remove(grant);
    }

    // ── Getters / setters ────────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTypeId() { return typeId; }
    public void setTypeId(String typeId) { this.typeId = typeId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public Set<String> getGrants() { return Collections.unmodifiableSet(grants); }
    public void setGrants(Set<String> grants) { this.grants = new HashSet<>(grants); }

    public boolean isConsumable() { return consumable; }
    public void setConsumable(boolean consumable) { this.consumable = consumable; }

    public int getDurability() { return durability; }
    public void setDurability(int durability) { this.durability = durability; }
}
