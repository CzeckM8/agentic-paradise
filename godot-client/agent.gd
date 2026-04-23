extends Node2D

@onready var sprite = $Sprite2D
@onready var label = $Label

var agent_name = ""
var current_activity = ""
var current_location = ""
var target_location = ""  # Where this NPC wants to go (set by server)
var target_object_id = ""  # Optional in-location anchor, e.g. coffee_register

# Per-turn discrete positioning (no smooth lerp — turn-based like Elin/Stone Shard)
var target_position = Vector2.ZERO
var has_initial_position = false
var _idle_turn_count: int = 0  # counts idle steps; prevents direction oscillation
var speech_label: Label = null
var _speech_timer: float = 0.0

func _ready():
	var npc_tex = load("res://assets/sprites/generic_male.png") as Texture2D
	var tile_px := 32.0
	var bc := get_node_or_null("../../../BackendConnector")
	if bc != null and bc.has_method("get_tile_size"):
		tile_px = float(bc.get_tile_size())
	if npc_tex:
		sprite.texture = npc_tex
		sprite.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
		sprite.centered = true
	else:
		sprite.texture = _create_diamond_texture()
	var d = maxf(float(sprite.texture.get_width()), float(sprite.texture.get_height()))
	if d > 0.0:
		var s = tile_px / d
		sprite.scale = Vector2(s, s)
	sprite.position = Vector2(16, 16)
	label.position = Vector2(-24, -52)
	label.add_theme_font_size_override("font_size", 12)
	label.autowrap_mode = TextServer.AUTOWRAP_WORD
	label.custom_minimum_size = Vector2(180, 0)
	target_position = position

	# Speech bubble label — styled panel above the name label
	speech_label = Label.new()
	speech_label.name = "SpeechLabel"
	speech_label.position = Vector2(-44, -106)
	speech_label.add_theme_font_size_override("font_size", 10)
	speech_label.add_theme_color_override("font_color", Color.WHITE)
	speech_label.autowrap_mode = TextServer.AUTOWRAP_WORD
	speech_label.custom_minimum_size = Vector2(120, 0)
	var bg = StyleBoxFlat.new()
	bg.bg_color = Color(0.07, 0.07, 0.07, 0.80)
	bg.content_margin_left = 5
	bg.content_margin_right = 5
	bg.content_margin_top = 3
	bg.content_margin_bottom = 3
	bg.corner_radius_top_left = 4
	bg.corner_radius_top_right = 4
	bg.corner_radius_bottom_left = 4
	bg.corner_radius_bottom_right = 4
	speech_label.add_theme_stylebox_override("normal", bg)
	speech_label.visible = false
	add_child(speech_label)

func _process(delta):
	if _speech_timer > 0.0:
		_speech_timer -= delta
		if _speech_timer <= 0.0 and speech_label != null:
			speech_label.visible = false

func update_from_backend(data, location_map, preset_position: Vector2 = Vector2.ZERO):
	"""Update agent based on backend data.
	Position is only set from server on first initialisation — after that the
	client steps the NPC toward target_location each turn."""
	agent_name = data.get("name", "unknown")
	current_activity = data.get("activity", data.get("action", data.get("currentAction", "idle")))
	if data.has("location"):
		var location_value = data.get("location", "unknown")
		current_location = "" if location_value == null else str(location_value)
	if data.has("targetLocation"):
		var target_location_value = data.get("targetLocation", "")
		target_location = "" if target_location_value == null else str(target_location_value)
	if data.has("object"):
		var object_value = data.get("object", "")
		target_object_id = "" if object_value == null else str(object_value)

	# On first spawn, initialise position from server coords or preset
	if not has_initial_position:
		if data.has("x") and data.has("y"):
			position = Vector2(float(data.get("x", 0.0)), float(data.get("y", 0.0)))
		elif preset_position != Vector2.ZERO:
			position = preset_position
		elif location_map.has(current_location):
			var location_data = location_map[current_location]
			if location_data is Dictionary:
				position = Vector2(location_data.get("centerX", 50.0), location_data.get("centerY", 50.0))
		has_initial_position = true

	# Update label and appearance
	_update_label()
	_update_appearance()

