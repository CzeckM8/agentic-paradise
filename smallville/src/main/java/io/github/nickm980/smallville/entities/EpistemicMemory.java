package io.github.nickm980.smallville.entities;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * What a specific NPC believes to be true about the world.
 *
 * Populated exclusively from:
 *   1. ObservedEvents — events where this agent appeared in ChronicleEvent.witnessIds
 *   2. Hearsay        — things spoken to this agent by another actor via Speak actions
 *   3. BeliefCorrections — written by ActionResolver on RESOLVER_REJECT
 *
 * NEVER populated directly from the Chronicle. The gap between Chronicle
 * truth and EpistemicMemory belief is the mechanism of gossip, deception,
 * surprise, and emergent social dynamics.
 *
 * LLM prompts for this agent read ONLY from this store.
 */
public class EpistemicMemory {

    private static final int MAX_OBSERVED = 200;
    private static final int MAX_HEARSAY = 100;
    private static final int MAX_CORRECTIONS = 50;

    // ── Events this agent directly witnessed ─────────────────────────────────

    /** A Chronicle event that this agent was a witness to. */
    public static class ObservedEvent {
        public final String chronicleEventId;
        public final int turnNumber;
        public final String actorId;
        public final String verb;
        public final String targetId;
        public final String payload;
        public final double actorX, actorY;
        public final LocalDateTime observedAt;

        public ObservedEvent(ChronicleEvent source) {
            this.chronicleEventId = source.getId();
            this.turnNumber = source.getTurnNumber();
            this.actorId = source.getActorId();
            this.verb = source.getVerb();
            this.targetId = source.getTargetId();
            this.payload = source.getPayload();
            this.actorX = source.getActorX();
            this.actorY = source.getActorY();
            this.observedAt = LocalDateTime.now();
        }

        public ObservedEvent(String chronicleEventId, int turnNumber, String actorId,
                String verb, String targetId, String payload, double actorX,
                double actorY, LocalDateTime observedAt) {
            this.chronicleEventId = chronicleEventId;
            this.turnNumber = turnNumber;
            this.actorId = actorId;
            this.verb = verb;
            this.targetId = targetId;
            this.payload = payload;
            this.actorX = actorX;
            this.actorY = actorY;
            this.observedAt = observedAt;
        }

        /** Narrative description for LLM prompt injection. */
        public String toNarrative() {
            String base = String.format("(turn %d) %s %s %s", turnNumber, actorId, verb, targetId);
            if (payload != null && !payload.isBlank()) base += ": \"" + payload + "\"";
            return base;
        }
    }

    // ── Things told to this agent (may be false) ─────────────────────────────

    /** Information received via a Speak action — may be inaccurate or fabricated. */
    public static class Hearsay {
        public final String id;
        public final String sourceActorId;  // who said it
        public final String content;        // what they said
        public final int turnNumber;
        public final LocalDateTime receivedAt;
        /** Confidence this agent places in the hearsay (0.0–1.0). */
        public double confidence;

        public Hearsay(String sourceActorId, String content, int turnNumber, double confidence) {
            this.id = UUID.randomUUID().toString();
            this.sourceActorId = sourceActorId;
            this.content = content;
            this.turnNumber = turnNumber;
            this.confidence = confidence;
            this.receivedAt = LocalDateTime.now();
        }

        public Hearsay(String id, String sourceActorId, String content, int turnNumber,
                double confidence, LocalDateTime receivedAt) {
            this.id = id;
            this.sourceActorId = sourceActorId;
            this.content = content;
            this.turnNumber = turnNumber;
            this.confidence = confidence;
            this.receivedAt = receivedAt;
        }

        public String toNarrative() {
            return String.format("(turn %d) %s told me: \"%s\" [confidence %.0f%%]",
                    turnNumber, sourceActorId, content, confidence * 100);
        }
    }

    // ── Corrections written by ActionResolver on RESOLVER_REJECT ─────────────

