package io.github.nickm980.smallville.entities;

/**
 * Reason codes returned by ActionResolver when a WorldAction is rejected.
 *
 * Written to EpistemicMemory as a BeliefCorrection when an NPC's action fails,
 * triggering an immediate PlanReplace cognition job so the NPC can reflect on
 * why its belief about the world was wrong.
 */
public enum RejectReason {
    /** Actor holds no item with "opens:<instanceId>" grant for a locked target. */
    MISSING_KEY,
    /** Actor holds no item with the required generic grant for this verb. */
    MISSING_GRANT,
    /** Target is not within required tile range. */
    OUT_OF_RANGE,
    /** Target's current state does not permit the verb. */
    STATE_INCOMPATIBLE,
    /** Two actors claimed the same target this turn; this actor lost priority. */
    CONFLICT_LOST,
    /** Target object or entity does not exist. */
    TARGET_NOT_FOUND,
    /** Target's affordance properties do not permit this verb. */
    AFFORDANCE_DENIED
}
