package io.github.nickm980.smallville.memory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import io.github.nickm980.smallville.entities.SimulationTime;
import io.github.nickm980.smallville.math.SmallvilleMath;

/**
 * A daily commitment represents a named activity with an explicit map location,
 * time window, and lifecycle state. Commitments are structured (created from
 * JSON LLM output at day-start) and are the primary driver of NPC daily
 * behavior, replacing the fragile full-schedule text approach.
 */
public class Commitment extends Plan {

    private final LocalDateTime endTime;
    private final String location;   // Resolved map location full path
    private final int priority;      // 1=LOW, 5=MEDIUM, 10=HIGH
    private CommitmentStatus status;
    private final String goal;

    public Commitment(String goal, String location, LocalDateTime start, LocalDateTime end, int priority) {
        super(buildDescription(goal, location, start, end), start, PlanType.COMMITMENT);
        this.goal = goal;
        this.location = location;
        this.endTime = end;
        this.priority = priority;
        this.status = CommitmentStatus.PENDING;
    }

    private static String buildDescription(String goal, String location, LocalDateTime start, LocalDateTime end) {
        return String.format("[%s-%s] %s at %s",
                start.toLocalTime(), end.toLocalTime(), goal, location);
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public String getLocation() {
        return location;
    }

    public int getPriority() {
        return priority;
    }

    public CommitmentStatus getStatus() {
        return status;
    }

    public String getGoal() {
        return goal;
    }

    public void setStatus(CommitmentStatus status) {
        this.status = status;
    }

    /** True if this commitment's window includes the given simulation time. */
    public boolean isActiveAt(LocalDateTime time) {
        return !time.isBefore(getTime()) && !time.isAfter(endTime);
    }

    /** True if the commitment's end time has fully passed. */
    public boolean isExpired(LocalDateTime time) {
        return time.isAfter(endTime);
    }

    @Override
    double getRecency() {
        var now = SimulationTime.now();
        var a = ChronoUnit.SECONDS.between(getTime(), SimulationTime.startedAt());
        var b = ChronoUnit.SECONDS.between(now, getTime());
        var timeSinceStart = ChronoUnit.SECONDS.between(now, SimulationTime.startedAt());
        return SmallvilleMath.normalize(SmallvilleMath.decay(a, b), timeSinceStart, 0);
    }
}
