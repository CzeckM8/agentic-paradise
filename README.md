# Agentic Paradise

Agentic Paradise is a 2D Godot + Java simulation project with autonomous NPC behavior, memory-driven agent logic, and turn-based world updates.

Based on [nmater1/smallville](https://github.com/nmater1/smallville) (Apache 2.0).

## Current Project Layout

- `smallville` - Java 21 backend API and simulation runtime
- `godot-client` - Godot client used for interactive gameplay/demo
- `dashboard` - optional web dashboard module
- `javascript-client` - optional JavaScript client library
- `java-client` - optional Java client module
- `docs` - project and implementation documentation

## Prerequisites

- Java 21
- Maven 3.8+
- Godot 4.x

## Configuration

Copy `.env.example` to `.env` and set values as needed:

```env
GOOGLE_AI_API_KEY=your_google_ai_api_key_here
LLM_PROVIDER=google_ai
LLM_MODEL=gemini-2.5-flash-lite
SERVER_PORT=8080
```

## Run the Project

### Backend (recommended on Windows)

From repository root:

```bat
start_server.bat
```

This starts the backend from `smallville`, loads `.env` values, and writes logs to `smallville/logs/runtime_latest.log`.

### Godot Client

1. Open `godot-client/project.godot` in Godot.
2. Run the project.
3. Ensure backend is running and reachable on the configured port.

## Core API Endpoints

- `GET /ping`
- `GET /state`
- `POST /turn`
- `POST /player/actions`
- `GET /agents`
- `GET /agents/{name}`
- `GET /locations`

For additional endpoint behavior and implementation notes, see docs in the `docs` directory.

## Final Deliverable Docs

- [Deployment Documentation](docs/final-deliverables/DEPLOYMENT_DOCUMENTATION.md)
- [User Manual](docs/final-deliverables/USER_MANUAL.md)
- [UAT Report](docs/final-deliverables/UAT_REPORT.md)

## Credits

- Original base project: [nmater1/smallville](https://github.com/nmater1/smallville) (Apache 2.0)
- This repository is a derived, independent student project.
