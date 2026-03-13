# Turn-Based Changes to Smallville Server

## Overview
The Smallville server has been modified from an autonomous, time-driven simulation to a player-centric, turn-based system. In the original system, the simulation advanced automatically on every `POST /state` call, updating all agents. The new system requires explicit player actions to drive the simulation forward.

## Key Changes

### 1. Action Queue Implementation
- **File**: `SimulationService.java`
- **Change**: Added a `ConcurrentLinkedQueue<PlayerActionRequest>` to store pending player actions.
- **Impact**: Actions are no longer executed immediately when enqueued.

### 2. Modified `enqueuePlayerAction` Method
- **File**: `SimulationService.java`
- **Change**: Changed return type from `PlayerActionResponse` to `void`. The method now only validates and enqueues the action.
- **Old Behavior**: Executed the action immediately and returned the result.
- **New Behavior**: Validates the action and adds it to the queue for later processing.

### 3. New `processNextAction` Method
- **File**: `SimulationService.java`
- **Change**: Added a new method that dequeues the next action, executes it using the new `executeAction` private method, advances the simulation state, and returns the result.
- **Functionality**: This method encapsulates the action execution logic that was previously in `enqueuePlayerAction`.

### 4. New `executeAction` Private Method
- **File**: `SimulationService.java`
- **Change**: Extracted the action execution logic into a separate private method.
- **Purpose**: Contains all the logic for handling different action types (move, interact, speak, etc.).

### 5. Modified `updateState` Method
- **File**: `SimulationService.java`
- **Change**: Added a comment indicating that in turn-based mode, only affected agents should be updated. Currently still updates all agents.
- **Future Enhancement**: This method should be modified to selectively update agents based on the action performed.

### 6. Updated Controller Endpoints
- **File**: `SimulationController.java`
- **Changes**:
  - Renamed `/actions` endpoint to `enqueueAction` for clarity.
  - Changed `/actions` to return success message instead of action result.
  - Added new `/turn` endpoint that calls `processNextAction()` and returns the action result along with updated state.

### 7. Updated Helper Methods
- **File**: `SimulationService.java`
- **Change**: Modified `executePlayerMove`, `executePlayerInteraction`, and `executePlayerDefense` to enqueue actions and return success responses instead of executing immediately.

## API Changes

### New Endpoint
- `POST /turn`: Processes the next action in the queue, executes it, advances the simulation, and returns the result along with the new state.

### Modified Endpoints
- `POST /actions`: Now enqueues actions instead of executing them immediately. Returns `{"success": true, "message": "Action enqueued"}`.
- `POST /state`: Unchanged, but now primarily used for getting state without advancing time.

## Workflow Changes

### Old Workflow
1. Client calls `POST /actions` with an action.
2. Server executes the action immediately and returns the result.
3. Client calls `POST /state` to advance time and update all agents.
4. Server updates all agents and returns the new state.

### New Workflow
1. Client calls `POST /actions` with an action.
2. Server validates and enqueues the action, returns success.
3. Client calls `POST /turn` to process the next action.
4. Server dequeues the action, executes it, advances time, updates agents, and returns the result plus new state.

## Preserved Features
- All existing action types and logic (move, interact, speak, etc.)
- Stress calculations and agent state management
- Location bounds checking
- Memory and conversation systems
- LLM-based agent behavior updates

## Future Enhancements
- Selective agent updates based on action proximity/impact
- Multiple action queues for different players
- Action validation and conflict resolution
- Turn order management for multiplayer scenarios

## Testing Recommendations
- Test action enqueuing and dequeuing
- Verify that actions are executed in the correct order
- Ensure time advances only when turns are processed
- Test that agent updates happen after action execution
- Validate that the `/turn` endpoint returns correct state information