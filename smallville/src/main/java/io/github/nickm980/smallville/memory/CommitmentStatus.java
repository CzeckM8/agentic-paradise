package io.github.nickm980.smallville.memory;

public enum CommitmentStatus {
    PENDING,    // Start time hasn't arrived yet
    ACTIVE,     // Currently in-progress
    COMPLETED,  // End time has passed or explicitly finished
    DEFERRED    // Could not be executed due to interruption
}
