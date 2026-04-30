package io.github.nickm980.smallville;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.nickm980.smallville.entities.InventoryItem;

public class InventoryItemTest {

    @Test
    public void hasGrant_and_opensInstance_match_exact_grants() {
        InventoryItem item = new InventoryItem("iron-key", "Iron Key", Set.of("opens:front-door", "carry"));

        assertTrue(item.hasGrant("carry"));
        assertFalse(item.hasGrant("opens"));
        assertTrue(item.opensInstance("front-door"));
        assertFalse(item.opensInstance("back-door"));
    }

    @Test
    public void consumeUse_does_not_remove_non_consumable_items() {
        InventoryItem item = new InventoryItem("apple", "Apple");
        item.setConsumable(false);
        item.setDurability(1);

        assertFalse(item.consumeUse());
    }

    @Test
    public void consumeUse_removes_infinite_consumables_immediately() {
        InventoryItem item = new InventoryItem("ticket", "Ticket Stub");
        item.setConsumable(true);
        item.setDurability(-1);

        assertTrue(item.consumeUse());
    }

    @Test
    public void consumeUse_counts_down_finite_consumables() {
        InventoryItem item = new InventoryItem("chalk", "Chalk");
        item.setConsumable(true);
        item.setDurability(2);

        assertFalse(item.consumeUse());
        assertTrue(item.consumeUse());
        assertTrue(item.getDurability() <= 0);
    }

    @Test
    public void setGrants_copies_input_set() {
        java.util.Set<String> grants = new java.util.HashSet<>();
        grants.add("write");
        InventoryItem item = new InventoryItem("pencil", "Pencil");

        item.setGrants(grants);
        grants.add("erase");

        assertTrue(item.hasGrant("write"));
        assertFalse(item.hasGrant("erase"));
    }
}