    /**
     * Written when ActionResolver rejects this agent's WorldAction.
     * Captures the gap between what the agent believed and what reality showed.
     * Included as reflection payload in the immediate PlanReplace cognition job.
     */
    public static class BeliefCorrection {
        public final String id;
        public final int turnNumber;
        public final String attemptedVerb;
        public final String targetId;
        public final RejectReason rejectReason;
        /** What the agent was implicitly assuming when they attempted the action. */
        public final String believed;
        /** What ActionResolver found to be true instead. */
        public final String reality;
        public final LocalDateTime createdAt;

        public BeliefCorrection(int turnNumber, String attemptedVerb, String targetId,
                                RejectReason rejectReason,
                                String believed, String reality) {
            this.id = UUID.randomUUID().toString();
            this.turnNumber = turnNumber;
            this.attemptedVerb = attemptedVerb;
            this.targetId = targetId;
            this.rejectReason = rejectReason;
            this.believed = believed;
            this.reality = reality;
            this.createdAt = LocalDateTime.now();
        }

        public BeliefCorrection(String id, int turnNumber, String attemptedVerb, String targetId,
                                RejectReason rejectReason, String believed, String reality,
                                LocalDateTime createdAt) {
            this.id = id;
            this.turnNumber = turnNumber;
            this.attemptedVerb = attemptedVerb;
            this.targetId = targetId;
            this.rejectReason = rejectReason;
            this.believed = believed;
            this.reality = reality;
            this.createdAt = createdAt;
        }

        /** Narrative for LLM reflection prompt. */
        public String toReflectionPrompt() {
            return String.format(
                "I tried to %s '%s' but was blocked (%s). " +
                "I believed: %s. Reality: %s. " +
                "Why might this have happened, and what should I do instead?",
                attemptedVerb, targetId, rejectReason, believed, reality);
        }
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    private final Deque<ObservedEvent> observed = new ArrayDeque<>();
    private final Deque<Hearsay> hearsay = new ArrayDeque<>();
    private final Deque<BeliefCorrection> corrections = new ArrayDeque<>();

    public static class Snapshot {
        public List<ObservedEventSnapshot> observed = new ArrayList<>();
        public List<HearsaySnapshot> hearsay = new ArrayList<>();
        public List<BeliefCorrectionSnapshot> corrections = new ArrayList<>();
    }

    public static class ObservedEventSnapshot {
        public String chronicleEventId;
        public int turnNumber;
        public String actorId;
        public String verb;
        public String targetId;
        public String payload;
        public double actorX;
        public double actorY;
        public LocalDateTime observedAt;
    }

    public static class HearsaySnapshot {
        public String id;
        public String sourceActorId;
        public String content;
        public int turnNumber;
        public LocalDateTime receivedAt;
        public double confidence;
    }

    public static class BeliefCorrectionSnapshot {
        public String id;
        public int turnNumber;
        public String attemptedVerb;
        public String targetId;
        public RejectReason rejectReason;
        public String believed;
        public String reality;
        public LocalDateTime createdAt;
    }

    // ── Ingest methods (called by PerceptionChannel / ActionResolver) ─────────

    public void ingestObserved(ChronicleEvent event) {
        observed.addLast(new ObservedEvent(event));
        if (observed.size() > MAX_OBSERVED) observed.pollFirst();
    }

    public void ingestHearsay(String sourceActorId, String content, int turnNumber,
                              double confidence) {
        hearsay.addLast(new Hearsay(sourceActorId, content, turnNumber, confidence));
        if (hearsay.size() > MAX_HEARSAY) hearsay.pollFirst();
    }

    public void ingestBeliefCorrection(int turnNumber, String attemptedVerb, String targetId,
                                       RejectReason reason,
                                       String believed, String reality) {
        corrections.addLast(new BeliefCorrection(
                turnNumber, attemptedVerb, targetId, reason, believed, reality));
        if (corrections.size() > MAX_CORRECTIONS) corrections.pollFirst();
    }

    // ── Retrieval for LLM prompt builder ─────────────────────────────────────

