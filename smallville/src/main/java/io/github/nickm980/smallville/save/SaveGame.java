package io.github.nickm980.smallville.save;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.nickm980.smallville.api.v1.dto.PlayerActionRequest;
import io.github.nickm980.smallville.entities.AgentAction;
import io.github.nickm980.smallville.entities.InventoryItem;
import io.github.nickm980.smallville.entities.WorldObjectInstance;

public class SaveGame {
    public int schemaVersion = 1;
    public String slotId;
    public String displayName;
    public LocalDateTime savedAt;
    public LocalDateTime simulationTime;
    public long stepMinutes;
    public String trackedAgentName;
    public int progress;
    public int turnCounter;
    public List<SavedLocation> locations = new ArrayList<>();
    public List<SavedAgent> agents = new ArrayList<>();
    public List<SavedConversation> conversations = new ArrayList<>();
    public Map<String, Map<String, Object>> objectTypeDefinitions = new LinkedHashMap<>();
    public Map<String, WorldObjectInstance> objectInstances = new LinkedHashMap<>();
    public Map<String, List<String>> inventoryByAgent = new LinkedHashMap<>();
    public Map<String, List<PlayerActionRequest>> actionHistoryByPlayer = new LinkedHashMap<>();
    public List<PlayerActionRequest> pendingPlayerActions = new ArrayList<>();

    public static class SavedLocation {
	public String name;
	public String state;
	public String type;
	public double minX;
	public double maxX;
	public double minY;
	public double maxY;
    }

    public static class SavedAgent {
	public String name;
	public boolean player;
	public String activity;
	public String emoji;
	public String location;
	public String targetLocation;
	public String traits;
	public double x;
	public double y;
	public boolean hasBeenOrchestrated;
	public boolean deferScriptedActivityPresentation;
	public double stressLevel;
	public double playerStress;
	public List<String> carriedItemNames = new ArrayList<>();
	public Map<String, InventoryItem> typedInventory = new LinkedHashMap<>();
	public List<SavedMemory> memories = new ArrayList<>();
	public List<AgentAction> pendingActions = new ArrayList<>();
    }

    public static class SavedMemory {
	public String type;
	public String description;
	public int importance;
	public LocalDateTime time;
	public String planType;
	public boolean reactable;
    }

    public static class SavedConversation {
	public String talker;
	public String talkee;
	public List<SavedDialog> dialog = new ArrayList<>();
    }

    public static class SavedDialog {
	public String name;
	public String message;
    }
}