func show_speech(text: String, duration: float = 6.0) -> void:
	"""Show a floating speech bubble above this agent for `duration` seconds."""
	if speech_label == null:
		return
	var display = text.strip_edges()
	if display.length() > 140:
		display = display.substr(0, 137) + "..."
	speech_label.text = display
	speech_label.visible = true
	_speech_timer = duration

func _update_label():
	"""Update the text label above the agent"""
	var display_text = agent_name
	
	# Keep long activities visible without flooding the label.
	var activity_display = current_activity
	if activity_display.length() > 70:
		activity_display = activity_display.substr(0, 67) + "..."
	
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

func step_client_side(bc: Node) -> void:
	"""Move one tile per turn using obstacle-aware pathing.
	If no explicit target is active, perform a small local routine roam."""
	if not has_initial_position:
		return
	current_location = bc.get_location_name_for_position(position, current_location)
	if _is_dialogue_locked_activity():
		_update_label()
		return

	var waypoint = _compute_waypoint(bc)
	if waypoint == Vector2.ZERO:
		if target_object_id != "" or target_location != "":
			return
		_step_idle_routine(bc)
		return

	var next_step = _compute_next_step_astar(bc, position, waypoint, 8192)
	if next_step == Vector2.ZERO:
		return

	position = bc.snap_to_tile(position + next_step)
	current_location = bc.get_location_name_for_position(position, current_location)
	_update_label()

func _is_dialogue_locked_activity() -> bool:
	var activity_lower = current_activity.to_lower()
	return activity_lower.contains("agentic: speaking") \
		or activity_lower.contains("talking with") \
		or activity_lower.contains("engaged in conversation") \
		or activity_lower.contains("conversation declined") \
		or activity_lower.contains("deferred conversation")

func _compute_waypoint(bc: Node) -> Vector2:
	"""Return the coordinate this NPC should step toward this turn."""
	# During agentic movement, track the target entity's live position via entity anchor.
	# The target name is embedded in the activity string: "agentic: moving toward <Name> ..."
	if _is_agentic_approach_activity() and bc.has_method("get_entity_anchor"):
		var target_name = _extract_agentic_movement_target()
		if target_name != "":
			var anchor_pos = bc.get_entity_anchor(target_name)
			if anchor_pos != Vector2.ZERO:
				return bc.snap_to_tile(anchor_pos)

	var safe_object_id = "" if target_object_id == null else str(target_object_id)
	if safe_object_id != "":
		var target_obj = _find_world_object_by_id(bc, safe_object_id)
		if not target_obj.is_empty():
			var object_location = str(target_obj.get("location", ""))
			if object_location != "" and object_location == current_location:
				return bc.snap_to_tile(Vector2(float(target_obj.get("x", 0.0)), float(target_obj.get("y", 0.0))))

	if target_location == "":
		return Vector2.ZERO
	if not bc.locations.has(target_location):
		return Vector2.ZERO

	var loc_data = bc.locations[target_location]
	if not (loc_data is Dictionary):
		return Vector2.ZERO

	var center = Vector2(
		float(loc_data.get("centerX", 0.0)),
		float(loc_data.get("centerY", 0.0))
	)

	# If already inside the target location, walk toward its center
	var cur = bc.get_location_name_for_position(position, "")
	if cur == target_location:
		return center

	# Look for an entrance anchor in world_objects for this location
	var nearest_anchor = Vector2.ZERO
	var best_dist = INF
	for obj in bc.world_objects:
		if str(obj.get("type", "")) != "entrance_anchor":
			continue
		var obj_loc = str(obj.get("location", ""))
		if obj_loc == target_location \
				or obj_loc.ends_with(":" + target_location) \
				or target_location.ends_with(":" + obj_loc):
			var anchor_pos = Vector2(float(obj.get("x", 0.0)), float(obj.get("y", 0.0)))
			var d = position.distance_to(anchor_pos)
			if d < best_dist:
				best_dist = d
				nearest_anchor = anchor_pos

	if nearest_anchor != Vector2.ZERO:
		var raw_interior = _interior_door_target(bc, nearest_anchor, loc_data)
		var anchor_snap = bc.snap_to_tile(nearest_anchor)
		var interior = _resolve_passable_door_waypoint(bc, raw_interior, anchor_snap, position)
		if interior != Vector2.ZERO:
			if position.distance_to(interior) <= bc.get_tile_size() * 1.0:
				var at_loc = bc.get_location_name_for_position(position, "")
				if at_loc == target_location:
					return center
			return interior
		return _clamped_interior_waypoint(bc, loc_data)

	# No anchor — choose an interior waypoint (not edge/border) to avoid
	# floor/snap mismatch that can stick goals just outside large open areas.
	return _clamped_interior_waypoint(bc, loc_data)

