# User Acceptance Testing (UAT) Report

## Project
Agentic Paradise

## Team
Team 6

## Report Scope
This report covers User Acceptance Testing for the current integrated build (backend + Godot client workflow).

## 1) UAT Objective

Validate that the delivered build supports the expected end-user flow:
- launch backend,
- launch client,
- move and perform player actions,
- observe responsive world/NPC updates.

## 2) Test Environment

- OS: Windows 10/11 (team demo environment)
- Backend: Java/Maven runtime from `smallville`
- Client: Godot project in `godot-client`
- Configuration: `.env` with Google AI provider settings

## 3) UAT Scenarios and Results

| ID | Scenario | Expected Result | Actual Result | Status |
|---|---|---|---|---|
| UAT-01 | Start backend with provided startup flow | Backend starts and exposes API endpoints | Backend starts via `start_server.bat`; endpoints respond | Pass |
| UAT-02 | Launch Godot client and connect to backend | Client loads world state from backend | Client loads world and initializes simulation state | Pass |
| UAT-03 | Player movement across world | Movement is visible and synchronized with backend turn flow | Movement works and turn processing updates world state | Pass |
| UAT-04 | Player action dispatch (speak/interact/attack) | Actions are accepted and reflected in simulation | Actions enqueue and process through turn endpoint | Pass |
| UAT-05 | NPC response after player actions | NPCs react according to simulation/runtime rules | NPC reactions observed; behavior updates visible | Pass |

## 4) Acceptance Criteria

The build is accepted when:
1. Core startup flow works (backend + client).
2. Player movement/actions work in-session.
3. NPC behavior updates are visible after turn processing.
4. No blocking runtime errors prevent demo use.

**Result:** Accepted for final project delivery and demonstration.

## 5) Issues / Limitations Observed

- LLM-backed responses can introduce variable latency.
- Behavior quality depends on model choice and API availability.
- Deployment workflow is currently optimized for local demo/development, not production hosting.

## 6) Recommendations

- Continue refining NPC behavior quality through prompt/runtime tuning.
- Add repeatable scripted UAT checklist runs before demos.
- Expand formal coverage with additional automated integration/system tests over time.
