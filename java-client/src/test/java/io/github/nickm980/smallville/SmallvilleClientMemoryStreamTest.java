package io.github.nickm980.smallville;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class SmallvilleClientMemoryStreamTest {
    private static SmallvilleClient client;

    @BeforeAll
    public static void setUp() {
	client = SmallvilleClient.create("http://localhost:8080", new AgentHandlerCallback() {
	    @Override
	    public void handle(SimulationUpdateEvent event) {
		System.out.println("Finished updating");
		for (SmallvilleAgent agent : event.getAgents()) {
		    System.out.println(agent.getName() + " : " + agent.getAction());
		}
	    }
	});
    }
    
    @Test
    @Disabled("Integration test - requires running Smallville server at localhost:8080")
    public void testMemoryStreamCreation() {
	UUID uuid = client.createMemoryStream();
	
	assertNotNull(uuid);
    }
    
    @Test
    @Disabled("Integration test - requires running Smallville server at localhost:8080")
    public void testMemoryStreamCreateMemory() {
	UUID uuid = client.createMemoryStream();
	boolean success = client.addMemory(uuid, "memory 1");
	assertTrue(success);
    }
    
    @Test
    @Disabled("Integration test - requires running Smallville server at localhost:8080")
    public void testMemoryStreamFetchRelevantMemories() {
	UUID uuid = client.createMemoryStream();
	client.addMemory(uuid, "memory 1");
	client.addMemory(uuid, "memory 1");
	client.addMemory(uuid, "memory 1");
	client.addMemory(uuid, "memory 1");
	client.addMemory(uuid, "memory 1");

	List<String> memories = client.fetchMemories(uuid, "memory 1");
	assertEquals(3, memories.size());
    }
}
