package io.github.nickm980.smallville;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.entities.Conversation;
import io.github.nickm980.smallville.entities.Dialog;
import io.github.nickm980.smallville.entities.Location;
import io.github.nickm980.smallville.entities.Player;
import io.github.nickm980.smallville.exceptions.SmallvilleException;

public class WorldTest {

    private World world;

    @BeforeEach
    public void setUp() {
	world = new World();
    }

    @Test
    public void test_world_locations() {
	assertTrue(world.getLocation("missing location").isEmpty());

	world.create(new Location("location name"));

	assertTrue(world.getLocation("location name").isPresent());

	world.setState("location name", "empty");

	assertTrue(world.getLocation("location name").get().getState().equals("empty"));
    }

    @Test
    public void test_saving_null_location_throws_error() {
	assertThrows(Exception.class, () -> {
	    world.setState(null, null);
	});
    }

    @Test
    public void test_world_conversation_creation() {
	assertEquals(0, world.getConversationsAfter(LocalDateTime.now()).size());

	Conversation conversation = new Conversation("none", "", List.of(new Dialog("john", "hi")));
	world.create(conversation);

	assertEquals(1, world.getConversationsAfter(LocalDateTime.now()).size());

	assertThrows(SmallvilleException.class, () -> {
	    world.create(new Conversation("name", "name", List.of(new Dialog("name", "message"))));
	});

	assertThrows(SmallvilleException.class, () -> {
	    world.create(new Conversation("name", "name", List.of()));
	});
    }

    @Test
    public void test_find_conversation_between() {
	Conversation conversation = new Conversation("Alex", "Sam", List.of(new Dialog("Alex", "hi")));
	world.create(conversation);

	assertTrue(world.findConversationBetween("Alex", "Sam").isPresent());
	assertTrue(world.findConversationBetween("Sam", "Alex").isPresent());
	assertFalse(world.findConversationBetween("Alex", "Jordan").isPresent());
    }

    @Test
    public void test_remove_non_player_agents() {
	Location location = new Location("square");
	world.create(location);
	world.create(new Agent("Alex", List.of(), "idle", location));
	world.create(new Agent("Sam", List.of(), "idle", location));
	world.create(new Player("Player", location));

	assertEquals(2, world.removeNonPlayerAgents());
	assertEquals(1, world.getAgents().size());
	assertEquals("Player", world.getAgents().getFirst().getFullName());
    }

    @Test
    public void test_create_agent_and_fetch_it() {
	Location location = new Location("cafe");
	world.create(location);
	Agent agent = new Agent("Jamie", List.of(), "reading", location);

	assertTrue(world.create(agent));
	assertNotNull(world.getAgent("Jamie").orElse(null));
	assertEquals("reading", world.getAgent("Jamie").orElseThrow().getCurrentActivity());
    }
}
