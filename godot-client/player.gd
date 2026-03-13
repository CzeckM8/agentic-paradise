extends Node2D

@onready var sprite = $Sprite2D
@onready var label = $Label

@export var current_location = "town_square"  # Export for camera access
var player_name = "Player"
var current_activity = "idle"

# Movement
var move_speed = 200.0  # pixels per second (slower for better control)
var tile_step_size = 32.0
var target_position = Vector2.ZERO
var velocity = Vector2.ZERO
var is_moving = false
var movement_sync_timer = 0.0
var movement_sync_cooldown = 0.35
var movement_sync_min_distance = 20.0
var last_synced_position = Vector2.ZERO
var has_initial_backend_position = false
var inventory = []
var stress_level = 0.5  # 0.0 to 1.0

# Action state
var pending_action = null  # Store action to be sent to server
var action_cooldown = 0.0
var action_cooldown_time = 1.0  # 1 second between actions
var action_feedback_timer = 0.0
var action_feedback_duration = 0.3  # Brief visual feedback duration

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
	target_position = position
	_update_label()
	
	# Get reference to backend connector
	backend_connector = get_node("../../../BackendConnector")
	if backend_connector:
		print("Backend connector found")
	else:
		print("Backend connector NOT found")
	
	# Enable input processing
	set_process_input(true)
	
	# Set initial position to be visible and snapped to tile grid.
	position = Vector2(750, 900)
	if backend_connector:
		tile_step_size = backend_connector.get_tile_size()
		position = backend_connector.snap_to_tile(position)
	target_position = position
	last_synced_position = position
	current_location = "town_square"
	print("Player initialized at position: ", position, " in location: ", current_location)

func _process(delta):
	is_moving = false

	# Handle action cooldown
	if action_cooldown > 0:
		action_cooldown -= delta
	
	# Handle action feedback timer
	if action_feedback_timer > 0:
		action_feedback_timer -= delta
		if action_feedback_timer <= 0:
			# Reset to normal appearance
			_update_appearance()
	
	# Keep backend synchronized for non-movement updates and safety.
	_sync_movement_to_backend(delta)
	
	# Handle smooth movement towards target (for location changes or other movements)
	if position.distance_to(target_position) > 5:
		position = position.move_toward(target_position, move_speed * delta)
		is_moving = true
		if current_activity != "moving":
			current_activity = "moving"
			_update_label()
	else:
		# Only set to idle if not currently moving via WASD
		if not is_moving and current_activity == "moving":
			current_activity = "idle"
			_update_label()

func _input(event):
	if event is InputEventKey:
		# Discrete tile movement (one adjacent tile per key press).
		if event.pressed and not event.echo:
			var move_direction = Vector2.ZERO
			if event.keycode == KEY_W:
				move_direction = Vector2(0, -1)
			elif event.keycode == KEY_S:
				move_direction = Vector2(0, 1)
			elif event.keycode == KEY_A:
				move_direction = Vector2(-1, 0)
			elif event.keycode == KEY_D:
				move_direction = Vector2(1, 0)

			if move_direction != Vector2.ZERO:
				_try_step_move(move_direction)
				return

		# Action keys (only on press, with cooldown)
		if event.pressed and action_cooldown <= 0:
			var action_taken = false
			if event.keycode == KEY_SPACE:
				_try_interact_nearby()
				action_taken = true
			elif event.keycode == KEY_E:
				_try_speak("Hello there!")
				action_taken = true
			elif event.keycode == KEY_Q:
				_try_attack_nearby()
				action_taken = true
			
			if action_taken:
				action_cooldown = action_cooldown_time
				# Visual feedback for action
				action_feedback_timer = action_feedback_duration
				sprite.modulate = Color.YELLOW  # Brief yellow flash for action feedback

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

	var target_location = backend_connector.get_location_name_for_position(candidate, current_location)
	if target_location == "":
		return

	if backend_connector.is_coordinate_occupied(target_location, candidate, player_name):
		print("Blocked movement: tile occupied at ", candidate)
		return

	position = candidate
	target_position = candidate
	current_location = target_location
	current_activity = "moving"
	is_moving = true
	_update_label()

	backend_connector.submit_player_movement(player_name, current_location, position)
	last_synced_position = position
	movement_sync_timer = movement_sync_cooldown

