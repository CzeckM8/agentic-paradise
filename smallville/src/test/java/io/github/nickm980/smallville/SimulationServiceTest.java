package io.github.nickm980.smallville;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.github.nickm980.smallville.api.v1.SimulationService;
import io.github.nickm980.smallville.api.v1.dto.AgentStateResponse;
import io.github.nickm980.smallville.api.v1.dto.CreateAgentRequest;
import io.github.nickm980.smallville.api.v1.dto.CreateLocationRequest;
import io.github.nickm980.smallville.api.v1.dto.CreateMemoryRequest;
import io.github.nickm980.smallville.api.v1.dto.CreatePlayerRequest;
import io.github.nickm980.smallville.api.v1.dto.ObjectInstanceUpsertRequest;
import io.github.nickm980.smallville.api.v1.dto.PlayerActionRequest;
import io.github.nickm980.smallville.api.v1.dto.RuntimeOrchestrationRequest;
import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.entities.AgentAction;
import io.github.nickm980.smallville.entities.ChronicleEvent;
import io.github.nickm980.smallville.entities.Location;
import io.github.nickm980.smallville.entities.SimulationTime;
import io.github.nickm980.smallville.llm.ChatGPT;
import io.github.nickm980.smallville.memory.Commitment;
import io.github.nickm980.smallville.memory.CommitmentStatus;
import io.github.nickm980.smallville.memory.Memory;
import io.github.nickm980.smallville.memory.Reflection;

public class SimulationServiceTest {

    private SimulationService service;
    private World world;

    @BeforeEach
    public void setUp() {
	ChatGPT llm = Mockito.mock(ChatGPT.class);
	Mockito.when(llm.sendChat(Mockito.any(), Mockito.anyInt())).thenReturn("result");
	world = new World();
	service = new SimulationService(llm, world);
    }

    @Test
    public void test_location_creation_is_added_to_list() {
	CreateLocationRequest request = new CreateLocationRequest();
	request.setName("location");

	service.createLocation(request);

	assertEquals(1, service.getAllLocations().size());
    }

    @Test
    public void test_agent_creation_is_added_to_list() {
	CreateLocationRequest request = new CreateLocationRequest();
	request.setName("location");

	service.createLocation(request);

	CreateAgentRequest createAgent = new CreateAgentRequest();
	createAgent.setActivity("doing nothing");
	createAgent.setLocation("location");
	createAgent.setMemories(List.of("memory1"));
	createAgent.setName("name");

	service.createAgent(createAgent);

	assertEquals(1, service.getAgents().size());
    }

    @Test
    public void test_service_memory_creation_does_not_throw() {
	CreateLocationRequest createLocation = new CreateLocationRequest();
	createLocation.setName("location");

	service.createLocation(createLocation);

	CreateAgentRequest createAgent = new CreateAgentRequest();
	createAgent.setActivity("doing nothing");
	createAgent.setLocation("location");
	createAgent.setMemories(List.of("memory1"));
	createAgent.setName("name");

	service.createAgent(createAgent);

	CreateMemoryRequest request = new CreateMemoryRequest();
	request.setName("name");
	request.setDescription("description");
	request.setReactable(false);

	assertDoesNotThrow(() -> {
	    service.createMemory(request);
	});
    }

    @Test
    public void test_agent_actions_are_processed_in_order() {
	Location start = new Location("Start");
	start.setMinX(0);
	start.setMaxX(64);
	start.setMinY(0);
	start.setMaxY(64);
	world.create(start);

	Location destination = new Location("Workshop");
	destination.setMinX(96);
	destination.setMaxX(160);
	destination.setMinY(0);
	destination.setMaxY(64);
	world.create(destination);

	CreateAgentRequest createAgent = new CreateAgentRequest();
	createAgent.setName("Alex");
	createAgent.setActivity("idle");
	createAgent.setLocation("Start");
	createAgent.setMemories(List.of("likes routines"));
	service.createAgent(createAgent);

	Agent agent = world.getAgent("Alex").orElseThrow();
	AgentAction move = new AgentAction("move", "Going to Workshop");
	move.setTargetLocation("Workshop");
	AgentAction perform = new AgentAction("activity", "Inspecting the shelf");
	perform.setTargetLocation("Workshop");
	agent.enqueueAction(move);
	agent.enqueueAction(perform);

	RuntimeOrchestrationRequest request = new RuntimeOrchestrationRequest();
	service.orchestrateRuntime(request);

	AgentStateResponse firstTurn = service.getAgentState("Alex");
	assertNotNull(firstTurn.getActiveAction());
	assertEquals("move", firstTurn.getActiveAction().getType());
	assertEquals(1, firstTurn.getQueuedActions().size());

	service.orchestrateRuntime(request);
	service.orchestrateRuntime(request);
	service.orchestrateRuntime(request);

	AgentStateResponse afterMove = service.getAgentState("Alex");
	assertEquals("Workshop", afterMove.getLocation());
	assertNotNull(afterMove.getActiveAction());
	assertEquals("activity", afterMove.getActiveAction().getType());
	assertEquals(0, afterMove.getQueuedActions().size());

	service.orchestrateRuntime(request);

	AgentStateResponse completed = service.getAgentState("Alex");
	assertEquals("Workshop", completed.getLocation());
	assertEquals("Inspecting the shelf", completed.getAction());
	assertNull(completed.getActiveAction());
	assertEquals(0, completed.getQueuedActions().size());
    }

