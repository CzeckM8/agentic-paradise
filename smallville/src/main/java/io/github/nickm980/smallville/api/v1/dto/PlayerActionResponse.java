package io.github.nickm980.smallville.api.v1.dto;

import java.util.List;

public class PlayerActionResponse {
    private boolean success;
    private String actionId;
    private AgentStateResponse playerState;  // Updated player agent state
    private AgentStateResponse targetAgentState;  // If interaction occurred
    private List<AgentStateResponse> affectedAgents;  // Other agents affected by action
    private String result;  // Description of what happened
    private double stressChange;  // How much player stress changed

    public PlayerActionResponse(boolean success, String result) {
        this.success = success;
        this.result = result;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String actionId) {
        this.actionId = actionId;
    }

    public AgentStateResponse getPlayerState() {
        return playerState;
    }

    public void setPlayerState(AgentStateResponse playerState) {
        this.playerState = playerState;
    }

    public AgentStateResponse getTargetAgentState() {
        return targetAgentState;
    }

    public void setTargetAgentState(AgentStateResponse targetAgentState) {
        this.targetAgentState = targetAgentState;
    }

    public List<AgentStateResponse> getAffectedAgents() {
        return affectedAgents;
    }

    public void setAffectedAgents(List<AgentStateResponse> affectedAgents) {
        this.affectedAgents = affectedAgents;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public double getStressChange() {
        return stressChange;
    }

    public void setStressChange(double stressChange) {
        this.stressChange = stressChange;
    }
}
