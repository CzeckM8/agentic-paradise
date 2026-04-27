package io.github.nickm980.smallville.api.v1;

import io.github.nickm980.smallville.llm.tools.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * Static factory for the 7 tools available to an agent during its turn loop.
 *
 * These are the only tools the LLM is permitted to call. commit_action is the
 * only tool that may mutate world state; all others are read-only.
 */
public class AgentToolSchema {

    private AgentToolSchema() {}

    public static List<ToolDefinition> all() {
        return List.of(
            queryMemory(),
            scanNearby(),
            checkInventory(),
            readBeliefs(),
            readEpistemicEvents(),
            queryKnownLocations(),
            updatePlan(),
            commitAction()
        );
    }

    private static ToolDefinition queryMemory() {
        return new ToolDefinition(
            "query_memory",
            "Search this agent's memory stream for observations, plans, and reflections relevant to the query. Returns up to 5 matching memories.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string", "description", "What to search for in memory")
                ),
                "required", List.of("query")
            )
        );
    }

    private static ToolDefinition scanNearby() {
        return new ToolDefinition(
            "scan_nearby",
            "Return a list of nearby entities (agents, players, objects) within radius_tiles. Each entry includes id, type, distance_tiles, and current_activity.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "radius_tiles", Map.of("type", "integer", "description", "Search radius in tiles (default 10, max 20)")
                ),
                "required", List.of()
            )
        );
    }

    private static ToolDefinition checkInventory() {
        return new ToolDefinition(
            "check_inventory",
            "Return the items currently in this agent's inventory.",
            Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
            )
        );
    }

    private static ToolDefinition readBeliefs() {
        return new ToolDefinition(
            "read_beliefs",
            "Return this agent's beliefs about other agents. If target_name is provided, returns the belief for that specific agent; otherwise returns a summary of all known agents.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "target_name", Map.of("type", "string", "description", "Name of the agent to read beliefs about (optional)")
                ),
                "required", List.of()
            )
        );
    }

    private static ToolDefinition readEpistemicEvents() {
        return new ToolDefinition(
            "read_epistemic_events",
            "Return the most recent events this agent has directly witnessed or heard about.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "limit", Map.of("type", "integer", "description", "Max events to return (default 5)")
                ),
                "required", List.of()
            )
        );
    }

    private static ToolDefinition queryKnownLocations() {
        return new ToolDefinition(
            "query_known_locations",
            "Search this agent's mental map for the last known position of objects, agents, or players. Returns tile coordinates, location name, and how many turns ago the position was observed. Knowledge may be stale — use scan_nearby to confirm current position.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string", "description", "Name or type to search for (e.g. 'key', 'door', 'Player', 'agent')")
                ),
                "required", List.of("query")
            )
        );
    }

    private static ToolDefinition updatePlan() {
        return new ToolDefinition(
            "update_plan",
            "Update this agent's declared current activity and optional destination. Does NOT execute a world action — only changes the agent's stated intent visible to others.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "activity", Map.of("type", "string", "description", "New activity description"),
                    "target_location", Map.of("type", "string", "description", "Destination location name (optional)")
                ),
                "required", List.of("activity")
            )
        );
    }

    private static ToolDefinition commitAction() {
        return new ToolDefinition(
            "commit_action",
            "Execute a world action. This is the ONLY tool that can change world state. It is validated by the action resolver — it may be rejected if out of range, missing affordance, or missing item. Calling this ends the turn regardless of result.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "verb", Map.of("type", "string", "description", "Action verb (carry, open, close, write, speak, give, observe, sit, place_object, unlock, lock, wait, punch, kick, tackle, attack)"),
                    "target_id", Map.of("type", "string", "description", "ID of the target object or agent name"),
                    "target_type", Map.of("type", "string", "description", "Type: 'agent', 'player', or 'object'"),
                    "payload", Map.of("type", "string", "description", "Optional: speech text, item name, or written content")
                ),
                "required", List.of("verb", "target_id", "target_type")
            )
        );
    }
}