func _move_in_direction(direction: Vector2):
	"""Move player smoothly in a direction within current location bounds"""
	if backend_connector:
		# Get current location bounds
		var location_data = backend_connector.get_location_data(current_location)
		if location_data.is_empty():
			print("No location data for: ", current_location)
			return
		
		# Calculate new position
		var move_distance = 50.0  # Distance to move per key press
		var new_position = position + direction * move_distance
		
		# Clamp to location bounds
		var min_x = location_data.get("minX", 0.0)
		var max_x = location_data.get("maxX", 1000.0)
		var min_y = location_data.get("minY", 0.0)
		var max_y = location_data.get("maxY", 1000.0)
		
		new_position.x = clamp(new_position.x, min_x, max_x)
		new_position.y = clamp(new_position.y, min_y, max_y)
		
		# Set target position for smooth movement
		target_position = new_position
		is_moving = true
		current_activity = "moving"
		_update_label()
		
		print("Moving to: ", new_position, " in location: ", current_location)

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
	"""Try to interact with nearby agents"""
	if backend_connector:
		var nearby_agents = _get_nearby_agents()
		if nearby_agents.size() > 0:
			var target_agent = nearby_agents[0]  # Interact with first nearby agent
			
			backend_connector.enqueue_player_action(
				player_name,
				"interact",
				target_agent,
				"",  # no target location
				position.x, position.y,
				"Interacting with " + target_agent
			)
			
			current_activity = "interacting with " + target_agent
			_update_label()
			
			print("Player interacting with: ", target_agent)
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
	"""Get list of agents in the same location (simplified proximity check)"""
	var nearby = []
	if backend_connector:
		var all_agents = backend_connector.agent_nodes.keys()
		for agent_name in all_agents:
			if agent_name != player_name:
				var agent_pos = backend_connector.get_agent_position(agent_name)
				if agent_pos.get("location") == current_location:
					nearby.append(agent_name)
	return nearby

func _sync_movement_to_backend(delta: float):
	"""Throttle move action sync so walking drives server turns without flooding the queue."""
	if not backend_connector:
		return

	movement_sync_timer = max(0.0, movement_sync_timer - delta)
	if movement_sync_timer > 0.0:
		return

	if position.distance_to(last_synced_position) < movement_sync_min_distance:
		return

	current_location = backend_connector.get_location_name_for_position(position, current_location)
	backend_connector.submit_player_movement(player_name, current_location, position)
	last_synced_position = position
	movement_sync_timer = movement_sync_cooldown

func update_from_backend(data: Dictionary, location_map: Dictionary):
	"""Update player based on backend data"""
	player_name = data.get("name", "Player")
	current_activity = data.get("activity", "idle")
	current_location = data.get("location", "market")
	stress_level = data.get("stress", 0.5)

	# Use authoritative coordinates when the backend returns them.
	if data.has("x") and data.has("y"):
		var backend_pos = Vector2(float(data.get("x", position.x)), float(data.get("y", position.y)))
		if backend_connector:
			backend_pos = backend_connector.snap_to_tile(backend_pos)
		position = backend_pos
		target_position = backend_pos
	
	# Update target position based on location
	if not (data.has("x") and data.has("y")) and location_map.has(current_location):
		var location_data = location_map[current_location]
		if location_data is Dictionary:
			var center_x = location_data.get("centerX", 50.0)
			var center_y = location_data.get("centerY", 50.0)
			target_position = Vector2(center_x, center_y)
		else:
			target_position = location_data as Vector2
	
	# Update appearance
	_update_appearance()
	_update_label()

	# Ensure first backend sync places player immediately at resolved coordinates.
	if not has_initial_backend_position:
		position = target_position
		last_synced_position = position
		has_initial_backend_position = true

func move_to_location(location_name: String, location_map: Dictionary):
	"""Move player to a new location (called by backend after successful move)"""
	if location_map.has(location_name):
		var location_data = location_map[location_name]
		if location_data is Dictionary:
			var center_x = location_data.get("centerX", 50.0)
			var center_y = location_data.get("centerY", 50.0)
			target_position = Vector2(center_x, center_y)
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
