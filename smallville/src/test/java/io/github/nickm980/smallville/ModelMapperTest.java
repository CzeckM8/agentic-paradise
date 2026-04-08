package io.github.nickm980.smallville;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.nickm980.smallville.api.v1.dto.ConversationResponse;
import io.github.nickm980.smallville.api.v1.dto.AgentActionStateResponse;
import io.github.nickm980.smallville.api.v1.dto.AgentDeltaStateResponse;
import io.github.nickm980.smallville.api.v1.dto.AgentStateResponse;
import io.github.nickm980.smallville.api.v1.dto.LocationStateResponse;
import io.github.nickm980.smallville.api.v1.dto.MemoryResponse;
import io.github.nickm980.smallville.api.v1.dto.ModelMapper;
import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.entities.AgentAction;
import io.github.nickm980.smallville.entities.Conversation;
import io.github.nickm980.smallville.entities.Dialog;
import io.github.nickm980.smallville.entities.Location;
import io.github.nickm980.smallville.memory.Characteristic;
import io.github.nickm980.smallville.memory.Observation;
import io.github.nickm980.smallville.memory.Plan;
import io.github.nickm980.smallville.memory.PlanType;
import io.github.nickm980.smallville.memory.Reflection;

public class ModelMapperTest {

    private ModelMapper mapper;

    @BeforeEach
    public void setUp() {
        mapper = new ModelMapper();
    }

    @Test
    public void fromAgent_maps_location_target_and_action_queue() {
        Location library = new Location("Library");
        library.setMinX(10);
        library.setMaxX(74);
        library.setMinY(20);
        library.setMaxY(84);

        Agent agent = new Agent("Jordan", List.of(), "Browsing", library);
        agent.setCurrentEmoji("book");
        agent.setTargetLocation("Library: Reading Room");

        AgentAction move = new AgentAction("move", "Walk to the reading room");
        move.setTargetLocation("Library: Reading Room");
        move.setEmoji("walk");
        move.setTargetX(32.0);
        move.setTargetY(64.0);
        agent.enqueueAction(move);

        AgentAction speak = new AgentAction("speak", "Ask for the catalog");
        speak.setSpeakText("Where is the catalog?");
        speak.setTargetAgent("Clerk");
        speak.setItem("library-card");
        agent.enqueueAction(speak);
        agent.startNextAction();

        AgentStateResponse response = mapper.fromAgent(agent);

        assertEquals("Jordan", response.getName());
        assertEquals("Browsing", response.getAction());
        assertEquals("book", response.getEmoji());
        assertEquals("Library", response.getLocation());
        assertEquals("Library: Reading Room", response.getTargetLocation());
        assertEquals(library.getCenterX(), response.getX());
        assertEquals(library.getCenterY(), response.getY());
        assertNotNull(response.getActiveAction());
        assertEquals("move", response.getActiveAction().getType());
        assertEquals("in_progress", response.getActiveAction().getStatus());
        assertEquals(1, response.getQueuedActions().size());
        assertEquals("speak", response.getQueuedActions().getFirst().getType());
        assertEquals("Clerk", response.getQueuedActions().getFirst().getTargetAgent());
        assertEquals("Where is the catalog?", response.getQueuedActions().getFirst().getSpeakText());
        assertEquals("library-card", response.getQueuedActions().getFirst().getItem());
    }

    @Test
    public void fromAgentAction_maps_all_fields() {
        AgentAction action = new AgentAction("pickup", "Pick up the letter");
        action.setId("action-123");
        action.setEmoji("mail");
        action.setTargetLocation("Post Office");
        action.setTargetAgent("Mia");
        action.setItem("letter");
        action.setSpeakText("Thanks for waiting.");
        action.setTargetX(12.5);
        action.setTargetY(42.0);
        action.setStatus("queued");

        AgentActionStateResponse response = mapper.fromAgentAction(action);

        assertEquals("action-123", response.getId());
        assertEquals("pickup", response.getType());
        assertEquals("Pick up the letter", response.getDescription());
        assertEquals("mail", response.getEmoji());
        assertEquals("Post Office", response.getTargetLocation());
        assertEquals("Mia", response.getTargetAgent());
        assertEquals("letter", response.getItem());
        assertEquals("Thanks for waiting.", response.getSpeakText());
        assertEquals(12.5, response.getTargetX());
        assertEquals(42.0, response.getTargetY());
        assertEquals("queued", response.getStatus());
    }

