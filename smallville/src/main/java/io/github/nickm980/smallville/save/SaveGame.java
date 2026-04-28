package io.github.nickm980.smallville.save;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.nickm980.smallville.api.v1.dto.PlayerActionRequest;
import io.github.nickm980.smallville.entities.AgentAction;
import io.github.nickm980.smallville.entities.ChronicleEvent;
import io.github.nickm980.smallville.entities.EpistemicMemory;
import io.github.nickm980.smallville.entities.InventoryItem;
import io.github.nickm980.smallville.entities.WorldObjectInstance;

public class SaveGame {
    public int schemaVersion = 2;
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
    public Map<String, SavedRuntimeAgentState> runtimeStateByAgent = new LinkedHashMap<>();
    public Map<String, List<SavedReactiveEvent>> reactiveEventsByAgent = new LinkedHashMap<>();
    public Map<String, List<SavedCommittedAction>> committedActionsByAgent = new LinkedHashMap<>();
    public Map<String, SavedAgenticRuntimeState> agenticStateByAgent = new LinkedHashMap<>();
    public Map<String, Map<String, List<SavedSocialEpisode>>> socialEpisodesByAgent = new LinkedHashMap<>();
    public Map<String, List<SavedConversationTurn>> conversationTurnsByPair = new LinkedHashMap<>();
    public List<ChronicleEvent> chronicle = new ArrayList<>();

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
	public String lastActivity;
	public String emoji;
	public String location;
	public String targetLocation;
	public String traits;
	public double x;
	public double y;
	public boolean hasBeenOrchestrated;
	public boolean deferScriptedActivityPresentation;
	public double stressLevel;
	public String mentalState;
	public double playerStress;
	public int playerNumInteractions;
	public List<String> carriedItemNames = new ArrayList<>();
	public Map<String, InventoryItem> typedInventory = new LinkedHashMap<>();
	public List<SavedMemory> memories = new ArrayList<>();
	public AgentAction activeAction;
	public List<AgentAction> queuedActions = new ArrayList<>();
	public EpistemicMemory.Snapshot epistemicMemory;
    }

    public static class SavedMemory {
	public String type;
	public String description;
	public int importance;
	public LocalDateTime time;
	public String planType;
	public boolean reactable;
	public LocalDateTime endTime;
	public String commitmentLocation;
	public int commitmentPriority;
	public String commitmentStatus;
	public String commitmentGoal;
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

    public static class SavedRuntimeAgentState {
	public java.time.LocalDate lastRoutineDate;
	public java.time.LocalDate lastReflectionDate;
	public LocalDateTime lastLlmCallAt;
	public LocalDateTime lastOrchestratedAt;
	public boolean lastAware;
	public String lastTraceActivity;
	public String lastTraceLocation;
	public String lastTraceTarget;
	public Double lastTraceX;
	public Double lastTraceY;
	public LocalDateTime lastTraceLoggedAt;
    }

    public static class SavedReactiveEvent {
	public String description;
	public int severity;
	public LocalDateTime createdAt;
	public boolean playerInvolved;
    }

    public static class SavedCommittedAction {
	public String action;
	public String reason;
	public String location;
	public double x;
	public double y;
	public LocalDateTime createdAt;
    }

    public static class SavedKnowledgeEntry {
	public List<String> values = new ArrayList<>();
	public double confidence;
	public LocalDateTime updatedAt;
	public String source;
    }

    public static class SavedAgenticGoal {
	public String type;
	public String targetId;
	public String targetType;
	public boolean targetIsMobile;
	public double snapshotX;
	public double snapshotY;
	public String snapshotLocation;
	public String topic;
	public String opener;
	public String description;
	public double priority;
	public String actionType;
	public String actionDescription;
	public String actionFlair;
    }

    public static class SavedAgenticRuntimeState {
	public String phase;
	public SavedAgenticGoal activeGoal;
	public LocalDateTime phaseUpdatedAt;
	public LocalDateTime cooldownUntil;
	public Map<String, SavedKnowledgeEntry> knowledge = new LinkedHashMap<>();
	public boolean chatWindowClosedObserved;
	public boolean pinnedLastTurn;
	public int deferredTurns;
	public int recentIgnoreCount;
	public double socialFriction;
	public double lastInitiativeScore;
	public String lastOutcome;
	public LocalDateTime lastInitiatedAt;
	public LocalDateTime lastRepliedAt;
	public String lastError;
    }

    public static class SavedSocialEpisode {
	public String target;
	public String outcome;
	public String topic;
	public String playerReply;
	public String summary;
	public LocalDateTime createdAt;
    }

    public static class SavedConversationTurn {
	public String speaker;
	public String listener;
	public String text;
	public LocalDateTime createdAt;
    }
}
