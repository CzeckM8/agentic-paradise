package io.github.nickm980.smallville;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.nickm980.smallville.entities.ActionHistory;

public class ActionHistoryTest {

    @Test
    public void setActivity_tracks_previous_activity() {
        ActionHistory history = new ActionHistory("idle");

        history.setActivity("walking home");

        assertEquals("walking home", history.getActivity());
        assertEquals("idle", history.getLastActivity());
    }

    @Test
    public void setEmoji_updates_current_emoji() {
        ActionHistory history = new ActionHistory("idle");

        history.setEmoji("happy");

        assertEquals("happy", history.getEmoji());
    }

    @Test
    public void setStressLevel_clamps_and_updates_mental_state() {
        ActionHistory history = new ActionHistory("idle");

        history.setStressLevel(-1.0);
        assertEquals(0.0, history.getStressLevel());
        assertEquals("calm", history.getMentalState());

        history.setStressLevel(0.3);
        assertEquals("anxious", history.getMentalState());

        history.setStressLevel(0.6);
        assertEquals("panicked", history.getMentalState());

        history.setStressLevel(2.0);
        assertEquals(1.0, history.getStressLevel());
        assertEquals("aggressive", history.getMentalState());
    }

    @Test
    public void applyStressChange_uses_current_level() {
        ActionHistory history = new ActionHistory("idle");
        history.setStressLevel(0.15);

        history.applyStressChange(0.1);

        assertEquals(0.25, history.getStressLevel());
        assertEquals("anxious", history.getMentalState());
    }
}