func _clamped_interior_waypoint(bc: Node, loc_data: Dictionary) -> Vector2:
	var ts = bc.get_tile_size()
	var min_x = float(loc_data.get("minX", 0.0))
	var max_x = float(loc_data.get("maxX", 0.0))
	var min_y = float(loc_data.get("minY", 0.0))
	var max_y = float(loc_data.get("maxY", 0.0))
	var interior_min_x = min_x + ts
	var interior_max_x = max_x - ts
	var interior_min_y = min_y + ts
	var interior_max_y = max_y - ts
	if interior_min_x > interior_max_x:
		interior_min_x = min_x
		interior_max_x = max_x
	if interior_min_y > interior_max_y:
		interior_min_y = min_y
		interior_max_y = max_y
	var clamped = Vector2(
		clamp(position.x, interior_min_x, interior_max_x),
		clamp(position.y, interior_min_y, interior_max_y)
	)
	return bc.snap_to_tile(clamped)

func _is_agentic_approach_activity() -> bool:
	return current_activity.to_lower().contains("agentic: moving toward")

func _extract_agentic_movement_target() -> String:
	"""Parse the entity name out of an activity string like:
	   'agentic: moving toward Player'
	   'agentic: moving toward Player to discuss <topic>'
	Returns empty string if the format is not matched."""
	var lower = current_activity.to_lower()
	var prefix = "agentic: moving toward "
	var idx = lower.find(prefix)
	if idx < 0:
		return ""
	var rest = current_activity.substr(idx + prefix.length())
	# Entity name ends at " to " qualifier or end-of-string
	var to_idx = rest.to_lower().find(" to ")
	if to_idx >= 0:
		return rest.substr(0, to_idx).strip_edges()
	return rest.strip_edges()

func _find_world_object_by_id(bc: Node, object_id) -> Dictionary:
	if object_id == null:
		return {}
	var safe_id = str(object_id)
	if safe_id == "":
		return {}
	for obj in bc.world_objects:
		if str(obj.get("id", "")) == safe_id:
			return obj
	return {}

