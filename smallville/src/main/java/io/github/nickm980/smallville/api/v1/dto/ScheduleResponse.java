package io.github.nickm980.smallville.api.v1.dto;

import java.time.LocalDateTime;

public class ScheduleResponse {
    private String description;
    private LocalDateTime time;
    private String type; // SHORT_TERM, MID_TERM, LONG_TERM, COMMITMENT

    public ScheduleResponse(String description, LocalDateTime time, String type) {
        this.description = description;
        this.time = time;
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getTime() {
        return time;
    }

    public void setTime(LocalDateTime time) {
        this.time = time;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
