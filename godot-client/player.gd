extends Node2D

@onready var sprite = $Sprite2D
@onready var label = $Label

@export var current_location = "town_square"  # Export for camera access
var player_name = "Player"
var current_activity = "idle"

# Movement
var tile_step_size = 32.0
var action_locked = false
var lock_reason = ""
var inventory = []
var stress_level = 0.5  # 0.0 to 1.0

# Hold-to-move (Stoneshard/Elin style)
var held_move_dir = Vector2.ZERO
var hold_initial_delay: float = 0.20   # seconds before first repeat fires
var hold_repeat_interval: float = 0.10 # seconds between subsequent repeats
var hold_elapsed: float = 0.0
var hold_repeating: bool = false

# Action state
var pending_action = null  # Store action to be sent to server
var action_cooldown = 0.0
var action_cooldown_time = 1.0  # 1 second between actions
var action_feedback_timer = 0.0
var action_feedback_duration = 0.3  # Brief visual feedback duration
var interaction_range = 75.0

# Reference to backend connector
var backend_connector = null

func _ready():
	# Create a distinctive player sprite (larger than agents)
	var texture = _create_player_texture()
	if texture:
		sprite.texture = texture
		sprite.position = Vector2.ZERO  # Center the sprite
		sprite.visible = true
		print("Player sprite created and visible")
	else:
		print("Failed to create player texture")
	
	label.position = Vector2(-50, -50)
	label.add_theme_font_size_override("font_size", 14)
	_update_label()
	
	# Get reference to backend connector
	backend_connector = get_node("../../../BackendConnector")
	if backend_connector:
		print("Backend connector found")
	else:
		print("Backend connector NOT found")
	
	# Enable input processing
	set_process_input(true)
	
	# Set initial position snapped to tile grid.
	position = Vector2(750, 900)
	if backend_connector:
		tile_step_size = backend_connector.get_tile_size()
		position = backend_connector.snap_to_tile(position)
	current_location = "town_square"
	print("Player initialized at position: ", position, " in location: ", current_location)

func _process(delta):
	# Handle action cooldown
	if action_cooldown > 0:
		action_cooldown -= delta
	
	# Handle action feedback timer
	if action_feedback_timer > 0:
		action_feedback_timer -= delta
		if action_feedback_timer <= 0:
			_update_appearance()

	# Hold-to-move autorepeat: after holding a direction past the initial delay,
	# fire a new step every hold_repeat_interval as long as the server is idle.
	if held_move_dir != Vector2.ZERO:
		if action_locked or (backend_connector and backend_connector.is_dialogue_open()):
			held_move_dir = Vector2.ZERO
		else:
			hold_elapsed += delta
			var threshold = hold_repeat_interval if hold_repeating else hold_initial_delay
			if hold_elapsed >= threshold:
				hold_elapsed = 0.0
				hold_repeating = true
				if not (backend_connector != null and backend_connector.turn_request_in_flight):
					_try_step_move(held_move_dir)

func _key_to_direction(keycode: int) -> Vector2:
	match keycode:
		KEY_W: return Vector2(0, -1)
		KEY_S: return Vector2(0, 1)
		KEY_A: return Vector2(-1, 0)
		KEY_D: return Vector2(1, 0)
	return Vector2.ZERO

func _input(event):
	if event is InputEventKey:
		if action_locked:
			held_move_dir = Vector2.ZERO
			return

		# While dialogue UI is open, let the UI consume key presses.
		if backend_connector and backend_connector.is_dialogue_open():
			held_move_dir = Vector2.ZERO
			return

		# WASD movement with hold-to-repeat (Stoneshard/Elin model).
		var key_dir = _key_to_direction(event.keycode)
		if key_dir != Vector2.ZERO:
			if event.pressed and not event.echo:
				# Fresh press: move immediately and start hold tracking.
				held_move_dir = key_dir
				hold_elapsed = 0.0
				hold_repeating = false
				_try_step_move(key_dir)
			elif not event.pressed:
				# Key released: stop hold repeat for this direction.
				if key_dir == held_move_dir:
					held_move_dir = Vector2.ZERO
			return

		# Action keys (only on fresh press, with cooldown)
		if event.pressed and not event.echo and action_cooldown <= 0:
			var action_taken = false
			if event.keycode == KEY_SPACE:
				_try_interact_nearby()
				action_taken = true
			elif event.keycode == KEY_E:
				_open_dialogue_with_nearby()
				action_taken = true
			elif event.keycode == KEY_Q:
				_try_attack_nearby()
				action_taken = true
			
			if action_taken:
				action_cooldown = action_cooldown_time
				# Visual feedback for action
				action_feedback_timer = action_feedback_duration
				sprite.modulate = Color.YELLOW  # Brief yellow flash for action feedback

