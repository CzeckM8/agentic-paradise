# Deployment Documentation

This document describes how to deploy and run the current `Agentic Paradise` project locally for demonstration and testing.

## 1) System Requirements

### Required
- Windows 10/11, macOS, or Linux
- Java 21 (required by the backend `smallville` module)
- Maven 3.8+
- Godot 4.x (to run the game client in `godot-client`)

### Optional
- Google AI Studio API key (recommended for LLM-backed behavior)
- Node.js 18+ (only needed for the optional `dashboard` and `javascript-client` modules)

## 2) Repository Setup

1. Clone the repository.
2. Create a local environment file from `.env.example`:
   - Copy `.env.example` to `.env`
   - Set:
     - `GOOGLE_AI_API_KEY`
     - `LLM_PROVIDER` (default `google_ai`)
     - `LLM_MODEL` (optional override)
     - `SERVER_PORT` (default `8080`)

## 3) Backend Deployment (Primary Runtime)

The backend lives in `smallville`.

### Option A: Use the project starter script (recommended on Windows)
1. From the repository root, run:
   - `start_server.bat`
2. The script:
   - loads `.env` values,
   - checks/clears stale processes on the configured port,
   - starts the backend with Maven,
   - writes logs to `smallville/logs/runtime_latest.log`.

### Option B: Start manually with Maven
1. Open a terminal in `smallville`.
2. Run:
   - `mvn clean package`
   - `mvn -q -DskipTests exec:java -Dexec.mainClass=io.github.nickm980.smallville.Smallville -Dexec.args="--api-key o --port 8080"`
3. Optional JVM properties:
   - `-Dgoogleai.api.key=...`
   - `-Dllm.provider=google_ai`
   - `-Dllm.model=<model_name>`

## 4) Client Deployment (Godot)

1. Open `godot-client/project.godot` in Godot 4.x.
2. Confirm backend host/port settings in `godot-client/backend_connector.gd` if needed.
3. Run the project from Godot.
4. If backend auto-start is enabled in the client, it attempts to use `start_server.bat` when `GET /ping` is unavailable.

## 5) Basic Deployment Verification

After backend startup, verify:
- `GET /ping` returns a successful response.
- `GET /state` returns world state JSON.
- Running the Godot client shows player and NPC updates over turn processing.

## 6) Configuration Notes

- Default server port is `8080` unless overridden in `.env`.
- LLM behavior depends on provider/model values in `.env`.
- Keep `.env` local and uncommitted.

## 7) Rollback / Recovery Plan

If deployment fails after config or runtime changes:
1. Stop running backend/client processes.
2. Restore known-good `.env` values (or regenerate from `.env.example`).
3. Remove stale Java process using the configured backend port.
4. Re-run `start_server.bat`.
5. If build artifacts appear corrupted, run `mvn clean package` in `smallville` and restart.

## 8) Known Deployment Constraints

- No containerized deployment (Docker/Kubernetes) is currently maintained.
- Production cloud deployment scripts are not currently provided.
- Primary supported workflow is local runtime for development/demo.
