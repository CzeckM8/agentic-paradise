package io.github.nickm980.smallville;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.github.nickm980.smallville.analytics.Analytics;
import io.github.nickm980.smallville.api.SmallvilleServer;
import io.github.nickm980.smallville.llm.ChatGPT;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import okhttp3.Response;

public class ServerIntegrationTest {

    private Javalin app;

    @BeforeEach
    public void setUp() {
        ChatGPT llm = Mockito.mock(ChatGPT.class);
        Mockito.when(llm.sendChat(Mockito.any(), Mockito.anyInt())).thenReturn("result");
        app = new SmallvilleServer(new Analytics(), llm, new World()).server();
    }

    @Test
    public void createAgent_flow_is_visible_through_agent_and_state_endpoints() {
        JavalinTest.test(app, (server, client) -> {
            Response locationResponse = client.post("/locations", Map.of(
                "name", "Town Square",
                "type", "street",
                "minX", 0,
                "maxX", 64,
                "minY", 0,
                "maxY", 64
            ));
            assertEquals(200, locationResponse.code());

            Response agentResponse = client.post("/agents", Map.of(
                "name", "Alex",
                "activity", "reading",
                "location", "Town Square",
                "memories", List.of("likes routines", "knows the plaza")
            ));
            assertEquals(200, agentResponse.code());

            Response getAgentResponse = client.get("/agents/Alex");
            JSONObject agentBody = new JSONObject(getAgentResponse.body().string());

            assertEquals(200, getAgentResponse.code());
            assertEquals("Alex", agentBody.getString("name"));
            assertEquals("Town Square", agentBody.getString("location"));
            assertEquals("reading", agentBody.getString("action"));

            Response stateResponse = client.get("/state");
            JSONObject stateBody = new JSONObject(stateResponse.body().string());
            JSONArray agents = stateBody.getJSONArray("agents");
            JSONArray locations = stateBody.getJSONArray("location_states");

            assertEquals(1, agents.length());
            assertEquals("Alex", agents.getJSONObject(0).getString("name"));
            assertTrue(locations.toList().stream().anyMatch(item -> ((Map<?, ?>) item).get("name").equals("Town Square")));
        });
    }

    @Test
    public void updateLocationState_flow_is_visible_through_locations_endpoint() {
        JavalinTest.test(app, (server, client) -> {
            Response createLocationResponse = client.post("/locations", Map.of(
                "name", "Library",
                "type", "building",
                "minX", 0,
                "maxX", 64,
                "minY", 0,
                "maxY", 64
            ));
            assertEquals(200, createLocationResponse.code());

            Response updateStateResponse = client.post("/locations/Library", Map.of("state", "busy"));
            assertEquals(200, updateStateResponse.code());

            Response locationsResponse = client.get("/locations");
            JSONObject locationsBody = new JSONObject(locationsResponse.body().string());
            JSONArray locations = locationsBody.getJSONArray("locations");

            assertEquals(200, locationsResponse.code());
            assertTrue(locations.toList().stream().anyMatch(item ->
                "Library".equals(((Map<?, ?>) item).get("name"))
                    && "busy".equals(((Map<?, ?>) item).get("state"))));
        });
    }

    @Test
    public void createPlayer_flow_returns_player_state_with_spawn_location() {
        JavalinTest.test(app, (server, client) -> {
            client.post("/locations", Map.of(
                "name", "Cafe",
                "type", "shop",
                "minX", 32,
                "maxX", 96,
                "minY", 32,
                "maxY", 96
            )).close();

            Response createPlayerResponse = client.post("/player", Map.of(
                "name", "PlayerOne",
                "location", "Cafe",
                "activity", "waiting",
                "memories", new String[0]
            ));

            assertEquals(200, createPlayerResponse.code());

            Response getPlayerResponse = client.get("/player/PlayerOne");
            JSONObject playerBody = new JSONObject(getPlayerResponse.body().string());

            assertEquals(200, getPlayerResponse.code());
            assertEquals("PlayerOne", playerBody.getString("name"));
            assertEquals("Cafe", playerBody.getString("location"));
            assertEquals("waiting", playerBody.getString("activity"));
            assertTrue(playerBody.getJSONArray("inventory").isEmpty());
        });
    }

    @Test
    public void object_type_and_instance_flow_is_visible_through_object_endpoints() {
        JavalinTest.test(app, (server, client) -> {
            client.post("/locations", Map.of(
                "name", "Workshop",
                "type", "building",
                "minX", 0,
                "maxX", 128,
                "minY", 0,
                "maxY", 128
            )).close();

            Response defineTypeResponse = client.post("/objects/types/toolbox", Map.of(
                "properties", Map.of(
                    "interactive", true,
                    "carriable", true,
                    "walkable", true
                )
            ));
            assertEquals(200, defineTypeResponse.code());

            Response upsertObjectResponse = client.post("/objects/toolbox-1", Map.of(
                "type", "toolbox",
                "name", "Red Toolbox",
                "x", 32,
                "y", 64,
                "location", "Workshop",
                "properties", Map.of("condition", "new")
            ));
            JSONObject objectBody = new JSONObject(upsertObjectResponse.body().string());

            assertEquals(200, upsertObjectResponse.code());
            assertEquals("toolbox-1", objectBody.getString("id"));
            assertEquals("Red Toolbox", objectBody.getString("name"));
            assertEquals("Workshop", objectBody.getString("location"));
            assertEquals("toolbox", objectBody.getString("type"));

            Response patchResponse = client.patch("/objects/toolbox-1/properties", Map.of("condition", "used", "tag", "starter"));
            JSONObject patchedBody = new JSONObject(patchResponse.body().string());

            assertEquals(200, patchResponse.code());
            assertEquals("used", patchedBody.getJSONObject("properties").getString("condition"));
            assertEquals("starter", patchedBody.getJSONObject("properties").getString("tag"));

            Response getObjectResponse = client.get("/objects/toolbox-1");
            JSONObject getObjectBody = new JSONObject(getObjectResponse.body().string());
            assertEquals(200, getObjectResponse.code());
            assertEquals("used", getObjectBody.getJSONObject("properties").getString("condition"));

            Response positionResponse = client.get("/objects/toolbox-1/position");
            JSONObject positionBody = new JSONObject(positionResponse.body().string());
            assertEquals(200, positionResponse.code());
            assertEquals("object", positionBody.getString("kind"));
            assertEquals(32.0, positionBody.getDouble("x"));
            assertEquals(64.0, positionBody.getDouble("y"));

            Response listResponse = client.get("/objects");
            JSONObject listBody = new JSONObject(listResponse.body().string());
            JSONArray objects = listBody.getJSONArray("objects");
            assertTrue(objects.toList().stream().anyMatch(item ->
                "toolbox-1".equals(((Map<?, ?>) item).get("id"))
                    && "Red Toolbox".equals(((Map<?, ?>) item).get("name"))));
        });
    }

