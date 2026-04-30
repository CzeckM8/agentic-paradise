package io.github.nickm980.smallville;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.entities.Location;
import io.github.nickm980.smallville.prompts.Prompts;
import io.github.nickm980.smallville.prompts.dto.CurrentActivity;
import io.github.nickm980.smallville.update.UpdateCurrentActivity;
import io.github.nickm980.smallville.update.UpdateInfo;

public class UpdateCurrentActivityTest {

    private World world;
    private Agent agent;
    private Prompts prompts;
    private UpdateCurrentActivity update;

    @BeforeEach
    public void setUp() {
        world = new World();

        Location home = new Location("Home");
        home.setMinX(0);
        home.setMaxX(64);
        home.setMinY(0);
        home.setMaxY(64);
        world.create(home);

        Location market = new Location("Market");
        market.setMinX(96);
        market.setMaxX(160);
        market.setMinY(0);
        market.setMaxY(64);
        world.create(market);

        agent = new Agent("Riley", List.of(), "idle", home);
        prompts = Mockito.mock(Prompts.class);
        update = new UpdateCurrentActivity();
    }

    @Test
    public void update_queues_move_then_activity_for_new_destination() {
        CurrentActivity currentActivity = new CurrentActivity();
        currentActivity.setActivity("Buy groceries");
        currentActivity.setEmoji("cart");
        currentActivity.setLocation("Market");
        currentActivity.setLastActivity("Finished breakfast");
        Mockito.when(prompts.getCurrentActivity(agent)).thenReturn(currentActivity);

        boolean result = update.update(prompts, world, agent, new UpdateInfo());

        assertTrue(result);
        assertEquals("cart", agent.getEmoji());
        assertEquals("Going to Market", agent.getCurrentActivity());
        assertEquals(2, agent.getQueuedActions().size());
        assertEquals("move", agent.getQueuedActions().get(0).getType());
        assertEquals("Market", agent.getQueuedActions().get(0).getTargetLocation());
        assertEquals("activity", agent.getQueuedActions().get(1).getType());
        assertEquals("Buy groceries", agent.getQueuedActions().get(1).getDescription());
        assertEquals("Market", agent.getQueuedActions().get(1).getTargetLocation());
        assertEquals("Finished breakfast", agent.getMemoryStream().getObservations().getLast().getDescription());
    }

    @Test
    public void update_keeps_local_activity_when_destination_is_unknown() {
        CurrentActivity currentActivity = new CurrentActivity();
        currentActivity.setActivity("Sort the mail");
        currentActivity.setEmoji("mail");
        currentActivity.setLocation("Nowhere");
        Mockito.when(prompts.getCurrentActivity(agent)).thenReturn(currentActivity);

        update.update(prompts, world, agent, new UpdateInfo());

        assertEquals("mail", agent.getEmoji());
        assertEquals("Sort the mail", agent.getCurrentActivity());
        assertEquals(1, agent.getQueuedActions().size());
        assertEquals("activity", agent.getQueuedActions().getFirst().getType());
        assertEquals("Home", agent.getQueuedActions().getFirst().getTargetLocation());
        assertNull(agent.getActiveAction());
    }

    @Test
    public void update_replaces_existing_queue_before_building_new_actions() {
        agent.enqueueAction(new io.github.nickm980.smallville.entities.AgentAction("activity", "Old plan"));
        agent.startNextAction();
        agent.setTargetLocation("Old target");

        CurrentActivity currentActivity = new CurrentActivity();
        currentActivity.setActivity("Read quietly");
        currentActivity.setEmoji("book");
        Mockito.when(prompts.getCurrentActivity(agent)).thenReturn(currentActivity);

        update.update(prompts, world, agent, new UpdateInfo());

        assertNull(agent.getActiveAction());
        assertEquals(1, agent.getQueuedActions().size());
        assertEquals("Read quietly", agent.getQueuedActions().getFirst().getDescription());
        assertEquals("Home", agent.getQueuedActions().getFirst().getTargetLocation());
        assertNotNull(agent.getMemoryStream());
    }
}