func _compute_next_step_astar(bc: Node, from_pos: Vector2, to_pos: Vector2, max_expansions: int = 8192) -> Vector2:
	"""Return a one-tile cardinal step using A* over the 32px grid."""
	var start_tile: Vector2i = bc.world_to_tile(from_pos)
	var goal_tile: Vector2i = bc.world_to_tile(to_pos)
	if start_tile == goal_tile:
		return Vector2.ZERO

	# If the goal tile is blocked, substitute the closest passable tile by Manhattan
	# distance to the intended goal (cardinals first, then wider rings). Tie-break
	# with distance to start so the approach path stays reasonable. No substitute → stuck.
	var resolved_goal = _resolve_blocked_astar_goal(bc, goal_tile, start_tile, 32)
	if resolved_goal == null:
		return Vector2.ZERO
	goal_tile = resolved_goal
	if start_tile == goal_tile:
		return Vector2.ZERO

	var open: Array = [{"tile": start_tile, "g": 0, "f": _manhattan(start_tile, goal_tile)}]
	var came_from: Dictionary = {}
	var g_score: Dictionary = {_tile_key(start_tile): 0}
	var closed: Dictionary = {}
	var expansions = 0

	while not open.is_empty() and expansions < max_expansions:
		expansions += 1
		var best_i = 0
		var best_f = open[0]["f"]
		for i in range(1, open.size()):
			if open[i]["f"] < best_f:
				best_f = open[i]["f"]
				best_i = i

		var current = open[best_i]
		open.remove_at(best_i)
		var current_tile: Vector2i = current["tile"]
		var current_key = _tile_key(current_tile)
		if closed.has(current_key):
			continue
		closed[current_key] = true

		if current_tile == goal_tile:
			var step_tile = _reconstruct_first_step(came_from, start_tile, goal_tile)
			if step_tile == start_tile:
				break
			var world_step = bc.tile_to_world(step_tile) - bc.tile_to_world(start_tile)
			return bc.snap_to_tile(world_step)

		for dir in [Vector2i(1, 0), Vector2i(-1, 0), Vector2i(0, 1), Vector2i(0, -1)]:
			var neighbor = current_tile + dir
			if closed.has(_tile_key(neighbor)):
				continue
			var neighbor_world = bc.tile_to_world(neighbor)
			if bc.is_coordinate_blocked(neighbor_world):
				continue

			var bounds = bc.get_world_bounds()
			if neighbor_world.x < bounds.get("minX", 0.0) or neighbor_world.x > bounds.get("maxX", 1800.0):
				continue
			if neighbor_world.y < bounds.get("minY", 0.0) or neighbor_world.y > bounds.get("maxY", 1200.0):
				continue

			var ng = int(current["g"]) + 1
			var nkey = _tile_key(neighbor)
			if not g_score.has(nkey) or ng < int(g_score[nkey]):
				g_score[nkey] = ng
				came_from[nkey] = current_tile
				open.append({"tile": neighbor, "g": ng, "f": ng + _manhattan(neighbor, goal_tile)})

	return Vector2.ZERO

func _interior_door_target(bc: Node, anchor: Vector2, loc_data: Dictionary) -> Vector2:
	"""Return the world-space tile one step INSIDE the building from the door anchor.
	The door hole tile is snap_to_tile(anchor); we then step one tile deeper into the
	building interior so A* goal is never on the wall or outside the building."""
	var ts = bc.get_tile_size()
	var min_x = float(loc_data.get("minX", 0.0))
	var max_x = float(loc_data.get("maxX", 0.0))
	var min_y = float(loc_data.get("minY", 0.0))
	var max_y = float(loc_data.get("maxY", 0.0))
	# snap_to_tile uses round, matching _seed_building_wall_tiles door exclusion
	var door = bc.snap_to_tile(anchor)
	var result = door
	if anchor.x <= min_x + ts:        # left wall → step right into building
		result.x = door.x + ts
	elif anchor.x >= max_x - ts:      # right wall → step left into building
		result.x = door.x - ts
	elif anchor.y <= min_y + ts:      # top wall → step down into building
		result.y = door.y + ts
	elif anchor.y >= max_y - ts:      # bottom wall → step up into building
		result.y = door.y - ts
	return result

func _step_idle_routine(bc: Node) -> void:
	"""Small local movement when no explicit target exists to avoid static NPCs."""
	if current_location == "" or not bc.locations.has(current_location):
		return
	var loc = bc.locations[current_location]
	if not (loc is Dictionary):
		return

	_idle_turn_count += 1
	var tile = bc.get_tile_size()
	var dirs = [Vector2(tile, 0), Vector2(-tile, 0), Vector2(0, tile), Vector2(0, -tile)]
	# Use turn-bucketed seed so direction persists 4 turns before changing
	var seed = abs((agent_name + ":" + str(_idle_turn_count / 4)).hash()) % 4
	for i in range(4):
		var step = dirs[(seed + i) % 4]
		var candidate = bc.snap_to_tile(position + step)
		if bc.is_coordinate_blocked(candidate):
			continue
		if not _is_within_location(candidate, loc):
			continue
		position = candidate
		current_location = bc.get_location_name_for_position(position, current_location)
		_update_label()
		return

