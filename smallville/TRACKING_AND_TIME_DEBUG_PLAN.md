# Tracking And Time Debug Plan

## Goals
- Reproduce and fix boundary freeze when an NPC moves between locations.
- Verify simulation time advances every turn by configured timestep.
- Ensure day-boundary behavior is deterministic and observable.

## Active Instrumentation
- Tracked agent: `John` (constant in simulation runtime).
- Orchestration checkpoints logged each turn:
  - `start-turn`
  - `after-day-start-refresh`
  - `after-reactive-fallback`
  - `after-deterministic-catchup`
  - `after-movement`
- Boundary/path diagnostics:
  - `arrival-at-boundary`
  - `entered-target-bounds`
  - `blocked-candidate`
  - `all-direct-candidates-blocked, routine-fallback`

## Log Prefixes To Filter
- `[Track:John]`
- `[Commitments]`
- `[Plans]`
- `[Activity]`
- `[Reactive]`

## Turn-Time Invariants
- `/turn` must always advance simulation time, even if action queue is empty.
- `/turn`, `/state`, and `/state/delta` should include simulation `time`.
- HUD should display simulation `time` from backend payload, never system clock.

## Day-Boundary Invariants
- New world starts at `12:00 PM` simulation time.
- Reflection should run at end-of-day (`23:59`) with midnight-cross fallback.
- New-day routine/commitments should refresh after day boundary crossing.

## Repro Script
1. Start server and client.
2. Create a new world and verify HUD starts at `12:00 PM`.
3. Issue repeated move turns and verify time increments each turn.
4. Route `John` across a location boundary and monitor `[Track:John]` logs.
5. Confirm target clears after entering destination bounds.
6. Continue to day boundary and verify reflection/refresh phases in logs.

## Success Criteria
- No multi-hour freeze at boundaries for tracked agent.
- Time increments every processed turn.
- Deterministic day rollover: reflection and next-day planning visible in logs.