    @Test
    public void player_action_enqueue_flow_is_visible_through_action_history_endpoint() {
        JavalinTest.test(app, (server, client) -> {
            client.post("/locations", Map.of(
                "name", "Park",
                "type", "outdoor",
                "minX", 0,
                "maxX", 96,
                "minY", 0,
                "maxY", 96
            )).close();

            client.post("/player", Map.of(
                "name", "PlayerOne",
                "location", "Park",
                "activity", "idle",
                "memories", new String[0]
            )).close();

            Response enqueueResponse = client.post("/player/actions", Map.of(
                "playerId", "PlayerOne",
                "actionType", "wait",
                "actionDescription", "Take a breath",
                "playerX", 32,
                "playerY", 32
            ));
            JSONObject enqueueBody = new JSONObject(enqueueResponse.body().string());

            assertEquals(200, enqueueResponse.code());
            assertTrue(enqueueBody.getBoolean("success"));

            Response historyResponse = client.get("/player/PlayerOne/actions?limit=5");
            JSONObject historyBody = new JSONObject(historyResponse.body().string());
            JSONArray actions = historyBody.getJSONArray("actions");

            assertEquals(200, historyResponse.code());
            assertEquals(1, actions.length());
            assertEquals("PlayerOne", actions.getJSONObject(0).getString("playerId"));
            assertEquals("wait", actions.getJSONObject(0).getString("actionType"));
            assertEquals("Take a breath", actions.getJSONObject(0).getString("description"));
        });
    }

    @Test
    public void turn_flow_consumes_queued_wait_action_and_returns_runtime_state() {
        JavalinTest.test(app, (server, client) -> {
            client.post("/locations", Map.of(
                "name", "Park",
                "type", "outdoor",
                "minX", 0,
                "maxX", 96,
                "minY", 0,
                "maxY", 96
            )).close();

            client.post("/player", Map.of(
                "name", "PlayerOne",
                "location", "Park",
                "activity", "idle",
                "memories", new String[0]
            )).close();

            Response enqueueResponse = client.post("/player/actions", Map.of(
                "playerId", "PlayerOne",
                "actionType", "wait",
                "actionDescription", "Take a breath",
                "playerX", 32,
                "playerY", 32
            ));
            assertEquals(200, enqueueResponse.code());

            Response turnResponse = client.post("/turn", Map.of(
                "playerX", 32,
                "playerY", 32,
                "awarenessRadius", 180
            ));
            JSONObject turnBody = new JSONObject(turnResponse.body().string());
            JSONObject actionResult = turnBody.getJSONObject("actionResult");
            JSONObject playerState = actionResult.getJSONObject("playerState");
            JSONArray agents = turnBody.getJSONArray("agents");

            assertEquals(200, turnResponse.code());
            assertTrue(actionResult.getBoolean("success"));
            assertTrue(actionResult.getString("result").contains("Wait action processed"));
            assertEquals("PlayerOne", playerState.getString("name"));
            assertEquals("Take a breath", playerState.getString("action"));
            assertTrue(agents.toList().stream().anyMatch(item ->
                "PlayerOne".equals(((Map<?, ?>) item).get("name"))
                    && "Take a breath".equals(((Map<?, ?>) item).get("action"))));
        });
    }

    @Test
    public void runtime_orchestrate_flow_returns_runtime_summary_for_player_location() {
        JavalinTest.test(app, (server, client) -> {
            client.post("/locations", Map.of(
                "name", "Town Square",
                "type", "street",
                "minX", 0,
                "maxX", 64,
                "minY", 0,
                "maxY", 64
            )).close();

            client.post("/agents", Map.of(
                "name", "Alex",
                "activity", "reading",
                "location", "Town Square",
                "memories", List.of("likes routines")
            )).close();

            Response orchestrateResponse = client.post("/runtime/orchestrate", Map.of(
                "playerX", 32,
                "playerY", 32,
                "awarenessRadius", 180,
                "forceDayStart", true
            ));
            JSONObject orchestrateBody = new JSONObject(orchestrateResponse.body().string());

            assertEquals(200, orchestrateResponse.code());
            assertEquals("Town Square", orchestrateBody.getString("playerAwareLocation"));
            assertTrue(orchestrateBody.has("time"));
            assertTrue(orchestrateBody.get("llmUpdatedAgents") instanceof JSONArray);
            assertTrue(orchestrateBody.get("deterministicUpdatedAgents") instanceof JSONArray);
            assertTrue(orchestrateBody.get("reactedAgents") instanceof JSONArray);
        });
    }
}
