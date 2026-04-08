package io.github.nickm980.smallville;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import io.github.nickm980.smallville.entities.AgentAction;

public class AgentActionTest {

    @Test
    public void constructor_sets_type_description_and_default_status() {
        AgentAction action = new AgentAction("move", "Head to town");

        assertEquals("move", action.getType());
        assertEquals("Head to town", action.getDescription());
        assertEquals("queued", action.getStatus());
    }

    @Test
    public void copy_preserves_all_fields() {
        AgentAction action = new AgentAction("speak", "Talk to Sam");
        action.setId("action-7");
        action.setEmoji("smile");
        action.setTargetLocation("Cafe");
        action.setTargetAgent("Sam");
        action.setItem("coffee");
        action.setSpeakText("Hello there");
        action.setTargetX(24.0);
        action.setTargetY(56.0);
        action.setStatus("in_progress");

        AgentAction copy = action.copy();

        assertEquals("action-7", copy.getId());
        assertEquals("speak", copy.getType());
        assertEquals("Talk to Sam", copy.getDescription());
        assertEquals("smile", copy.getEmoji());
        assertEquals("Cafe", copy.getTargetLocation());
        assertEquals("Sam", copy.getTargetAgent());
        assertEquals("coffee", copy.getItem());
        assertEquals("Hello there", copy.getSpeakText());
        assertEquals(24.0, copy.getTargetX());
        assertEquals(56.0, copy.getTargetY());
        assertEquals("in_progress", copy.getStatus());
    }

    @Test
    public void copy_is_independent_from_original() {
        AgentAction action = new AgentAction("activity", "Read");
        AgentAction copy = action.copy();

        copy.setDescription("Write");
        copy.setStatus("completed");

        assertEquals("Read", action.getDescription());
        assertEquals("queued", action.getStatus());
        assertNotEquals(action.getDescription(), copy.getDescription());
    }
}
