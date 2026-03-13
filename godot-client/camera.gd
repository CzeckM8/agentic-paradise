extends Camera2D

var zoom_speed = 0.1
var pan_speed = 500

# Add camera bounds
var min_pos = Vector2(0,0)
var max_pos = Vector2(1800, 1200)

# Reference to player
var player = null
var player_lookup_cooldown = 0.5
var player_lookup_timer = 0.0

func _ready():
	# Set initial zoom
	zoom = Vector2(0.8, 0.8)  # Slightly zoomed out to see more
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
	
	# Follow player if it exists and is valid
	if player and is_instance_valid(player):
		var target_position = player.position
		position = position.lerp(target_position, 5.0 * delta)
		
		position.x = clamp(position.x, player.position.x - 200, player.position.x + 200)
		position.y = clamp(position.y, player.position.y - 150, player.position.y + 150)
		return
	
	# Fallback: Pan with ARROW keys (not WASD, which is for player movement)
	var direction = Vector2.ZERO
	if Input.is_action_pressed("ui_right"):
		direction.x += 1 
	if Input.is_action_pressed("ui_left"):
		direction.x -= 1
	if Input.is_action_pressed("ui_up"):
		direction.y -= 1
	if Input.is_action_pressed("ui_down"):
		direction.y += 1
	
	position += direction * pan_speed * delta / zoom.x
	
	# Clamp camera position to bounds
	position.x = clamp(position.x, min_pos.x, max_pos.x)
	position.y = clamp(position.y, min_pos.y, max_pos.y)

func _input(event):
	# Zoom with mouse wheel
	if event is InputEventMouseButton:
		if event.button_index == MOUSE_BUTTON_WHEEL_UP:
			zoom += Vector2(zoom_speed, zoom_speed)
		elif event.button_index == MOUSE_BUTTON_WHEEL_DOWN:
			zoom -= Vector2(zoom_speed, zoom_speed)
	
	# Clamp zoom
	zoom.x = clamp(zoom.x, 0.3, 2.0) # Zoom out more to see more
	zoom.y = clamp(zoom.y, 0.3, 2.0)