func _is_within_location(world_pos: Vector2, loc_data: Dictionary) -> bool:
	return world_pos.x >= float(loc_data.get("minX", 0.0)) \
		and world_pos.x <= float(loc_data.get("maxX", 0.0)) \
		and world_pos.y >= float(loc_data.get("minY", 0.0)) \
		and world_pos.y <= float(loc_data.get("maxY", 0.0))

func _manhattan(a: Vector2i, b: Vector2i) -> int:
	return abs(a.x - b.x) + abs(a.y - b.y)

## Tiles at exactly Manhattan distance `r` from `center` (diamond perimeter).
func _manhattan_ring(center: Vector2i, r: int) -> Array:
	var out: Array = []
	if r <= 0:
		return [center]
	for dx in range(-r, r + 1):
		var adx = abs(dx)
		var dy_rem = r - adx
		var x = center.x + dx
		if dy_rem == 0:
			out.append(Vector2i(x, center.y))
		else:
			out.append(Vector2i(x, center.y + dy_rem))
			out.append(Vector2i(x, center.y - dy_rem))
	return out

func _tile_in_world_bounds(bc: Node, tile: Vector2i) -> bool:
	var p = bc.tile_to_world(tile)
	var bounds = bc.get_world_bounds()
	return p.x >= float(bounds.get("minX", 0.0)) and p.x <= float(bounds.get("maxX", 1800.0)) \
		and p.y >= float(bounds.get("minY", 0.0)) and p.y <= float(bounds.get("maxY", 1200.0))

## When the pathfinding goal sits on a blocked tile, pick a passable substitute that
## minimizes Manhattan distance to the intended goal; tie-break with distance from `start_tile`.
## Returns null if no passable tile exists within `max_radius`.
func _resolve_blocked_astar_goal(bc: Node, intended_goal: Vector2i, start_tile: Vector2i, max_radius: int = 32) -> Variant:
	if not bc.is_coordinate_blocked(bc.tile_to_world(intended_goal)):
		return intended_goal
	for r in range(1, max_radius + 1):
		var pool: Array = []
		for t in _manhattan_ring(intended_goal, r):
			if not _tile_in_world_bounds(bc, t):
				continue
			if bc.is_coordinate_blocked(bc.tile_to_world(t)):
				continue
			pool.append(t)
		if pool.is_empty():
			continue
		var pick: Vector2i = pool[0]
		var best_to_start = _manhattan(start_tile, pick)
		for i in range(1, pool.size()):
			var cand: Vector2i = pool[i]
			var ds = _manhattan(start_tile, cand)
			if ds < best_to_start:
				best_to_start = ds
				pick = cand
		return pick
	return null

## Door approach: prefer interior tile, then snapped anchor; if both register as blocked,
## use the same Manhattan-ring search as A* (from the agent) so the waypoint is passable when possible.
## Returns Vector2.ZERO if no passable substitute exists (caller must fall back, e.g. clamped interior).
func _resolve_passable_door_waypoint(bc: Node, interior_world: Vector2, anchor_world: Vector2, agent_pos: Vector2) -> Vector2:
	if not bc.is_coordinate_blocked(interior_world):
		return interior_world
	if not bc.is_coordinate_blocked(anchor_world):
		return anchor_world
	var start_tile = bc.world_to_tile(agent_pos)
	var ri = _resolve_blocked_astar_goal(bc, bc.world_to_tile(interior_world), start_tile, 32)
	if ri != null:
		return bc.tile_to_world(ri)
	var ra = _resolve_blocked_astar_goal(bc, bc.world_to_tile(anchor_world), start_tile, 32)
	if ra != null:
		return bc.tile_to_world(ra)
	return Vector2.ZERO

func _tile_key(tile: Vector2i) -> String:
	return str(tile.x) + "," + str(tile.y)

func _reconstruct_first_step(came_from: Dictionary, start_tile: Vector2i, goal_tile: Vector2i) -> Vector2i:
	var cursor = goal_tile
	var cursor_key = _tile_key(cursor)
	if not came_from.has(cursor_key):
		return start_tile

	while true:
		var prev: Vector2i = came_from.get(_tile_key(cursor), start_tile)
		if prev == start_tile:
			return cursor
		if prev == cursor:
			return start_tile
		cursor = prev
	return start_tile