    /** Most recent N observed events, newest first. */
    public List<ObservedEvent> recentObserved(int n) {
        List<ObservedEvent> list = new ArrayList<>(observed);
        Collections.reverse(list);
        return list.subList(0, Math.min(n, list.size()));
    }

    /** Most recent N hearsay entries, newest first. */
    public List<Hearsay> recentHearsay(int n) {
        List<Hearsay> list = new ArrayList<>(hearsay);
        Collections.reverse(list);
        return list.subList(0, Math.min(n, list.size()));
    }

    /** Most recent unresolved BeliefCorrection, or null if none. */
    public BeliefCorrection latestCorrection() {
        if (corrections.isEmpty()) return null;
        List<BeliefCorrection> list = new ArrayList<>(corrections);
        return list.get(list.size() - 1);
    }

    /** True if there is at least one BeliefCorrection not yet reflected upon. */
    public boolean hasPendingCorrection() {
        return !corrections.isEmpty();
    }

    public void clearCorrections() {
        corrections.clear();
    }

    public Snapshot snapshot() {
        Snapshot snapshot = new Snapshot();
        for (ObservedEvent event : observed) {
            ObservedEventSnapshot item = new ObservedEventSnapshot();
            item.chronicleEventId = event.chronicleEventId;
            item.turnNumber = event.turnNumber;
            item.actorId = event.actorId;
            item.verb = event.verb;
            item.targetId = event.targetId;
            item.payload = event.payload;
            item.actorX = event.actorX;
            item.actorY = event.actorY;
            item.observedAt = event.observedAt;
            snapshot.observed.add(item);
        }
        for (Hearsay entry : hearsay) {
            HearsaySnapshot item = new HearsaySnapshot();
            item.id = entry.id;
            item.sourceActorId = entry.sourceActorId;
            item.content = entry.content;
            item.turnNumber = entry.turnNumber;
            item.receivedAt = entry.receivedAt;
            item.confidence = entry.confidence;
            snapshot.hearsay.add(item);
        }
        for (BeliefCorrection correction : corrections) {
            BeliefCorrectionSnapshot item = new BeliefCorrectionSnapshot();
            item.id = correction.id;
            item.turnNumber = correction.turnNumber;
            item.attemptedVerb = correction.attemptedVerb;
            item.targetId = correction.targetId;
            item.rejectReason = correction.rejectReason;
            item.believed = correction.believed;
            item.reality = correction.reality;
            item.createdAt = correction.createdAt;
            snapshot.corrections.add(item);
        }
        return snapshot;
    }

    public void restore(Snapshot snapshot) {
        observed.clear();
        hearsay.clear();
        corrections.clear();
        if (snapshot == null) {
            return;
        }
        if (snapshot.observed != null) {
            for (ObservedEventSnapshot item : snapshot.observed) {
                observed.addLast(new ObservedEvent(
                        item.chronicleEventId,
                        item.turnNumber,
                        item.actorId,
                        item.verb,
                        item.targetId,
                        item.payload,
                        item.actorX,
                        item.actorY,
                        item.observedAt));
            }
        }
        if (snapshot.hearsay != null) {
            for (HearsaySnapshot item : snapshot.hearsay) {
                hearsay.addLast(new Hearsay(
                        item.id,
                        item.sourceActorId,
                        item.content,
                        item.turnNumber,
                        item.confidence,
                        item.receivedAt));
            }
        }
        if (snapshot.corrections != null) {
            for (BeliefCorrectionSnapshot item : snapshot.corrections) {
                corrections.addLast(new BeliefCorrection(
                        item.id,
                        item.turnNumber,
                        item.attemptedVerb,
                        item.targetId,
                        item.rejectReason,
                        item.believed,
                        item.reality,
                        item.createdAt));
            }
        }
    }

    public int observedCount() { return observed.size(); }
    public int hearsayCount() { return hearsay.size(); }
    public int correctionCount() { return corrections.size(); }
}