func set_action_lock(locked: bool, reason: String = ""):
	action_locked = locked
	lock_reason = reason
	if locked:
		current_activity = "waiting for response"
	else:
		if current_activity == "waiting for response":
			current_activity = "idle"
	_update_label()

func _try_step_move(direction: Vector2):
	"""Move exactly one adjacent tile (W/A/S/D) if bounds and occupancy allow it."""
	if not backend_connector:
		return

	var world_bounds = backend_connector.get_world_bounds()
	var candidate = position + direction * tile_step_size
	candidate = backend_connector.snap_to_tile(candidate)
	candidate.x = clamp(candidate.x, world_bounds.get("minX", 0.0), world_bounds.get("maxX", 1800.0))
	candidate.y = clamp(candidate.y, world_bounds.get("minY", 0.0), world_bounds.get("maxY", 1200.0))
	candidate = backend_connector.snap_to_tile(candidate)

	if candidate == position:
		return

	var target_location = backend_connector.get_location_name_for_position(candidate, "")
	if target_location == "":
		return

	if backend_connector.has_method("is_coordinate_blocked") and backend_connector.is_coordinate_blocked(candidate):
		print("Blocked movement: wall tile at ", candidate)
		return

	if backend_connector.is_coordinate_occupied(target_location, candidate, player_name):
		print("Blocked movement: tile occupied at ", candidate)
		return

	position = candidate
	current_location = target_location
	current_activity = "moving"
	_update_label()
	if backend_connector and backend_connector.has_method("mark_player_locally_moved"):
		backend_connector.mark_player_locally_moved()

	backend_connector.submit_player_movement(player_name, current_location, position)



func _try_move_to_location(location_name: String):
	"""Attempt to move to a location"""
	if backend_connector:
		# Get location bounds to send appropriate coordinates
		var location_data = backend_connector.get_location_data(location_name)
		var target_x = location_data.get("centerX", 50.0)
		var target_y = location_data.get("centerY", 50.0)
		
		# Enqueue move action
		backend_connector.enqueue_player_action(
			player_name,
			"move",
			"",  # no target agent
			location_name,  # target location
			target_x, target_y,  # target position within location
			"Moving to " + location_name
		)
		
		# Update local state optimistically
		current_location = location_name
		current_activity = "moving to " + location_name
		_update_label()
		
		print("Player moving to: ", location_name, " at position: ", target_x, ", ", target_y)

func _try_interact_nearby():
	"""Try to interact with nearby agents — opens dialogue panel (Stoneshard-style: no world turn on open)."""
	if backend_connector:
		var nearby_agents = _get_nearby_agents()
		if nearby_agents.size() > 0:
			var target_agent = nearby_agents[0]
			backend_connector.open_dialogue_panel(target_agent)
			print("Player opened dialogue with: ", target_agent)
		else:
			print("No nearby agents to interact with")

func _try_speak(message: String):
	"""Try to speak"""
	if backend_connector:
		backend_connector.enqueue_player_action(
			player_name,
			"speak",
			"",  # no target agent
			"",  # no target location
			position.x, position.y,
			"Speaking",
			message
		)
		
		current_activity = "speaking"
		_update_label()
		
		print("Player speaking: ", message)

func _open_dialogue_with_nearby():
	"""Open the dialogue panel with the nearest nearby agent."""
	if backend_connector == null:
		return

	var nearby_agents = _get_nearby_agents()
	if nearby_agents.size() == 0:
		print("No nearby agents to talk to")
		return

	var target_agent = nearby_agents[0]
	backend_connector.open_dialogue_panel(target_agent)

func _try_attack_nearby():
	"""Try to attack nearby agents"""
	if backend_connector:
		var nearby_agents = _get_nearby_agents()
		if nearby_agents.size() > 0:
			var target_agent = nearby_agents[0]  # Attack first nearby agent
			
			backend_connector.enqueue_player_action(
				player_name,
				"attack",
				target_agent,
				"",  # no target location
				position.x, position.y,
				"Attacking " + target_agent,
				"",  # no speak text
				0.8,  # high intensity
				""  # no item
			)
			
			current_activity = "attacking " + target_agent
			_update_label()
			
			print("Player attacking: ", target_agent)
		else:
			print("No nearby agents to attack")

