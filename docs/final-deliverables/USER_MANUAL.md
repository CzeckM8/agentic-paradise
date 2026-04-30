# User Manual

This manual covers end-user operation of the current `Agentic Paradise` build.

## 1) What This Application Does

`Agentic Paradise` is a simulation where a player moves through a town and interacts with autonomous NPCs. NPC behavior is updated through turn processing and can react to player actions.

## 2) Before You Start

You need:
- Backend build artifacts available (see first-time setup below)
- Godot client opened from `godot-client/project.godot`
- First-time setup for fresh clones:
  - From `smallville`, run `mvn -DskipTests clean package` once to generate backend build artifacts in `smallville/target`.

## 3) Launch Steps

1. Create `.env` from `.env.example` and fill required values.
2. Generate backend artifact:
   - From `smallville`, run `mvn -DskipTests clean package`.
3. Open/import `godot-client/project.godot` in Godot 4.x.
4. Run the project and wait for initial world data to load.
5. The client launch flow starts `start_server.bat` automatically when backend is not already reachable.

## 4) Core Controls

- **Move:** Use `W`, `A`, `S`, `D`
- **Interact/Speak/Attack:** Use the in-game action UI controls (as configured in the current scene/UI)
- **Pause/Menu actions:** Use the available pause menu controls in the running client

## 5) Typical Gameplay Flow

1. Move to a location in the world.
2. Trigger an interaction or communication action.
3. The client sends actions to backend turn processing.
4. NPCs update behavior and movement based on simulation state.

## 6) Troubleshooting

### Backend not reachable
- Confirm the `godot-client` launch successfully triggered `start_server.bat`.
- Check whether port `8080` (or your configured port) is already in use.
- Review `smallville/logs/runtime_latest.log` for startup errors.

### Game opens but world does not update
- Confirm backend API is responding (`/ping`, `/state`).
- Stop the current run session and run the Godot project again so client startup can re-trigger backend auto-start.
- Verify `.env` values if LLM-backed calls appear to fail.

### Slow or delayed NPC behavior
- This can occur when LLM responses are slow.
- Use a lighter model in `.env` to improve responsiveness.

## 7) Best Practices

- Run only the `godot-client` launch flow; avoid manually running `start_server.bat` first.
- Keep `.env` aligned with your available API/provider credentials.
- Use stable network and power conditions during demos.

## 8) Current Scope Notes

- Main supported user path is local simulation execution.
- Optional modules (`dashboard`, `javascript-client`, `java-client`) are not required for core gameplay demo flow.