    @Test
    public void fromAgentAction_returns_null_for_null_input() {
        assertNull(mapper.fromAgentAction(null));
    }

    @Test
    public void fromAgent_returns_independent_action_snapshots() {
        Agent agent = new Agent("Casey", List.of(), "Waiting", null);
        agent.enqueueAction(new AgentAction("activity", "Fold flyers"));

        AgentStateResponse response = mapper.fromAgent(agent);
        response.getQueuedActions().getFirst().setDescription("Changed outside");

        AgentStateResponse freshResponse = mapper.fromAgent(agent);

        assertEquals("Fold flyers", freshResponse.getQueuedActions().getFirst().getDescription());
        assertTrue(freshResponse.getQueuedActions().stream().allMatch(action -> action.getStatus() != null));
    }

    @Test
    public void fromAgentDelta_maps_public_status_fields() {
        Location park = new Location("Park");
        Agent agent = new Agent("Morgan", List.of(), "Walking", park);
        agent.setCurrentEmoji("smile");
        agent.applyStressChange(0.3);

        AgentDeltaStateResponse response = mapper.fromAgentDelta(agent);

        assertEquals("Morgan", response.getName());
        assertEquals("Park", response.getLocation());
        assertEquals("Walking", response.getCurrentAction());
        assertEquals("smile", response.getEmoji());
        assertEquals(agent.getStressLevel(), response.getStressLevel());
        assertEquals(agent.getMentalState(), response.getMentalState());
    }

    @Test
    public void fromLocation_maps_bounds_and_type() {
        Location location = new Location("Bakery");
        location.setType("shop");
        location.setState("open");
        location.setMinX(5);
        location.setMaxX(25);
        location.setMinY(10);
        location.setMaxY(50);

        LocationStateResponse response = mapper.fromLocation(location);

        assertEquals("Bakery", response.getName());
        assertEquals("shop", response.getType());
        assertEquals("open", response.getState());
        assertEquals(5.0, response.getMinX());
        assertEquals(25.0, response.getMaxX());
        assertEquals(10.0, response.getMinY());
        assertEquals(50.0, response.getMaxY());
    }

    @Test
    public void fromMemory_maps_memory_subtypes() {
        Observation observation = new Observation("Saw a comet");
        observation.setImportance(7);
        Plan plan = new Plan("Meet at noon", LocalDateTime.of(2026, 4, 8, 12, 30), PlanType.SHORT_TERM);
        Characteristic characteristic = new Characteristic("Keeps careful notes");
        Reflection reflection = new Reflection("I should slow down");

        MemoryResponse observationResponse = mapper.fromMemory(observation);
        MemoryResponse planResponse = mapper.fromMemory(plan);
        MemoryResponse characteristicResponse = mapper.fromMemory(characteristic);
        MemoryResponse reflectionResponse = mapper.fromMemory(reflection);

        assertEquals("Observation", observationResponse.getType());
        assertEquals(7.0, observationResponse.getImportance());
        assertEquals("Plan", planResponse.getType());
        assertEquals("12:30 PM", planResponse.getTime());
        assertEquals("Characteristic", characteristicResponse.getType());
        assertEquals("Reflection", reflectionResponse.getType());
    }

    @Test
    public void fromConversation_maps_each_dialog_line() {
        Conversation conversation = new Conversation(
            "Alex",
            "Sam",
            List.of(
                new Dialog("Alex", "Hi"),
                new Dialog("Sam", "Hello")
            )
        );

        List<ConversationResponse> response = mapper.fromConversation(conversation);

        assertEquals(2, response.size());
        assertEquals("Alex", response.get(0).getName());
        assertEquals("Hi", response.get(0).getMessage());
        assertEquals("Sam", response.get(1).getName());
        assertEquals("Hello", response.get(1).getMessage());
    }
}
