package io.github.nickm980.smallville;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.nickm980.smallville.entities.Agent;
import io.github.nickm980.smallville.entities.AgentAction;
import io.github.nickm980.smallville.entities.Location;

public class AgentTest {

    private Agent agent;

    @BeforeEach
    public void setUp() {
        Location home = new Location("Home");
        home.setMinX(0);
        home.setMaxX(64);
        home.setMinY(0);
        home.setMaxY(64);
        agent = new Agent("Alex", List.of(), "idle", home);
    }

    @Test
    public void startNextAction_promotes_actions_in_order() {
        AgentAction move = new AgentAction("move", "Go to the square");
        move.setTargetLocation("Town Square");
        AgentAction speak = new AgentAction("speak", "Say hello");
        speak.setSpeakText("Hi there");

        agent.enqueueAction(move);
        agent.enqueueAction(speak);

        AgentAction first = agent.startNextAction();

        assertNotNull(first);
        assertEquals("move", first.getType());
        assertEquals("in_progress", first.getStatus());
        assertEquals(1, agent.getQueuedActions().size());
        assertEquals("speak", agent.getQueuedActions().getFirst().getType());
    }

    @Test
    public void completeActiveAction_returns_completed_copy_and_clears_target() {
        AgentAction move = new AgentAction("move", "Head home");
        move.setTargetLocation("Home");
        agent.enqueueAction(move);
        agent.startNextAction();
        agent.setTargetLocation("Home");

        AgentAction completed = agent.completeActiveAction();

        assertNotNull(completed);
        assertEquals("completed", completed.getStatus());
        assertNull(agent.getActiveAction());
        assertNull(agent.getTargetLocation());
        assertFalse(agent.hasPendingActions());
    }

    @Test
    public void replaceActionQueue_clears_previous_state_and_enqueues_new_actions() {
        AgentAction oldAction = new AgentAction("move", "Old move");
        agent.enqueueAction(oldAction);
        agent.startNextAction();
        agent.setTargetLocation("Old Place");
        agent.setDeferScriptedActivityPresentation(true);

        AgentAction freshAction = new AgentAction("activity", "Read a book");
        agent.replaceActionQueue(List.of(freshAction));

        assertNull(agent.getActiveAction());
        assertNull(agent.getTargetLocation());
        assertFalse(agent.isDeferScriptedActivityPresentation());
        assertEquals(1, agent.getQueuedActions().size());
        assertEquals("Read a book", agent.getQueuedActions().getFirst().getDescription());
        assertEquals("queued", agent.getQueuedActions().getFirst().getStatus());
    }

    @Test
    public void returned_actions_are_defensive_copies() {
        AgentAction action = new AgentAction("activity", "Sketch");
        agent.enqueueAction(action);

        AgentAction queuedCopy = agent.getQueuedActions().getFirst();
        queuedCopy.setDescription("Changed outside");

        AgentAction started = agent.startNextAction();
        started.setDescription("Changed again");

        assertEquals("Sketch", agent.getActiveAction().getDescription());
    }

    @Test
    public void clearActions_resets_queue_and_flags() {
        agent.enqueueAction(new AgentAction("activity", "Write notes"));
        agent.startNextAction();
        agent.setTargetLocation("Desk");
        agent.setDeferScriptedActivityPresentation(true);

        agent.clearActions();

        assertFalse(agent.hasPendingActions());
        assertNull(agent.getTargetLocation());
        assertFalse(agent.isDeferScriptedActivityPresentation());
        assertTrue(agent.getQueuedActions().isEmpty());
    }
}
