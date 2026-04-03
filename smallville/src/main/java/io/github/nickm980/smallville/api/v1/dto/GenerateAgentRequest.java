package io.github.nickm980.smallville.api.v1.dto;

public class GenerateAgentRequest {
	private Integer count;
	private Boolean replaceExistingAgents;
	private Boolean trackFirstAgent;
	private Boolean enableRepairPass;
	private String preferredLocation;
	private String prompt;

	public Integer getCount() {
		return count;
	}

	public void setCount(Integer count) {
		this.count = count;
	}

	public Boolean getReplaceExistingAgents() {
		return replaceExistingAgents;
	}

	public void setReplaceExistingAgents(Boolean replaceExistingAgents) {
		this.replaceExistingAgents = replaceExistingAgents;
	}

	public Boolean getTrackFirstAgent() {
		return trackFirstAgent;
	}

	public void setTrackFirstAgent(Boolean trackFirstAgent) {
		this.trackFirstAgent = trackFirstAgent;
	}

	public Boolean getEnableRepairPass() {
		return enableRepairPass;
	}

	public void setEnableRepairPass(Boolean enableRepairPass) {
		this.enableRepairPass = enableRepairPass;
	}

	public String getPreferredLocation() {
		return preferredLocation;
	}

	public void setPreferredLocation(String preferredLocation) {
		this.preferredLocation = preferredLocation;
	}

	public String getPrompt() {
		return prompt;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}
}