    @Test
    public void test_save_and_load_restores_core_game_state() {
	java.time.Duration originalStep = SimulationTime.getStepDuration();
	java.time.LocalDateTime originalTime = SimulationTime.now();
	service.deleteSave("slot-3");
	try {
	    CreateLocationRequest market = new CreateLocationRequest();
	    market.setName("market");
	    market.setType("market");
	    market.setMinX(0);
	    market.setMaxX(200);
	    market.setMinY(0);
	    market.setMaxY(200);
	    service.createLocation(market);

	    CreatePlayerRequest player = new CreatePlayerRequest();
	    player.setName("Player");
	    player.setLocation("market");
	    player.setActivity("idle");
	    player.setMemories(new String[] {"I remember the market."});
	    service.createPlayer(player);

	    world.getAgent("Player").orElseThrow().setPosition(64, 96);

	    ObjectInstanceUpsertRequest object = new ObjectInstanceUpsertRequest();
	    object.setType("pencil");
	    object.setName("Pencil");
	    object.setX(64);
	    object.setY(96);
	    object.setLocation("market");
	    service.upsertObjectInstance("pencil-1", object);

	    PlayerActionRequest action = new PlayerActionRequest();
	    action.setPlayerId("Player");
	    action.setActionType("interact");
	    action.setActionDescription("Inspecting the pencil");
	    service.enqueuePlayerAction(action);

	    SimulationTime.setStep(java.time.Duration.ofMinutes(5));
	    service.saveGame("slot-3");

	    world.getAgent("Player").orElseThrow().setPosition(128, 128);
	    service.patchObjectProperties("pencil-1", java.util.Map.of("broken", true));
	    SimulationTime.setStep(java.time.Duration.ofMinutes(1));

	    service.loadGame("slot-3");

	    AgentStateResponse restoredPlayer = service.getAgentState("Player");
	    assertEquals("market", restoredPlayer.getLocation());
	    assertEquals(64.0, restoredPlayer.getX());
	    assertEquals(96.0, restoredPlayer.getY());
	    assertEquals(5, SimulationTime.getStepDurationInMinutes());
	    assertEquals("Pencil", service.getObjectInstance("pencil-1").get("name"));
	    assertEquals(1, service.getPlayerActionHistory("Player", 10).size());
	} finally {
	    SimulationTime.setStep(originalStep);
	    SimulationTime.setSimulationTime(originalTime);
	    service.deleteSave("slot-3");
	}
    }

