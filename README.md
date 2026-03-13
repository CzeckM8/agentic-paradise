# Agentic Paradise

**A 2D Godot + LLM-powered emergent agent simulation**  
Based on: [nmater1/smallville](https://github.com/nmater1/smallville) (Apache 2.0)

## Features
- Local Ollama (gemma3: 4b) or OpenAI backend
- Custom memory-driven prompts
- Real-time `/state` sync with Godot
- 30 FPS reactive NPC behavior

## Setup
mvn clean package
java -jar target/smallville-1.3.0.jar --port 8080

If the server requires an API key in your local config, run:
`java -jar target/smallville-1.3.0.jar --api-key o --port 8080`

## Architecture
- `GET /{x}/{y}`
	- Returns location + nearby entities/objects at coordinate.
- `GET /{x}/{y}/location`
	- Returns location name containing coordinate.
- `GET /{x}/{y}/objects`
	- Returns agents/player/object instances near coordinate.

- `GET /agents`
- `GET /agents/{name}`
- `GET /agents/{name}/memories/summary`
- `GET /agents/{name}/memories/recent?limit=20`
- `GET /agents/{name}/memories/{index}`

- `POST /player`
- `GET /player/{name}`
- `POST /player/actions`
- `GET /player/{name}/actions?limit=20`

- `POST /actions`
	- Backward-compatible player action enqueue endpoint.
- `POST /turn`
	- Processes one queued action and advances world state.

- `POST /objects/types/{type}`
- `GET /objects/types`
- `GET /objects/types/{type}`
- `POST /objects/{id}`
- `GET /objects/{id}`
- `GET /objects`

- `GET /state`
- `POST /state`
- `GET /state/delta`
- `GET /locations`
- `POST /locations`

- `GET /llm/policy`
	- Returns stable/situational/avoid call categories.
- `GET /llm/latency-budget`
	- Returns target latency ranges for hosted model operation.

- `POST /runtime/orchestrate`
	- Runs one orchestration pass with optional player-awareness context.
- `GET /runtime/pending-events`
	- Returns queued reactive events waiting for agent processing.

## LLM Call Policy
- Stable calls:
	- Start-of-day reflection + routine generation.
	- Agent creation worldview/philosophy generation.
	- Reactions to extenuating/high-severity events.
- Situational calls:
	- Direct player-agent conversation.
	- Overheard/eavesdropped relevant conversation.
	- Witnessed high-impact events.
	- Meaningful player interaction beyond trivial collisions.
- Calls to avoid:
	- Player movement steps that do not interrupt agents.
	- Trivial inanimate-object interactions without consequence.
	- Offscreen agents without unresolved significant conflict.
- Offscreen behavior:
	- Run deterministic catch-up and persist only resulting state effects.
	- Surface outcomes through changed behavior/context, not pop-up narration.

## Runtime Orchestration
- Turn processing (`POST /turn`) now routes through runtime orchestration with player context.
- Runtime pass per agent:
	- Day-start or forced pass: LLM update for routine/reflection planning.
	- Reactive events: high severity triggers LLM reaction; lower severity uses deterministic fallback.
	- Offscreen/no-event: deterministic catch-up (routine-following + stress decay + continuity movement).
- Reactive triggers currently seeded by:
	- `attack`/`interact` actions (target + bystanders in same location).
	- `speak` actions (nearby listeners can overhear).

## Godot Client Sync (Basic)
- `godot-client/backend_connector.gd`
	- On startup, checks `GET /ping`; if unavailable and `auto_start_backend = true`, it runs `start_server.bat` automatically.
	- Initializes top-level locations (`market`, `tavern`, `coffee_shop`, `town_square`, `home`) using `name + type` metadata.
	- Uses `POST /player/actions` for action enqueue.
	- Sends runtime context to `POST /turn` with:
		- `playerX`
		- `playerY`
		- `awarenessRadius`
		- `forceDayStart`
	- Updates the local player node from turn response payloads.
- `godot-client/camera.gd`
	- Resolves the live player node at `World/Agents/Player` (runtime instance), not a scene file path.
	- Retries player lookup on a cooldown to avoid timer spam.
- `godot-client/player.gd`
	- WASD movement remains local per-frame for responsiveness.
	- Movement now uses world bounds (not per-location clamps), so walking can cross location coordinate boundaries.
	- Movement is throttled to server turns (0.35s cadence, 20px minimum travel).
	- Each movement sync enqueues `actionType: move` with:
		- `targetLocation` (resolved from loaded location bounds)
		- current world-space `playerX` / `playerY`
	- Interaction/speak/attack now send real player coordinates instead of location-center defaults.

## Turn-Step Behavior
- Each player movement sync processes one turn.
- Agent destinations are now treated as intent, not instant relocation.
- On each processed turn, each NPC advances at most one tile (32 units) toward its target with occupancy checks, so movement stays lockstep with player turns and does not teleport.

## Smooth Movement Notes
- Player movement is not a `DELETE -> POST` transfer between coordinate endpoints.
- The client keeps a local player for immediate responsiveness (client-side prediction).
- Server authority is reconciled on each processed turn using queued `move` actions (`playerX`, `playerY`, `targetLocation`).
- To reduce hiccups under network/server latency, move actions are coalesced client-side: while one turn is in flight, only the latest move is kept and sent after turn completion.

## Latency Budget (Hosted Models)

| Call Type | Target P95 | Input Budget | Notes |
|---|---:|---:|---|
| Reaction | 2-6s | 800-2,000 tokens | Keep event-focused prompt compact |
| Player Dialogue | 2-8s | 1,200-3,000 tokens | Use memory summaries, not full logs |
| Daily Reflection | 6-15s | 3,000-9,000 tokens | Run async at day boundary |
| Daily Routine Generation | 4-12s | 2,000-6,000 tokens | Cache stable profile/context |
| Offscreen Conflict Reconcile | 8-20s | 4,000-12,000 tokens | Batch only unresolved high-severity conflicts |

## Credits
**Based on:** [nmater1/smallville](https://github.com/nmater1/smallville) (Apache 2.0)  
**This is a derived, independent project.** We are not affiliated with the original.
