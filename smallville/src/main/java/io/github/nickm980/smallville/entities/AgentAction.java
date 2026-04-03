package io.github.nickm980.smallville.entities;

import java.util.UUID;

public class AgentAction {
    private String id = UUID.randomUUID().toString();
    private String type;
    private String description;
    private String emoji;
    private String targetLocation;
    private String targetAgent;
    private String item;
    private String speakText;
    private Double targetX;
    private Double targetY;
    private String status = "queued";

    public AgentAction() {
    }

    public AgentAction(String type, String description) {
        this.type = type;
        this.description = description;
    }

    public AgentAction copy() {
        AgentAction copy = new AgentAction();
        copy.id = id;
        copy.type = type;
        copy.description = description;
        copy.emoji = emoji;
        copy.targetLocation = targetLocation;
        copy.targetAgent = targetAgent;
        copy.item = item;
        copy.speakText = speakText;
        copy.targetX = targetX;
        copy.targetY = targetY;
        copy.status = status;
        return copy;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEmoji() {
        return emoji;
    }

    public void setEmoji(String emoji) {
        this.emoji = emoji;
    }

    public String getTargetLocation() {
        return targetLocation;
    }

    public void setTargetLocation(String targetLocation) {
        this.targetLocation = targetLocation;
    }

    public String getTargetAgent() {
        return targetAgent;
    }

    public void setTargetAgent(String targetAgent) {
        this.targetAgent = targetAgent;
    }

    public String getItem() {
        return item;
    }

    public void setItem(String item) {
        this.item = item;
    }

    public String getSpeakText() {
        return speakText;
    }

    public void setSpeakText(String speakText) {
        this.speakText = speakText;
    }

    public Double getTargetX() {
        return targetX;
    }

    public void setTargetX(Double targetX) {
        this.targetX = targetX;
    }

    public Double getTargetY() {
        return targetY;
    }

    public void setTargetY(Double targetY) {
        this.targetY = targetY;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
