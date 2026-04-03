extends Camera2D

var zoom_speed = 0.1
var pan_speed = 500

# Add camera bounds
var min_pos = Vector2(0,0)
var max_pos = Vector2(1800, 1200)
var manual_pan_offset = Vector2.ZERO
var manual_pan_decay = 3.0

# Reference to player
var player = null
var player_lookup_cooldown = 0.5
var player_lookup_timer = 0.0
var backend_connector = null

func _ready():
	# Set initial zoom
	zoom = Vector2(0.8, 0.8)  # Slightly zoomed out to see more
	backend_connector = get_node_or_null("../BackendConnector")
	call_deferred("_find_player")

func _find_player():
	"""Find the runtime player node instantiated under World/Agents."""
	var world_node = get_parent()
	if world_node:
		player = world_node.get_node_or_null("Agents/Player")
	if player:
		print("Camera found player at: ", player.get_path(), " position: ", player.position)
		position = player.position
	else:
		print("Camera could not find player yet")

func _process(delta):
	if not player or not is_instance_valid(player):
		player_lookup_timer -= delta
		if player_lookup_timer <= 0.0:
			_find_player()
			player_lookup_timer = player_lookup_cooldown

	# Pan with ARROW keys (not WASD, which is for player movement)
	var direction = Vector2.ZERO
	if Input.is_action_pressed("ui_right"):
		direction.x += 1 
	if Input.is_action_pressed("ui_left"):
		direction.x -= 1
	if Input.is_action_pressed("ui_up"):
		direction.y -= 1
	if Input.is_action_pressed("ui_down"):
		direction.y += 1

	if direction != Vector2.ZERO:
		manual_pan_offset += direction.normalized() * pan_speed * delta / zoom.x
	else:
		# Ease camera back toward the player when not manually panning.
		manual_pan_offset = manual_pan_offset.lerp(Vector2.ZERO, manual_pan_decay * delta)

	var follow_target = position
	if player and is_instance_valid(player):
		follow_target = player.position + manual_pan_offset
	else:
		follow_target = position + manual_pan_offset

	position = position.lerp(follow_target, 5.0 * delta)

	# Clamp camera position to world bounds.
	position.x = clamp(position.x, min_pos.x, max_pos.x)
	position.y = clamp(position.y, min_pos.y, max_pos.y)

func _input(event):
	# Zoom with mouse wheel — disabled entirely while dialogue is open so the
	# dialogue log can scroll without also zooming the camera.
	if event is InputEventMouseButton:
		if backend_connector != null and backend_connector.is_dialogue_open():
			return
		if event.button_index == MOUSE_BUTTON_WHEEL_UP:
			zoom += Vector2(zoom_speed, zoom_speed)
		elif event.button_index == MOUSE_BUTTON_WHEEL_DOWN:
			zoom -= Vector2(zoom_speed, zoom_speed)
	
	# Clamp zoom
	zoom.x = clamp(zoom.x, 0.3, 2.0) # Zoom out more to see more
	zoom.y = clamp(zoom.y, 0.3, 2.0)
