extends Node2D

@onready var sprite = $Sprite2D
@onready var label = $Label

var agent_name = ""
var current_activity = ""
var current_location = ""

# Per-turn discrete positioning (no smooth lerp — turn-based like Elin/Stone Shard)
var target_position = Vector2.ZERO
var has_initial_position = false

func _ready():
	# Default appearance: diamond marker with white outline.
	sprite.texture = _create_diamond_texture()
	label.position = Vector2(-40, -40)
	label.add_theme_font_size_override("font_size", 12)
	target_position = position

func _process(_delta):
	pass  # Position is set directly on each turn — no per-frame lerp

func update_from_backend(data, location_map, preset_position: Vector2 = Vector2.ZERO):
	"""Update agent based on backend data.
	If preset_position is provided, use it instead of computing from location_map
	(grid-based spacing from backend_connector.gd)"""
	agent_name = data.get("name", "unknown")
	current_activity = data.get("activity", data.get("action", data.get("currentAction", "idle")))
	current_location = data.get("location", "unknown")
	
	# Prefer server-authoritative coordinates when available.
	if data.has("x") and data.has("y"):
		target_position = Vector2(float(data.get("x", 0.0)), float(data.get("y", 0.0)))
	# Fallback to preset position (legacy client-side grid spacing)
	elif preset_position != Vector2.ZERO:
		target_position = preset_position
	# Otherwise compute from location center
	elif location_map.has(current_location):
		var location_data = location_map[current_location]
		if location_data is Dictionary:
			# Extract center coordinates from location bounds
			var center_x = location_data.get("centerX", 50.0)
			var center_y = location_data.get("centerY", 50.0)
			target_position = Vector2(center_x, center_y)
		else:
			# Fallback for non-dict location data
			target_position = location_data as Vector2
	
	# Update label
	_update_label()
	
	# Update color based on activity keywords
	_update_appearance()

	# Always snap to authoritative position — discrete turn-based movement.
	position = target_position
	has_initial_position = true

func _update_label():
	"""Update the text label above the agent"""
	var display_text = agent_name
	
	# Truncate long activities
	var activity_display = current_activity
	if activity_display.length() > 30:
		activity_display = activity_display.substr(0, 27) + "..."
	
	label.text = "%s\n%s\n@ %s" % [agent_name, activity_display, current_location]

func _update_appearance():
	"""Change color based on activity/state"""
	var activity_lower = current_activity.to_lower()
	
	if "walk" in activity_lower or "moving" in activity_lower or "going" in activity_lower:
		sprite.modulate = Color.GREEN
	elif "talk" in activity_lower or "conversation" in activity_lower or "speaking" in activity_lower:
		sprite.modulate = Color.YELLOW
	elif "fight" in activity_lower or "attack" in activity_lower or "combat" in activity_lower:
		sprite.modulate = Color.RED
	elif "idle" in activity_lower or "waiting" in activity_lower or "standing" in activity_lower:
		sprite.modulate = Color.GRAY
	else:
		sprite.modulate = Color.WHITE

func _create_diamond_texture():
	"""Create a diamond-shaped marker with a white outline."""
	var size = 32
	var img = Image.create(size, size, false, Image.FORMAT_RGBA8)
	img.fill(Color(0, 0, 0, 0))

	var center = Vector2i(size / 2, size / 2)
	var radius = 11

	for x in range(size):
		for y in range(size):
			var d = abs(x - center.x) + abs(y - center.y)
			if d <= radius:
				img.set_pixel(x, y, Color(0.39, 0.40, 0.95, 0.95))
			elif d <= radius + 1:
				img.set_pixel(x, y, Color(1, 1, 1, 1))

	return ImageTexture.create_from_image(img)

func get_agent_info():
	"""Return agent info for inspection/debugging"""
	return {
		"name": agent_name,
		"activity": current_activity,
		"location": current_location,
		"position": position
	}