    @Test
    public void test_save_and_load_restores_conversation_memory_and_belief_state() {
	java.time.Duration originalStep = SimulationTime.getStepDuration();
	java.time.LocalDateTime originalTime = SimulationTime.now();
	service.deleteSave("slot-3");
	try {
	    CreateLocationRequest market = new CreateLocationRequest();
	    market.setName("market");
	    market.setType("market");
	    market.setMinX(0);
	    market.setMaxX(200);
	    market.setMinY(0);
	    market.setMaxY(200);
	    service.createLocation(market);

	    CreatePlayerRequest player = new CreatePlayerRequest();
	    player.setName("Player");
	    player.setLocation("market");
	    player.setActivity("idle");
	    player.setMemories(new String[] {"I just arrived."});
	    service.createPlayer(player);

	    CreateAgentRequest alexRequest = new CreateAgentRequest();
	    alexRequest.setName("Alex");
	    alexRequest.setLocation("market");
	    alexRequest.setActivity("idle");
	    alexRequest.setMemories(List.of("Alex likes long talks."));
	    service.createAgent(alexRequest);

	    Agent playerAgent = world.getAgent("Player").orElseThrow();
	    Agent alex = world.getAgent("Alex").orElseThrow();
	    playerAgent.setPosition(64, 64);
	    alex.setPosition(96, 64);

	    Reflection reflection = new Reflection("I should be more patient with strangers.");
	    reflection.setImportance(6);
	    alex.getMemoryStream().add(reflection);

	    Commitment commitment = new Commitment(
		"Meet the Player",
		"market",
		LocalDateTime.of(2026, 4, 27, 12, 5),
		LocalDateTime.of(2026, 4, 27, 12, 25),
		9);
	    commitment.setImportance(8);
	    commitment.setStatus(CommitmentStatus.ACTIVE);
	    alex.getMemoryStream().add(commitment);

	    alex.getEpistemicMemory().ingestHearsay("Player", "The market closes soon.", 1, 0.8);
	    alex.getEpistemicMemory().ingestBeliefCorrection(1, "open", "north_gate", io.github.nickm980.smallville.entities.RejectReason.TARGET_NOT_FOUND,
		"The north gate should open from here.", "The north gate is too far away.");
	    ChronicleEvent observed = new ChronicleEvent(1, "Player", "player", "speak", "Alex", "agent",
		"Hello Alex", 64, 64, 96, 64, java.util.Set.of("Alex", "Player"));
	    alex.getEpistemicMemory().ingestObserved(observed);

	    PlayerActionRequest firstSpeak = new PlayerActionRequest();
	    firstSpeak.setPlayerId("Player");
	    firstSpeak.setActionType("speak");
	    firstSpeak.setTargetAgent("Alex");
	    firstSpeak.setActionDescription("Talking with Alex");
	    firstSpeak.setSpeakText("Hello Alex");
	    service.enqueuePlayerAction(firstSpeak);
	    service.processNextAction();

	    String savedTranscript = (String) service.getConversationTranscript("Player", "Alex", 10).get("transcript");
	    int savedObserved = alex.getEpistemicMemory().observedCount();
	    int savedHearsay = alex.getEpistemicMemory().hearsayCount();
	    int savedCorrections = alex.getEpistemicMemory().correctionCount();
	    long savedReflectionCount = alex.getMemoryStream().getMemories().stream().filter(Reflection.class::isInstance).count();
	    Commitment savedCommitment = (Commitment) alex.getMemoryStream().getMemories().stream()
		.filter(Commitment.class::isInstance)
		.findFirst()
		.orElseThrow();

	    service.saveGame("slot-3");

	    PlayerActionRequest secondSpeak = new PlayerActionRequest();
	    secondSpeak.setPlayerId("Player");
	    secondSpeak.setActionType("speak");
	    secondSpeak.setTargetAgent("Alex");
	    secondSpeak.setActionDescription("Talking with Alex again");
	    secondSpeak.setSpeakText("What did you mean earlier?");
	    service.enqueuePlayerAction(secondSpeak);
	    service.processNextAction();

	    alex.getEpistemicMemory().ingestHearsay("Nora", "A new rumor started after the save.", 2, 0.4);
	    Reflection postSaveReflection = new Reflection("This memory should disappear after load.");
	    postSaveReflection.setImportance(2);
	    alex.getMemoryStream().add(postSaveReflection);

	    String mutatedTranscript = (String) service.getConversationTranscript("Player", "Alex", 10).get("transcript");
	    assertTrue(mutatedTranscript.length() > savedTranscript.length());

	    service.loadGame("slot-3");

	    Agent restoredAlex = world.getAgent("Alex").orElseThrow();
	    String restoredTranscript = (String) service.getConversationTranscript("Player", "Alex", 10).get("transcript");
	    assertEquals(savedTranscript, restoredTranscript);
	    assertEquals(savedObserved, restoredAlex.getEpistemicMemory().observedCount());
	    assertEquals(savedHearsay, restoredAlex.getEpistemicMemory().hearsayCount());
	    assertEquals(savedCorrections, restoredAlex.getEpistemicMemory().correctionCount());

	    List<Memory> restoredMemories = restoredAlex.getMemoryStream().getMemories();
	    assertEquals(savedReflectionCount, restoredMemories.stream().filter(Reflection.class::isInstance).count());
	    Commitment restoredCommitment = (Commitment) restoredMemories.stream()
		.filter(Commitment.class::isInstance)
		.findFirst()
		.orElseThrow();
	    assertEquals(savedCommitment.getGoal(), restoredCommitment.getGoal());
	    assertEquals(savedCommitment.getLocation(), restoredCommitment.getLocation());
	    assertEquals(savedCommitment.getEndTime(), restoredCommitment.getEndTime());
	    assertEquals(savedCommitment.getStatus(), restoredCommitment.getStatus());
	} finally {
	    SimulationTime.setStep(originalStep);
	    SimulationTime.setSimulationTime(originalTime);
	    service.deleteSave("slot-3");
	}
    }
}
