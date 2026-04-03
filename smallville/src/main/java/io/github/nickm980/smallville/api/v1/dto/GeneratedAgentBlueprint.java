package io.github.nickm980.smallville.api.v1.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GeneratedAgentBlueprint {
	private String name;
	private String homeLocation;
	private String currentActivity;
	private List<String> coreTraits;
	private List<String> flaws;
	private List<String> memories;
	private String socialStyle;
	private Map<String, String> dailyAnchors;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getHomeLocation() {
		return homeLocation;
	}

	public void setHomeLocation(String homeLocation) {
		this.homeLocation = homeLocation;
	}

	public String getCurrentActivity() {
		return currentActivity;
	}

	public void setCurrentActivity(String currentActivity) {
		this.currentActivity = currentActivity;
	}

	public List<String> getCoreTraits() {
		return coreTraits;
	}

	public void setCoreTraits(List<String> coreTraits) {
		this.coreTraits = coreTraits;
	}

	public List<String> getFlaws() {
		return flaws;
	}

	public void setFlaws(List<String> flaws) {
		this.flaws = flaws;
	}

	public List<String> getMemories() {
		return memories;
	}

	public void setMemories(List<String> memories) {
		this.memories = memories;
	}

	public String getSocialStyle() {
		return socialStyle;
	}

	public void setSocialStyle(String socialStyle) {
		this.socialStyle = socialStyle;
	}

	public Map<String, String> getDailyAnchors() {
		return dailyAnchors;
	}

	public void setDailyAnchors(Map<String, String> dailyAnchors) {
		this.dailyAnchors = dailyAnchors;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("name", name);
		result.put("homeLocation", homeLocation);
		result.put("currentActivity", currentActivity);
		result.put("coreTraits", coreTraits);
		result.put("flaws", flaws);
		result.put("memories", memories);
		result.put("socialStyle", socialStyle);
		result.put("dailyAnchors", dailyAnchors);
		return result;
	}
}