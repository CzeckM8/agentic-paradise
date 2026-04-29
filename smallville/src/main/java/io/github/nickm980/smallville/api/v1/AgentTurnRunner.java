package io.github.nickm980.smallville.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.llm.LLM;
import io.github.nickm980.smallville.memory.Observation;
import io.github.nickm980.smallville.llm.tools.ToolCall;
import io.github.nickm980.smallville.llm.tools.ToolCallResponse;
import io.github.nickm980.smallville.llm.tools.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Runs the agentic tool loop for a single agent's turn.
 *
 * The LLM is given a situation briefing and a set of tools. It calls tools
 * iteratively to reason about the world, then calls commit_action to execute
 * an action and end its turn. The loop is bounded by maxIterations and an
 * optional wall-clock deadline.
 *
 * Thread safety: instances are single-use and short-lived. Called from the
 * async cognition thread; does NOT mutate world state directly.
 *
 * TODO: migrate situation briefing template to prompts.yaml
 */
public class AgentTurnRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AgentTurnRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<ToolDefinition> AGENT_TOOLS = AgentToolSchema.all();

    private final LLM llm;
    private final AgentToolExecutor executor;
    private final AgentTurnContext ctx;

    public AgentTurnRunner(LLM llm, AgentToolExecutor executor, AgentTurnContext ctx) {
        this.llm = llm;
        this.executor = executor;
        this.ctx = ctx;
    }

    /**
     * Run the tool loop and return a TurnResult.
     * Never throws — falls back to iterationFallback on any error.
     */
    public TurnResult run() {
        Agent agent = ctx.agent;
        List<ToolCall> allCalls = new ArrayList<>();
        long deadline = ctx.deadlineMs > 0
            ? System.currentTimeMillis() + ctx.deadlineMs
            : Long.MAX_VALUE;

        // Build the initial conversation: system briefing + first user message
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(systemMessage(buildSituationBriefing()));
        history.add(userMessage("It is your turn. Use tools to reason about your situation, then call commit_action to act."));

        LOG.info("[TurnRunner] Starting tool loop for {} (turn={}, maxIter={})",
            agent.getFullName(), ctx.turnNumber, ctx.maxIterations);

        for (int iter = 0; iter < ctx.maxIterations; iter++) {
            if (System.currentTimeMillis() >= deadline) {
                LOG.warn("[TurnRunner] {} — deadline exceeded at iter {}/{}, falling back to wait",
                    agent.getFullName(), iter, ctx.maxIterations);
                return TurnResult.iterationFallback(allCalls);
            }

            ToolCallResponse response;
            try {
                response = llm.sendChatWithTools(history, AGENT_TOOLS, 0.7);
            } catch (Exception e) {
                LOG.error("[TurnRunner] LLM call failed for {} at iter {}: {}",
                    agent.getFullName(), iter, e.getMessage());
                return TurnResult.iterationFallback(allCalls);
            }

            if (response == null) {
                LOG.warn("[TurnRunner] LLM returned null for {} at iter {} — falling back",
                    agent.getFullName(), iter);
                return TurnResult.iterationFallback(allCalls);
            }

            if (!response.isToolMode()) {
                // Model responded in prose — record as observation and end turn
                LOG.info("[TurnRunner] {} responded in prose at iter {} — ending turn",
                    agent.getFullName(), iter);
                if (response.textContent() != null && !response.textContent().isBlank()) {
                    String truncated = response.textContent().substring(0, Math.min(200, response.textContent().length()));
                    agent.getMemoryStream().add(new Observation("I reflected: " + truncated));
                }
                return TurnResult.iterationFallback(allCalls);
            }

            // Add the assistant's tool_calls message to history for next iteration
            history.add(assistantToolCallsMessage(response.toolCalls()));

            // Process each tool call
            boolean turnDone = false;
            TurnResult commitResult = null;

            for (ToolCall call : response.toolCalls()) {
                allCalls.add(call);
                LOG.info("[TurnRunner] {} iter={} → {}({})",
                    agent.getFullName(), iter, call.name(),
                    call.argsJson() != null && call.argsJson().length() > 80
                        ? call.argsJson().substring(0, 80) + "..." : call.argsJson());

                String toolResult;
                if ("commit_action".equals(call.name())) {
                    commitResult = handleCommitAction(call, allCalls);
                    toolResult = commitResult.committed
                        ? "Action permitted and queued for execution."
                        : "Action rejected: " + commitResult.rejectExplanation;
                    history.add(toolResultMessage(call.id(), call.name(), toolResult));
                    turnDone = true;
                    break;
                } else {
                    toolResult = dispatchReadOnlyTool(call);
                    history.add(toolResultMessage(call.id(), call.name(), toolResult));
                }
            }

            if (turnDone) {
                return commitResult;
            }
        }

        LOG.warn("[TurnRunner] {} exhausted {} iterations without commit_action — falling back",
            agent.getFullName(), ctx.maxIterations);
        return TurnResult.iterationFallback(allCalls);
    }

    // ── Tool dispatch ─────────────────────────────────────────────────────────

    private String dispatchReadOnlyTool(ToolCall call) {
        try {
            JsonNode args = call.argsJson() != null ? MAPPER.readTree(call.argsJson()) : MAPPER.createObjectNode();
            switch (call.name()) {
                case "query_memory":
                    return executor.executeQueryMemory(ctx.agent, args.path("query").asText(""));
                case "scan_nearby":
                    Integer radius = args.has("radius_tiles") ? args.get("radius_tiles").asInt() : null;
                    return executor.executeScanNearby(ctx.agent, radius);
                case "check_inventory":
                    return executor.executeCheckInventory(ctx.agent);
                case "read_beliefs":
                    String target = args.has("target_name") ? args.get("target_name").asText(null) : null;
                    return executor.executeReadBeliefs(ctx.agent, target);
                case "read_epistemic_events":
                    Integer limit = args.has("limit") ? args.get("limit").asInt() : null;
                    return executor.executeReadEpistemicEvents(ctx.agent, limit);
                case "query_known_locations":
                    String locQuery = args.path("query").asText("");
                    return executor.executeQueryKnownLocations(ctx.agent, locQuery);
                case "update_plan":
                    String activity = args.path("activity").asText("");
                    String loc = args.has("target_location") ? args.get("target_location").asText(null) : null;
                    return executor.executeUpdatePlan(ctx.agent, activity, loc);
                default:
                    return "Unknown tool: " + call.name();
            }
        } catch (Exception e) {
            LOG.error("[TurnRunner] Tool dispatch error for {}: {}", call.name(), e.getMessage());
            return "Tool error: " + e.getMessage();
        }
    }

    private TurnResult handleCommitAction(ToolCall call, List<ToolCall> allCalls) {
        try {
            JsonNode args = call.argsJson() != null ? MAPPER.readTree(call.argsJson()) : MAPPER.createObjectNode();
            String verb = args.path("verb").asText("");
            String targetId = args.path("target_id").asText("");
            String targetType = args.path("target_type").asText("");
            String payload = args.has("payload") ? args.get("payload").asText(null) : null;
            String speech = args.has("speech") ? args.get("speech").asText(null) : null;
            if (speech != null && speech.isBlank()) speech = null;

            AgentToolExecutor.CommitValidationResult validation =
                executor.executeCommitValidation(ctx.agent, verb, targetId, targetType, payload, ctx.turnNumber);

            if (validation.permitted) {
                return TurnResult.committed(verb, targetId, targetType, payload, speech, new ArrayList<>(allCalls));
            } else {
                // Write a belief correction so the agent learns from this rejection
                ctx.agent.getEpistemicMemory().ingestBeliefCorrection(
                    ctx.turnNumber, verb, targetId,
                    io.github.nickm980.smallville.entities.RejectReason.AFFORDANCE_DENIED,
                    "I believed this action was possible",
                    validation.rejectMessage);
                return TurnResult.rejected(verb, targetId, validation.rejectMessage, new ArrayList<>(allCalls));
            }
        } catch (Exception e) {
            LOG.error("[TurnRunner] commit_action parse error for {}: {}", ctx.agent.getFullName(), e.getMessage());
            return TurnResult.iterationFallback(allCalls);
        }
    }

    // ── Situation briefing ────────────────────────────────────────────────────
    // TODO: migrate this template to prompts.yaml

    private String buildSituationBriefing() {
        Agent agent = ctx.agent;
        StringBuilder sb = new StringBuilder();

        // Priority combat directive — injected first so it anchors the LLM's decision
        if (ctx.pendingIntent != null && !ctx.pendingIntent.isBlank()) {
            sb.append(ctx.pendingIntent).append("\n\n");
        }

        sb.append("You are ").append(agent.getFullName()).append(".\n");
        sb.append("Traits: ").append(agent.getTraits() != null ? agent.getTraits() : "unknown").append("\n");
        sb.append("Current time: ").append(
            ctx.simulationTime.format(DateTimeFormatter.ofPattern("EEEE, h:mm a"))).append("\n");
        sb.append("Your location: ").append(
            agent.getLocation() != null ? agent.getLocation().getFullPath() : "unknown").append("\n");
        sb.append("Current activity: ").append(agent.getCurrentActivity()).append("\n\n");

        // Legal actions (abbreviated — strip " [...]" annotation to save tokens)
        if (ctx.legalActions != null && !ctx.legalActions.isEmpty()) {
            sb.append("Actions available to you right now:\n");
            for (String a : ctx.legalActions) {
                int bracket = a.indexOf(" [");
                sb.append("  - ").append(bracket >= 0 ? a.substring(0, bracket) : a).append("\n");
            }
            sb.append("\n");
        }

        // Nearby entities (compact one-liner)
        if (ctx.nearbyEntities != null && !ctx.nearbyEntities.isEmpty()) {
            sb.append("Nearby:");
            for (int i = 0; i < ctx.nearbyEntities.size(); i++) {
                Map<String, Object> e = ctx.nearbyEntities.get(i);
                if (i > 0) sb.append(",");
                sb.append(" ").append(e.get("id")).append(" [").append(e.get("type")).append("]");
                if (e.containsKey("distance_tiles")) sb.append(" ").append(e.get("distance_tiles")).append("t");
                if (e.containsKey("current_activity")) sb.append(" — ").append(e.get("current_activity"));
            }
            sb.append("\n\n");
        }

        // Belief summary omitted from inline prompt — use read_beliefs tool if needed

        // Latest belief correction (if any)
        var correction = agent.getEpistemicMemory().latestCorrection();
        if (correction != null) {
            sb.append("Recent correction: ").append(correction.toReflectionPrompt()).append("\n\n");
        }

        sb.append("Use the available tools to reason about your situation, then call commit_action to act.\n");
        sb.append("Rules for commit_action:\n");
        sb.append("  - verb must be one of: carry, place_object, write, open, close, unlock, lock, use, speak, punch, kick, tackle, attack, give, wait, observe\n");
        sb.append("  - For combat: use punch (close range), kick (close range, more damage), tackle (2+ tiles away), or attack (generic)\n");
        sb.append("  - target_id must match an exact entity id from the Nearby list or your inventory (never a blank string)\n");
        sb.append("  - target_type must be 'agent', 'player', or 'object' matching the entity\n");
        sb.append("  - To do nothing this turn: verb=wait, target_id=self, target_type=agent\n");
        sb.append("  - speech (optional, free action): only include if you are genuinely saying words OUT LOUD to a nearby person, e.g. speech=\"Stay back!\" while punching. Do NOT use to describe your own activity — leave it blank if you are just doing something silently.\n");
        sb.append("You must call commit_action exactly once — it ends your turn.");

        return sb.toString();
    }

    // ── Message builders ──────────────────────────────────────────────────────

    private static Map<String, Object> systemMessage(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "system");
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> userMessage(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> assistantToolCallsMessage(List<ToolCall> calls) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", null);
        List<Map<String, Object>> toolCallsJson = new ArrayList<>();
        for (ToolCall c : calls) {
            Map<String, Object> func = new LinkedHashMap<>();
            func.put("name", c.name());
            func.put("arguments", c.argsJson() != null ? c.argsJson() : "{}");
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("id", c.id() != null ? c.id() : "call_" + UUID.randomUUID().toString().substring(0, 8));
            tc.put("type", "function");
            tc.put("function", func);
            toolCallsJson.add(tc);
        }
        m.put("tool_calls", toolCallsJson);
        return m;
    }

    private static Map<String, Object> toolResultMessage(String callId, String toolName, String result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", callId != null ? callId : "call_unknown");
        m.put("name", toolName);
        m.put("content", result != null ? result : "");
        return m;
    }
}