func _get_nearby_agents() -> Array:
	"""Get nearby agents in same location and within interaction range, nearest first."""
	var candidates = []
	if backend_connector:
		var all_agents = backend_connector.agent_nodes.keys()
		for agent_name in all_agents:
			if agent_name != player_name:
				var agent_pos = backend_connector.get_agent_position(agent_name)
				if agent_pos.get("location") == current_location:
					var ax = float(agent_pos.get("x", position.x))
					var ay = float(agent_pos.get("y", position.y))
					var distance = position.distance_to(Vector2(ax, ay))
					if distance <= interaction_range:
						candidates.append({"name": agent_name, "distance": distance})

	if candidates.is_empty():
		return []

	candidates.sort_custom(func(a, b): return a["distance"] < b["distance"])
	var nearby = []
	for candidate in candidates:
		nearby.append(candidate["name"])
	return nearby

func update_from_backend(data: Dictionary, _location_map: Dictionary):
	"""Update player state from backend data.
	
	One-time startup sync is allowed so save-state/server position and local node
	are aligned before the player starts moving. After the first local movement,
	position becomes client-authoritative again."""
	player_name = data.get("name", "Player")
	current_activity = data.get("activity", current_activity)
	current_location = data.get("location", current_location)
	stress_level = data.get("stress", 0.5)

	var allow_position_sync = false
	if backend_connector and backend_connector.has_method("should_sync_player_position_from_backend"):
		allow_position_sync = backend_connector.should_sync_player_position_from_backend()
	if allow_position_sync and data.has("x") and data.has("y"):
		var server_pos = Vector2(float(data.get("x", position.x)), float(data.get("y", position.y)))
		if backend_connector and backend_connector.has_method("snap_to_tile"):
			server_pos = backend_connector.snap_to_tile(server_pos)
		position = server_pos

	_update_appearance()
	_update_label()

func move_to_location(location_name: String, location_map: Dictionary):
	"""Move player to a new location (called by backend after successful move)"""
	if location_map.has(location_name):
		var location_data = location_map[location_name]
		if location_data is Dictionary:
			var center_x = location_data.get("centerX", 50.0)
			var center_y = location_data.get("centerY", 50.0)
			position = Vector2(center_x, center_y)
			current_location = location_name
			current_activity = "at " + location_name
			_update_label()
	else:
		push_error("Location not found: " + location_name)

func set_activity(activity: String):
	"""Set player's current activity"""
	current_activity = activity
	_update_label()

func get_position_in_location() -> Vector2:
	"""Return player's current position (for actions)"""
	return position

func update_stress(delta: float):
	"""Update stress level"""
	stress_level = clamp(stress_level + delta, 0.0, 1.0)
	_update_appearance()

func _update_label():
	"""Update the text label above the player"""
	var location_text = current_location
	if backend_connector:
		location_text = backend_connector.get_location_display_name(current_location)
	label.text = "%s\n%s\n@ %s\nStress: %.1f" % [
		player_name, 
		current_activity, 
		location_text,
		stress_level * 100
	]

func _update_appearance():
	"""Change color based on stress level"""
	if stress_level < 0.3:
		# Low stress - green/calm
		sprite.modulate = Color.GREEN
	elif stress_level < 0.7:
		# Medium stress - yellow
		sprite.modulate = Color.YELLOW
	else:
		# High stress - red/agitated
		sprite.modulate = Color.RED

func _create_player_texture():
	"""Create a distinctive player sprite at roughly agent size."""
	var img = Image.create(32, 32, false, Image.FORMAT_RGBA8)
	
	# Draw a circle in the center
	var center = Vector2(16, 16)
	var radius = 13
	
	for x in range(32):
		for y in range(32):
			var pixel_pos = Vector2(x, y)
			var dist = pixel_pos.distance_to(center)
			if dist <= radius:
				# Filled circle
				img.set_pixel(x, y, Color.DODGER_BLUE)
			elif dist <= radius + 3:
				# Border
				img.set_pixel(x, y, Color.WHITE)
	
	return ImageTexture.create_from_image(img)

func get_player_info() -> Dictionary:
	"""Return player info for debugging"""
	return {
		"name": player_name,
		"activity": current_activity,
		"location": current_location,
		"stress": stress_level,
		"position": position
	}
