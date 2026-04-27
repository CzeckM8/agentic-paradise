package io.github.nickm980.smallville.save;

import java.time.LocalDateTime;

public class SaveSlotSummary {
    public String slotId;
    public String displayName;
    public boolean empty;
    public LocalDateTime savedAt;
    public String simulationTime;
    public String playerName;
    public String playerLocation;
    public int agentCount;
    public int objectCount;

    public SaveSlotSummary() {
    }

    public SaveSlotSummary(String slotId, String displayName, boolean empty) {
	this.slotId = slotId;
	this.displayName = displayName;
	this.empty = empty;
    }
}
