extends Node

# Configuration
var backend_url = "http://localhost:8080"
var use_delta_endpoint = true  # Use lightweight /state/delta instead of full /state
var runtime_awareness_radius = 180.0
var auto_start_backend = true
var backend_wait_attempts = 24
var backend_wait_interval = 0.5
var tile_size = 32.0
var initial_generated_agent_count = 1
var initial_object_seed_enabled = true

# References
@onready var agents_container = get_node("../World/Agents")
@onready var debug_label = get_node("../UI/DebugLabel")
@onready var world_node = get_node("../World")
@onready var dialogue_panel = get_node("../UI/DialoguePanel")
@onready var dialogue_target_label = get_node("../UI/DialoguePanel/DialogueVBox/DialogueTargetLabel")
@onready var dialogue_log = get_node("../UI/DialoguePanel/DialogueVBox/DialogueLog")
@onready var dialogue_input = get_node("../UI/DialoguePanel/DialogueVBox/DialogueInputRow/DialogueInput")
@onready var dialogue_send_button = get_node("../UI/DialoguePanel/DialogueVBox/DialogueInputRow/DialogueSendButton")
@onready var dialogue_status = get_node("../UI/DialoguePanel/DialogueVBox/DialogueStatus")
@onready var loading_overlay = get_node("../UI/LoadingOverlay")
@onready var loading_status_label = get_node("../UI/LoadingOverlay/VBox/Status")

# Agent and Player scenes to instantiate
var agent_scene = preload("res://agent.tscn")
var player_scene = preload("res://player.tscn")

# Track spawned agents, player, and locations
var agent_nodes = {}
var player_node = null
var player_name = "Player"  # Default player name
var locations = {}  # Maps location name -> location bounds/data
var agent_positions = {}  # Maps agent name -> {x, y, location}
var last_runtime_request = {}
var turn_request_in_flight = false
var pending_move_action = {}
var location_overlays: Node2D = null
var object_overlays: Node2D = null
var world_objects: Array = []
var blocked_tiles: Dictionary = {}
var active_dialogue_target = ""
var dialogue_request_in_flight = false
var player_has_local_movement = false

# Save/load file path
var save_file_path = "user://game_state.json"

func _set_loading(visible: bool, message: String = ""):
	if loading_overlay != null:
		loading_overlay.visible = visible
		if message != "" and loading_status_label != null:
			loading_status_label.text = message

func _ready():
	print("Backend Connector initialized")
	_ensure_location_overlay_container()
	_ensure_object_overlay_container()
	_wire_dialogue_ui()
	_set_loading(true, "Connecting to server...")

	var backend_ready = await _wait_for_backend_ready()
	if not backend_ready:
		_set_loading(true, "ERROR: Server unreachable. Run start_server.bat")
		push_error("Backend not reachable at " + backend_url + ". Check start_server.bat or run server manually.")
		return

	# Now proceed with initialization
	if FileAccess.file_exists(save_file_path):
		print("Found saved state, loading...")
		_set_loading(true, "Loading saved world...")
		await _load_and_initialize_state()
	else:
		print("No saved state, creating new world...")
		_set_loading(true, "Creating world...")
		await _initialize_new_world()

	# Generate schedules for all agents before allowing play
	await _bootstrap_agent_schedules()

	# Hide loading overlay — player can now interact
	_set_loading(false)

	# Fetch locations and do initial state poll now that everything is ready
	await _fetch_locations_async()
	await _fetch_state_snapshot_async()
	if agent_nodes.is_empty():
		await _fetch_agents_snapshot_async()
	_fetch_objects()
	call_deferred("_poll_backend")

func _wait_for_backend_ready() -> bool:
	"""Verify backend availability and optionally start it via bat file."""
	var started_backend = false
	for i in range(backend_wait_attempts):
		if await _ping_backend():
			print("Backend is reachable")
			return true

		if auto_start_backend and not started_backend:
			print("Backend not reachable, attempting start_server.bat...")
			_start_backend_server()
			started_backend = true

		await get_tree().create_timer(backend_wait_interval).timeout

	return false

func _ping_backend() -> bool:
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request(backend_url + "/ping")
	if err != OK:
		http.queue_free()
		return false

	var response = await http.request_completed
	http.queue_free()
	var response_code = response[1]
	return response_code == 200

func _start_backend_server():
	"""Start the Java backend server"""
	var server_bat = "C:/Program Files/Git/agentic-paradise/start_server.bat"
	var exit_code = OS.create_process("cmd.exe", ["/c", server_bat])
	
	if exit_code == 0:
		print("Backend server launch command executed")
	else:
		push_error("Failed to start backend server. Exit code: " + str(exit_code))

func _reset_simulation_clock_to_noon() -> void:
	"""Reset simulation time to 12:00 PM for a newly initialized world."""
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request(backend_url + "/time/reset-to-noon", [], HTTPClient.METHOD_POST)
	if err != OK:
		push_error("Failed to reset simulation time to noon")
		http.queue_free()
		return

	var response = await http.request_completed
	var code = response[1]
	if code != 200:
		push_error("Backend rejected noon reset: " + str(code))
	http.queue_free()

func _initialize_new_world():
	"""Create initial agents and top-level locations with spatial bounds."""
	await _reset_simulation_clock_to_noon()

	var locations_config = {
		"market": {"type": "market", "bounds": {"minX": 0, "maxX": 600, "minY": 0, "maxY": 500}},
		"tavern": {"type": "tavern", "bounds": {"minX": 700, "maxX": 1200, "minY": 0, "maxY": 450}},
		"coffee_shop": {"type": "cafe", "bounds": {"minX": 1300, "maxX": 1800, "minY": 0, "maxY": 400}},
		"street": {"type": "outside", "bounds": {"minX": 0, "maxX": 1800, "minY": 0, "maxY": 1200}},
		"town_square": {"type": "public", "bounds": {"minX": 400, "maxX": 1100, "minY": 600, "maxY": 1200}},
		"home": {"type": "residential", "bounds": {"minX": 80, "maxX": 350, "minY": 620, "maxY": 980}}
	}
	
	print("[INIT] Creating locations...")
	for loc_name in locations_config.keys():
		var loc_data = locations_config[loc_name]
		var created = await _create_location_async(loc_name, loc_data.get("type", "generic"), loc_data.get("bounds", {}))
		if not created:
			push_error("[INIT] Failed to create location: " + loc_name)

	# Ensure backend has persisted location registry before adding agents/player.
	await get_tree().create_timer(0.5).timeout
	await _fetch_locations_async()

	if initial_object_seed_enabled:
		print("[INIT] Seeding world objects...")
		_set_loading(true, "Populating world objects...")
		await _seed_world_objects()

	print("[INIT] Generating initial agents...")
	var generated = await _generate_agents_async(initial_generated_agent_count)
	if generated <= 0:
		push_warning("[INIT] Agent generation unavailable, creating deterministic fallback agent")
		await _create_agent_async({
			"name": "Nora",
			"location": "town_square",
			"memories": ["I should keep an eye on town gossip today."],
			"activity": "Surveying the town square."
		})
	
	# Create the player character (await so bootstrap can lock input immediately after)
	print("[INIT] Creating player character...")
	await _create_player_async({
		"name": "Player",
		"location": "town_square",
		"activity": "Looking around the town square",
		"memories": ["I've arrived in this strange town.", "I should explore and meet the locals."]
	})

	print("[INIT] Initialization complete!")

func _generate_agents_async(count: int) -> int:
	"""Generate initial NPCs via backend LLM pipeline. Returns created agent count."""
	var http = HTTPRequest.new()
	add_child(http)
	var headers = ["Content-Type: application/json"]
	var payload = {
		"count": clamp(count, 1, 3),
		"replaceExistingAgents": true,
		"trackFirstAgent": true,
		"enableRepairPass": true,
		"preferredLocation": "town_square",
		"prompt": "Create one grounded but psychologically nuanced town resident with subtle contradictions."
	}

	var err = http.request(
		backend_url + "/agents/generate",
		headers,
		HTTPClient.METHOD_POST,
		JSON.stringify(payload)
	)
	if err != OK:
		http.queue_free()
		push_error("[INIT] Failed to send /agents/generate request")
		return 0

	var response = await http.request_completed
	http.queue_free()
	var code = response[1]
	if code != 200 and code != 201:
		push_error("[INIT] /agents/generate failed with code: " + str(code))
		return 0

	var body_text = response[3].get_string_from_utf8()
	var json = JSON.new()
	if json.parse(body_text) != OK:
		push_error("[INIT] Could not parse /agents/generate response")
		return 0

	var data = json.data
	if not (data is Dictionary):
		return 0

	var result = data.get("result", {})
	if not (result is Dictionary):
		return 0

	var created_agents = result.get("agents", [])
	if created_agents is Array:
		print("[INIT] Generated ", created_agents.size(), " agent(s)")
		return created_agents.size()

	return int(result.get("generatedCount", 0))

func _create_location_async(location_name: String, type: String = "generic", bounds: Dictionary = {}) -> bool:
	"""POST request to create a location with optional spatial bounds and await response."""
	var http = HTTPRequest.new()
	add_child(http)
	var headers = ["Content-Type: application/json"]
	var body_dict = {"name": location_name, "type": type}
	if bounds.size() > 0:
		body_dict["minX"] = bounds.get("minX", 0.0)
		body_dict["maxX"] = bounds.get("maxX", 100.0)
		body_dict["minY"] = bounds.get("minY", 0.0)
		body_dict["maxY"] = bounds.get("maxY", 100.0)

	var err = http.request(
		backend_url + "/locations",
		headers,
		HTTPClient.METHOD_POST,
		JSON.stringify(body_dict)
	)
	if err != OK:
		http.queue_free()
		return false

	var response = await http.request_completed
	http.queue_free()
	var response_code = response[1]
	if response_code == 200 or response_code == 201:
		print("Location created: ", location_name)
		return true

	push_error("Location creation failed for " + location_name + " code: " + str(response_code))
	return false

func _seed_world_objects() -> void:
	"""Create baseline object types and location objects/anchors for the current world."""
	var type_definitions = _get_default_object_type_definitions()
	for type_name in type_definitions.keys():
		await _define_object_type_async(type_name, type_definitions[type_name])

	var objects = _get_default_world_objects()
	var success_count = 0
	for object_data in objects:
		if await _upsert_object_instance_async(object_data):
			success_count += 1

	print("[INIT] Seeded ", success_count, "/", objects.size(), " objects")
	# Seed building perimeter wall tiles so agents respect building walls and only
	# enter through registered entrance_anchor door tiles.
	await _seed_building_wall_tiles(objects)
	await _log_object_count_async("[INIT]")

func _log_object_count_async(prefix: String = "[OBJECTS]") -> void:
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request(backend_url + "/objects")
	if err != OK:
		http.queue_free()
		push_error(prefix + " Could not fetch /objects after seed")
		return

	var response = await http.request_completed
	http.queue_free()
	if response[1] != 200:
		push_error(prefix + " /objects returned code: " + str(response[1]))
		return

	var json = JSON.new()
	if json.parse(response[3].get_string_from_utf8()) != OK:
		push_error(prefix + " Failed parsing /objects response")
		return

	var data = json.data
	var objects = data.get("objects", []) if data is Dictionary else []
	print(prefix, " Backend object count: ", objects.size())

func _define_object_type_async(type_name: String, properties: Dictionary) -> bool:
	var http = HTTPRequest.new()
	add_child(http)
	var headers = ["Content-Type: application/json"]
	var body = JSON.stringify({"properties": properties})
	var err = http.request(
		backend_url + "/objects/types/" + type_name,
		headers,
		HTTPClient.METHOD_POST,
		body
	)
	if err != OK:
		http.queue_free()
		push_error("[OBJECTS] Failed to define object type: " + type_name)
		return false

	var response = await http.request_completed
	http.queue_free()
	var response_code = response[1]
	if response_code != 200 and response_code != 201:
		push_error("[OBJECTS] Type definition failed for " + type_name + " with code: " + str(response_code))
		return false
	return true

func _upsert_object_instance_async(object_data: Dictionary) -> bool:
	var object_id = str(object_data.get("id", "")).strip_edges()
	if object_id == "":
		push_error("[OBJECTS] Missing object id in seed data")
		return false

	var payload = {
		"type": object_data.get("type", "fixture"),
		"name": object_data.get("name", object_id),
		"x": float(object_data.get("x", 0.0)),
		"y": float(object_data.get("y", 0.0)),
		"location": object_data.get("location", ""),
		"properties": object_data.get("properties", {})
	}

	var http = HTTPRequest.new()
	add_child(http)
	var headers = ["Content-Type: application/json"]
	var err = http.request(
		backend_url + "/objects/" + object_id,
		headers,
		HTTPClient.METHOD_POST,
		JSON.stringify(payload)
	)
	if err != OK:
		http.queue_free()
		push_error("[OBJECTS] Failed to upsert object: " + object_id)
		return false

	var response = await http.request_completed
	http.queue_free()
	var response_code = response[1]
	if response_code != 200 and response_code != 201:
		push_error("[OBJECTS] Upsert failed for " + object_id + " with code: " + str(response_code))
		return false
	return true

func _seed_building_wall_tiles(seeded_objects: Array) -> void:
	"""Compute building perimeter wall tiles from location bounds and seed them to
	the backend via POST /tiles/blocked.  Tiles that contain an entrance_anchor
	are excluded so those squares remain passable (they are the doors).
	All tile positions are snapped to the 32-pixel grid."""

	if locations.is_empty():
		await _fetch_locations_async()
	if locations.is_empty():
		push_error("[WALLS] Locations not loaded yet, skipping wall seed.")
		return

	# Build a quick-lookup set of entrance-anchor tile keys ("<x>,<y>").
	var door_tiles: Dictionary = {}
	for obj in seeded_objects:
		if str(obj.get("type", "")) == "entrance_anchor":
			var tx = snapped(float(obj.get("x", 0.0)), 32.0)
			var ty = snapped(float(obj.get("y", 0.0)), 32.0)
			door_tiles[str(tx) + "," + str(ty)] = true

	# Collect perimeter tiles for every enclosed (non-transit) location.
	var blocked: Array = []
	blocked_tiles.clear()
	for loc_name in locations.keys():
		var loc = locations[loc_name]
		if not (loc is Dictionary):
			continue
		if _is_transit_location_name(loc_name, loc):
			continue

		var min_x = snapped(float(loc.get("minX", 0.0)), 32.0)
		var max_x = snapped(float(loc.get("maxX", 0.0)), 32.0)
		var min_y = snapped(float(loc.get("minY", 0.0)), 32.0)
		var max_y = snapped(float(loc.get("maxY", 0.0)), 32.0)

		if max_x <= min_x or max_y <= min_y:
			continue

		var perim = _compute_perimeter_tiles(min_x, max_x, min_y, max_y)
		for tile in perim:
			var key = str(tile.x) + "," + str(tile.y)
			if not door_tiles.has(key):
				blocked.append({"x": tile.x, "y": tile.y})
				blocked_tiles[key] = true

	if blocked.is_empty():
		print("[WALLS] No wall tiles to seed.")
		return

	print("[WALLS] Seeded ", blocked.size(), " wall tiles locally (client-side collision).")

func _is_transit_location_name(loc_name: String, loc: Dictionary) -> bool:
	"""Return true for open/outdoor locations that should NOT have perimeter walls."""
	var ltype = str(loc.get("type", "")).to_lower()
	var lname = loc_name.to_lower()
	return ltype in ["street", "outside", "road"] \
		or lname.contains("street") or lname.contains("road") \
		or lname.contains("square") or lname.contains("park") or lname.contains("plaza") \
		or lname.contains("path") or lname.contains("sidewalk") \
		or lname == "outside"

func _blocked_tile_key(world_position: Vector2) -> String:
	var snapped_pos = snap_to_tile(world_position)
	return str(snapped_pos.x) + "," + str(snapped_pos.y)

func is_coordinate_blocked(world_position: Vector2) -> bool:
	"""Client-side movement gate for impassable wall tiles.
	The wall tile occupies the entire 32x32 coordinate cell."""
	return blocked_tiles.has(_blocked_tile_key(world_position))

func _compute_perimeter_tiles(min_x: float, max_x: float, min_y: float, max_y: float) -> Array:
	"""Return the set of 32px-snapped tile positions that form the outer edge of the
	bounding box. Each entry covers its entire 32x32 coordinate cell."""
	var tiles: Array = []
	var step := 32.0

	# Top edge and bottom edge
	var tx = min_x
	while tx <= max_x:
		tiles.append(Vector2(tx, min_y))
		tiles.append(Vector2(tx, max_y))
		tx += step

	# Left and right columns, excluding corners already added above
	var ty = min_y + step
	while ty < max_y:
		tiles.append(Vector2(min_x, ty))
		tiles.append(Vector2(max_x, ty))
		ty += step

	return tiles

func _get_default_object_type_definitions() -> Dictionary:
	return {
		"fixture": {
			"anchor": false,
			"portable": false,
			"interactive": true,
			"interactionMode": "nearby",
			"interactionRadius": 48,
			"tags": ["environment"]
		},
		"entrance_anchor": {
			"anchor": true,
			"anchorKind": "entrance",
			"portable": false,
			"interactive": true,
			"interactionMode": "nearby",
			"interactionRadius": 40,
			"tags": ["entrance", "pathing"]
		},
		"idle_zone": {
			"anchor": true,
			"anchorKind": "idle_zone",
			"portable": false,
			"interactive": true,
			"interactionMode": "nearby",
			"interactionRadius": 96,
			"tags": ["ambient", "social"]
		},
		"work_spot": {
			"anchor": true,
			"anchorKind": "work",
			"portable": false,
			"interactive": true,
			"interactionMode": "adjacent",
			"interactionRadius": 32,
			"tags": ["task", "service"]
		},
		"decor": {
			"anchor": false,
			"portable": true,
			"interactive": true,
			"interactionMode": "adjacent",
			"interactionRadius": 32,
			"tags": ["decorative", "stealable", "writable"]
		}
	}

func _get_default_world_objects() -> Array:
	return [
		# Street pathing and transitions
		{"id":"street_idle_north","type":"idle_zone","name":"North Sidewalk Flow","x":900,"y":180,"location":"street","properties":{"zoneRadius":120,"pathingHint":true}},
		{"id":"street_idle_central","type":"idle_zone","name":"Central Street Flow","x":900,"y":540,"location":"street","properties":{"zoneRadius":140,"pathingHint":true}},
		{"id":"street_idle_south","type":"idle_zone","name":"South Sidewalk Flow","x":900,"y":980,"location":"street","properties":{"zoneRadius":120,"pathingHint":true}},

		# Market
		{"id":"market_entry_street","type":"entrance_anchor","name":"Market Entrance","x":600,"y":260,"location":"market","properties":{"linkedHint":"street","building":"market"}},
		{"id":"market_counter","type":"work_spot","name":"Produce Counter","x":460,"y":200,"location":"market","properties":{"activity":["sell","buy","trade"],"adjacentPreferred":true}},
		{"id":"market_crates","type":"fixture","name":"Crate Stack","x":250,"y":300,"location":"market","properties":{"inspectable":true}},
		{"id":"market_notice_wall","type":"decor","name":"Market Notice Wall","x":120,"y":120,"location":"market","properties":{"writable":true,"graffiti":true}},
		{"id":"market_idle_zone","type":"idle_zone","name":"Market Browsing Zone","x":320,"y":260,"location":"market","properties":{"zoneRadius":100}},

		# Tavern
		{"id":"tavern_entry_street","type":"entrance_anchor","name":"Tavern Entrance","x":700,"y":230,"location":"tavern","properties":{"linkedHint":"street","building":"tavern"}},
		{"id":"tavern_bar","type":"work_spot","name":"Bar Counter","x":980,"y":140,"location":"tavern","properties":{"activity":["serve","chat","order"]}},
		{"id":"tavern_table_a","type":"fixture","name":"Round Table A","x":830,"y":300,"location":"tavern","properties":{"sitAround":true}},
		{"id":"tavern_table_b","type":"fixture","name":"Round Table B","x":1030,"y":320,"location":"tavern","properties":{"sitAround":true}},
		{"id":"tavern_wall_sign","type":"decor","name":"Tavern Wall Sign","x":1140,"y":80,"location":"tavern","properties":{"stealable":true,"writable":true}},
		{"id":"tavern_idle_zone","type":"idle_zone","name":"Tavern Common Zone","x":920,"y":260,"location":"tavern","properties":{"zoneRadius":110}},

		# Coffee shop
		{"id":"coffee_entry_street","type":"entrance_anchor","name":"Coffee Shop Entrance","x":1300,"y":210,"location":"coffee_shop","properties":{"linkedHint":"street","building":"coffee_shop"}},
		{"id":"coffee_machine","type":"work_spot","name":"Espresso Station","x":1680,"y":120,"location":"coffee_shop","properties":{"activity":["brew","calibrate","clean"]}},
		{"id":"coffee_register","type":"work_spot","name":"Register","x":1580,"y":140,"location":"coffee_shop","properties":{"activity":["charge","serve"]}},
		{"id":"coffee_table","type":"fixture","name":"Window Table","x":1440,"y":290,"location":"coffee_shop","properties":{"sitAround":true}},
		{"id":"coffee_bulletin","type":"decor","name":"Community Bulletin Board","x":1335,"y":95,"location":"coffee_shop","properties":{"writable":true,"noteBoard":true}},
		{"id":"coffee_idle_zone","type":"idle_zone","name":"Coffee Lounge Zone","x":1520,"y":250,"location":"coffee_shop","properties":{"zoneRadius":90}},

		# Town square
		{"id":"square_stage","type":"fixture","name":"Public Stage","x":620,"y":760,"location":"town_square","properties":{"performable":true}},
		{"id":"square_fountain","type":"fixture","name":"Fountain","x":780,"y":860,"location":"town_square","properties":{"landmark":true}},
		{"id":"square_notice","type":"decor","name":"Public Notice Wall","x":980,"y":720,"location":"town_square","properties":{"writable":true,"graffiti":true}},
		{"id":"square_idle_zone","type":"idle_zone","name":"Town Square Gathering","x":760,"y":900,"location":"town_square","properties":{"zoneRadius":140}},

		# Home
		{"id":"home_entry_street","type":"entrance_anchor","name":"Home Entrance","x":350,"y":760,"location":"home","properties":{"linkedHint":"street","building":"home"}},
		{"id":"home_bed","type":"work_spot","name":"Bedside","x":170,"y":860,"location":"home","properties":{"activity":["rest","sleep"]}},
		{"id":"home_kitchen","type":"work_spot","name":"Kitchen Counter","x":250,"y":720,"location":"home","properties":{"activity":["cook","clean"]}},
		{"id":"home_picture","type":"decor","name":"Framed Picture","x":120,"y":680,"location":"home","properties":{"stealable":true,"writable":false}},
		{"id":"home_idle_zone","type":"idle_zone","name":"Home Living Area","x":220,"y":800,"location":"home","properties":{"zoneRadius":90}}
	]

# LOCATION FUNCTIONS
func _create_location(location_name: String, type: String = "generic", bounds: Dictionary = {}):
	"""POST request to create a location with optional spatial bounds (non-async)
	Example: _create_location("market:produce", "market", {"minX": 0, "maxX": 100, "minY": 0, "maxY": 100})
	"""
	var http = HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(_on_location_created.bind(http))
	
	var headers = ["Content-Type: application/json"]
	var body_dict = {"name": location_name, "type": type}
	
	# Add bounds if provided
	if bounds.size() > 0:
		body_dict["minX"] = bounds.get("minX", 0.0)
		body_dict["maxX"] = bounds.get("maxX", 100.0)
		body_dict["minY"] = bounds.get("minY", 0.0)
		body_dict["maxY"] = bounds.get("maxY", 100.0)
	
	var body = JSON.stringify(body_dict)
	
	var error = http.request(
		backend_url + "/locations",
		headers,
		HTTPClient.METHOD_POST,
		body
	)
	
	if error == OK:
		print("Creating location: ", location_name, " (type: ", type, ")", " bounds: ", bounds)
	else:
		push_error("Failed to create location: " + location_name)
		http.queue_free()

func _on_location_created(result, response_code, headers, body, http):
	"""Handle location creation response"""
	if response_code == 200 or response_code == 201:
		var json = JSON.new()
		json.parse(body.get_string_from_utf8())
		var location_data = json.data
		print("Location created: ", location_data.get("name", "unknown"))
	else:
		push_error("Location creation failed with code: " + str(response_code))
	
	# Now it's safe to free
	if is_instance_valid(http):
		http.queue_free()
func _fetch_locations():
	"""GET request to fetch all locations"""
	var http = HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(_on_locations_received.bind(http))
	
	var error = http.request(backend_url + "/locations")
	if error != OK:
		push_error("Failed to fetch locations")

func _fetch_locations_async() -> bool:
	"""GET request to fetch all locations and wait for completion."""
	var http = HTTPRequest.new()
	add_child(http)
	var error = http.request(backend_url + "/locations")
	if error != OK:
		http.queue_free()
		push_error("Failed to fetch locations")
		return false

	var response = await http.request_completed
	http.queue_free()
	var response_code = response[1]
	if response_code != 200:
		push_error("Failed to fetch locations, code: " + str(response_code))
		return false

	var json = JSON.new()
	if json.parse(response[3].get_string_from_utf8()) != OK:
		push_error("Failed to parse locations response")
		return false

	return _apply_locations_from_response_data(json.data)

func _apply_locations_from_response_data(response_data) -> bool:
	var locs = []
	if response_data is Dictionary and response_data.has("locations"):
		locs = response_data.locations
	elif response_data is Array:
		locs = response_data
	else:
		push_error("Unexpected locations format: " + str(response_data))
		return false

	for loc_obj in locs:
		if loc_obj is Dictionary:
			var loc_name = loc_obj.get("name", "unknown")
			locations[loc_name] = {
				"name": loc_name,
				"type": loc_obj.get("type", "generic"),
				"minX": loc_obj.get("minX", 0.0),
				"maxX": loc_obj.get("maxX", 100.0),
				"minY": loc_obj.get("minY", 0.0),
				"maxY": loc_obj.get("maxY", 100.0),
				"centerX": (loc_obj.get("minX", 0.0) + loc_obj.get("maxX", 100.0)) / 2.0,
				"centerY": (loc_obj.get("minY", 0.0) + loc_obj.get("maxY", 100.0)) / 2.0
			}

	print("Loaded %d locations with spatial data: %s" % [locations.size(), locations.keys()])
	_redraw_location_overlays()
	return true

func _on_locations_received(result, response_code, headers, body, http):
	"""Handle locations response with spatial bounds"""
	if is_instance_valid(http):
		http.queue_free()
	
	if response_code == 200:
		var json = JSON.new()
		if json.parse(body.get_string_from_utf8()) == OK:
			_apply_locations_from_response_data(json.data)
		else:
			push_error("Failed to parse locations response")
	else:
		push_error("Failed to fetch locations, code: " + str(response_code))

func _fetch_objects():
	"""GET request to fetch all world objects for visualization."""
	var http = HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(_on_objects_received.bind(http))

	var error = http.request(backend_url + "/objects")
	if error != OK:
		push_error("Failed to fetch objects")

func _on_objects_received(result, response_code, headers, body, http):
	"""Handle object list response and redraw object overlays."""
	if is_instance_valid(http):
		http.queue_free()

	if response_code != 200:
		push_error("Failed to fetch objects, code: " + str(response_code))
		return

	var json = JSON.new()
	if json.parse(body.get_string_from_utf8()) != OK:
		push_error("Failed to parse objects response JSON")
		return

	var response_data = json.data
	if response_data is Dictionary and response_data.has("objects") and response_data.objects is Array:
		world_objects = response_data.objects
	else:
		world_objects = []

	_redraw_object_overlays()

# AGENT FUNCTIONS
func _create_agent_async(agent_data) -> void:
	"""Async POST request to create an agent, awaits completion"""
	var http = HTTPRequest.new()
	add_child(http)
	
	var headers = ["Content-Type: application/json"]
	var body = JSON.stringify(agent_data)
	
	var error = http.request(
		backend_url + "/agents",
		headers,
		HTTPClient.METHOD_POST,
		body
	)
	
	if error == OK:
		# Wait for the request to complete
		var response = await http.request_completed
		var result = response[0]
		var response_code = response[1]
		var response_body = response[3]
		
		if response_code == 200 or response_code == 201:
			print("[AGENT] Created agent: " + agent_data.get("name", "unknown"))
		else:
			push_error("[AGENT] Agent creation failed for " + agent_data.get("name", "unknown") + " with code: " + str(response_code) + " body: " + response_body.get_string_from_utf8())
	else:
		push_error("[AGENT] Failed to send agent creation request: " + agent_data.get("name", "unknown"))
	
	http.queue_free()

func _create_agent(agent_data):
	"""POST request to create an agent"""
	var http = HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(_on_agent_created.bind(http))  # Pass http as parameter
	
	var headers = ["Content-Type: application/json"]
	var body = JSON.stringify(agent_data)
	
	var error = http.request(
		backend_url + "/agents",
		headers,
		HTTPClient.METHOD_POST,
		body
	)
	
	if error != OK:
		push_error("Failed to create agent: " + agent_data.name)
		http.queue_free()  # Only free if request failed immediately

func _on_agent_created(result, response_code, headers, body, http):
	"""Handle agent creation response"""
	if response_code == 200 or response_code == 201:
		var json = JSON.new()
		json.parse(body.get_string_from_utf8())
		var agent_data = json.data
		print("Agent created: ", agent_data.get("name", "unknown"))
	else:
		push_error("Agent creation failed with code: " + str(response_code))
	
	# Now it's safe to free
	if is_instance_valid(http):
		http.queue_free()

# PLAYER FUNCTIONS
func _create_player(player_data):
	"""POST request to create a player character (fire-and-forget, legacy)."""
	# Delegate to the async version but don't await it.
	_create_player_async(player_data)

func _create_player_async(player_data) -> void:
	"""Async POST request to create a player character. Awaitable.
	The player node is always spawned regardless of server response so the
	client is never left without a controllable character."""
	print("Starting player creation with data: ", player_data)

	# Spawn the player node immediately — don't wait for server confirmation.
	if player_node == null:
		var player_node_instance = player_scene.instantiate()
		player_node_instance.name = "Player"
		agents_container.add_child(player_node_instance)
		player_node = player_node_instance
		print("Spawned player node: ", player_node_instance.name)
		# Small delay to let _ready() run on the new node
		await get_tree().create_timer(0.1).timeout

	# Now tell the server about the player (best-effort — may already exist).
	var http = HTTPRequest.new()
	add_child(http)
	var headers = ["Content-Type: application/json"]
	var error = http.request(
		backend_url + "/player",
		headers,
		HTTPClient.METHOD_POST,
		JSON.stringify(player_data)
	)
	if error != OK:
		push_error("Failed to send player creation request")
		http.queue_free()
		return

	var response = await http.request_completed
	http.queue_free()
	var response_code = response[1]
	print("Player creation response - code: ", response_code)
	if response_code != 200 and response_code != 201:
		# 409 means player already exists on server — that's fine, we still have the node.
		print("Player server sync returned ", response_code, " (may already exist — continuing)")

	# Allow one-time backend position sync after startup/load.
	player_has_local_movement = false

func move_player_to_location(location_name: String):
	"""Move the player to a new location"""
	if player_node != null:
		player_node.move_to_location(location_name, locations)
		# moving the player is an explicit user action; drive simulation
		print("[PLAYER] moved to ", location_name, " – polling backend")
		call_deferred("_poll_backend")

func get_player_position() -> Vector2:
	"""Get the player's current position in the world"""
	if player_node != null:
		return player_node.get_position_in_location()
	return Vector2.ZERO

func _wire_dialogue_ui():
	"""Connect dialogue input events for player-to-agent conversations."""
	if dialogue_send_button != null:
		dialogue_send_button.pressed.connect(_on_dialogue_send_pressed)
	if dialogue_input != null:
		dialogue_input.text_submitted.connect(_on_dialogue_text_submitted)
	if dialogue_panel != null:
		dialogue_panel.visible = false

func open_dialogue_panel(target_agent: String):
	"""Open the dialogue panel targeting a nearby agent."""
	active_dialogue_target = target_agent
	if dialogue_panel != null:
		dialogue_panel.visible = true
	if dialogue_target_label != null:
		dialogue_target_label.text = "Talking to: " + target_agent
	if dialogue_status != null:
		dialogue_status.text = "Type a message and send (Esc to close)"
	if dialogue_input != null:
		dialogue_input.grab_focus()
	if player_node != null:
		player_node.set_action_lock(true, "dialogue")

func close_dialogue_panel():
	if dialogue_panel != null:
		dialogue_panel.visible = false
	if dialogue_input != null:
		dialogue_input.release_focus()
	if player_node != null and not dialogue_request_in_flight:
		player_node.set_action_lock(false)
	active_dialogue_target = ""

func is_dialogue_open() -> bool:
	return dialogue_panel != null and dialogue_panel.visible

func is_dialogue_text_input_active() -> bool:
	return is_dialogue_open() and dialogue_input != null and dialogue_input.has_focus()

func append_dialogue_line(speaker: String, text: String):
	if dialogue_log == null:
		return
	var clean_speaker = str(speaker).strip_edges()
	var clean_text = str(text).strip_edges()
	# Reject null/empty speakers; fall back to known dialogue target
	if clean_speaker == "" or clean_speaker.to_lower() == "null" or clean_speaker == "<null>":
		clean_speaker = str(active_dialogue_target).strip_edges()
	if clean_speaker == "" or clean_speaker.to_lower() == "null" or clean_speaker == "<null>":
		return
	# Reject null/empty/placeholder text
	if clean_text == "" or clean_text == "<null>" or clean_text == "...":
		return
	var lower_text = clean_text.to_lower()
	if lower_text == "null" or lower_text == "none" or lower_text == "undefined":
		return
	var line = "%s: %s" % [clean_speaker, clean_text]
	if dialogue_log.text == "":
		dialogue_log.text = line
	else:
		dialogue_log.text += "\n" + line
	dialogue_log.scroll_to_line(9999)

func _set_dialogue_busy(is_busy: bool):
	dialogue_request_in_flight = is_busy
	if dialogue_send_button != null:
		dialogue_send_button.disabled = is_busy
	if dialogue_input != null:
		dialogue_input.editable = not is_busy
	if dialogue_status != null:
		dialogue_status.text = "Thinking..." if is_busy else "Type a message and send"
	if player_node != null:
		player_node.set_action_lock(is_busy, "speaking")

func _on_dialogue_send_pressed():
	_submit_dialogue_message()

func _on_dialogue_text_submitted(_submitted_text: String):
	_submit_dialogue_message()

func _submit_dialogue_message():
	if dialogue_input == null or dialogue_request_in_flight:
		return
	var message = dialogue_input.text.strip_edges()
	if message == "":
		return
	if active_dialogue_target == "":
		if dialogue_status != null:
			dialogue_status.text = "No target selected. Press E near an NPC."
		return

	append_dialogue_line(player_name, message)
	enqueue_player_action(
		player_name,
		"speak",
		active_dialogue_target,
		"",
		get_player_position().x,
		get_player_position().y,
		"Speaking with " + active_dialogue_target,
		message
	)
	dialogue_input.text = ""
	_set_dialogue_busy(true)



func _poll_backend():
	"""Poll the backend /state/delta endpoint (lightweight) or /state (full)"""
	print("[BACKEND] _poll_backend called")
	var http = HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(_on_state_received.bind(http))
	
	var endpoint = "/state/delta" if use_delta_endpoint else "/state"
	var error = http.request(backend_url + endpoint)
	if error != OK:
		push_error("Failed to poll backend on " + endpoint)
		call_deferred("_startup_hydrate_agents_if_missing")

func _startup_hydrate_agents_if_missing() -> void:
	if not agent_nodes.is_empty():
		return
	await _fetch_state_snapshot_async()
	if agent_nodes.is_empty():
		await _fetch_agents_snapshot_async()

func _fetch_state_snapshot_async() -> bool:
	"""Fetch full /state once and apply it, used for startup reliability."""
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request(backend_url + "/state")
	if err != OK:
		http.queue_free()
		return false

	var response = await http.request_completed
	http.queue_free()
	if response[1] != 200:
		return false

	var json = JSON.new()
	if json.parse(response[3].get_string_from_utf8()) != OK:
		return false

	if json.data is Dictionary:
		_update_world(json.data)
		return true
	return false

func _fetch_agents_snapshot_async() -> bool:
	"""Fetch /agents and force-spawn missing NPC nodes as startup fallback."""
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request(backend_url + "/agents")
	if err != OK:
		http.queue_free()
		return false

	var response = await http.request_completed
	http.queue_free()
	if response[1] != 200:
		return false

	var json = JSON.new()
	if json.parse(response[3].get_string_from_utf8()) != OK:
		return false

	var data = json.data
	if not (data is Dictionary) or not data.has("agents") or not (data.agents is Array):
		return false

	for agent_data in data.agents:
		if not (agent_data is Dictionary):
			continue
		var agent_name = str(agent_data.get("name", ""))
		if agent_name == "" or agent_name == player_name:
			continue
		if not agent_nodes.has(agent_name):
			var agent_node = agent_scene.instantiate()
			agent_node.name = agent_name
			agents_container.add_child(agent_node)
			agent_nodes[agent_name] = agent_node
		var base = Vector2(float(agent_data.get("x", 0.0)), float(agent_data.get("y", 0.0)))
		agent_nodes[agent_name].update_from_backend(agent_data, locations, base)

	if not agent_nodes.is_empty():
		print("[INIT] Agent snapshot hydrated NPC count: ", agent_nodes.size())
		return true
	return false

func _on_state_received(result, response_code, headers, body, http):
	"""Handle state response"""
	if is_instance_valid(http):
		http.queue_free()
	
	if response_code != 200:
		debug_label.text = "Backend error: " + str(response_code)
		return
	
	# Parse JSON
	var json = JSON.new()
	var parse_result = json.parse(body.get_string_from_utf8())
	
	if parse_result != OK:
		push_error("Failed to parse JSON from backend")
		return
	
	var state = json.data
	_update_world(state)
	if world_objects.is_empty():
		_fetch_objects()

func _update_world(state):
	"""Update agent positions and states from backend data (delta or full)"""
	# Update locations from response if available (keep them fresh)
	if state.has("location_states"):
		var locs = state.location_states
		for loc_obj in locs:
			if loc_obj is Dictionary:
				var loc_name = loc_obj.get("name", "unknown")
				locations[loc_name] = {
					"name": loc_name,
					"type": loc_obj.get("type", "generic"),
					"minX": loc_obj.get("minX", 0.0),
					"maxX": loc_obj.get("maxX", 100.0),
					"minY": loc_obj.get("minY", 0.0),
					"maxY": loc_obj.get("maxY", 100.0),
					"centerX": (loc_obj.get("minX", 0.0) + loc_obj.get("maxX", 100.0)) / 2.0,
					"centerY": (loc_obj.get("minY", 0.0) + loc_obj.get("maxY", 100.0)) / 2.0
				}
		_redraw_location_overlays()
	elif state.has("locations"):
		var locs = state.locations
		for loc_obj in locs:
			if loc_obj is Dictionary:
				var loc_name = loc_obj.get("name", "unknown")
				locations[loc_name] = {
					"name": loc_name,
					"type": loc_obj.get("type", "generic"),
					"minX": loc_obj.get("minX", 0.0),
					"maxX": loc_obj.get("maxX", 100.0),
					"minY": loc_obj.get("minY", 0.0),
					"maxY": loc_obj.get("maxY", 100.0),
					"centerX": (loc_obj.get("minX", 0.0) + loc_obj.get("maxX", 100.0)) / 2.0,
					"centerY": (loc_obj.get("minY", 0.0) + loc_obj.get("maxY", 100.0)) / 2.0
				}
		_redraw_location_overlays()
	
	# Update debug info
	if state.has("agents"):
		var total_entities = state.agents.size()
		var player_count = 0
		for agent_data in state.agents:
			if agent_data.get("name", "") == player_name:
				player_count += 1
		var npc_count = max(0, total_entities - player_count)
		var location_count = state.get("location_states", state.get("locations", [])).size()
		var simulation_time = state.get("time", "--:--")
		debug_label.text = "NPCs: %d | Player: %d | Locations: %d | Time: %s" % [
			npc_count, player_count, location_count, str(simulation_time)
		]
	
	# Update or create agent nodes
	if state.has("agents"):
		# keep simple counters per location so we can space agents out
		var locCounters = {}
		for agent_data in state.agents:
			var agent_name = agent_data.get("name", "unknown")
			var is_player_agent = agent_name == player_name
			
			# Do not create a second generic node for the controllable player.
			if is_player_agent and agent_nodes.has(agent_name):
				var existing_player_proxy = agent_nodes[agent_name]
				if is_instance_valid(existing_player_proxy):
					existing_player_proxy.queue_free()
				agent_nodes.erase(agent_name)

			# Create agent node if doesn't exist
			if not is_player_agent and not agent_nodes.has(agent_name):
				var agent_node = agent_scene.instantiate()
				agent_node.name = agent_name
				agents_container.add_child(agent_node)
				agent_nodes[agent_name] = agent_node
				print("Spawned agent node: ", agent_name)
			
			# figure out position offset grid for this location
			var loc_name = agent_data.get("location", "unknown")
			var base_center = Vector2(0, 0)
			var spacing = 150.0  # Increased from 80 to match larger location bounds
			if agent_data.has("x") and agent_data.has("y"):
				base_center = Vector2(float(agent_data.get("x", 0.0)), float(agent_data.get("y", 0.0)))
			elif locations.has(loc_name):
				var locdata = locations[loc_name]
				base_center = Vector2(locdata.centerX, locdata.centerY)
				# compute grid columns based on width
				var width = locdata.get("maxX",0.0) - locdata.get("minX",0.0)
				var cols = max(1, int(width / spacing))
				var idx = locCounters.get(loc_name, 0)
				locCounters[loc_name] = idx + 1
				var row = int(idx / cols)
				var col = idx % cols
				var xoff = (col - (cols-1)/2.0) * spacing
				var yoff = (row - 0.5) * spacing
				base_center += Vector2(xoff, yoff)
				print("[GRID] ", agent_name, "in", loc_name, "=> cols", cols, "idx", idx,
					"pos", base_center)
			
				# store agent position data — for the player use the actual node position
			if is_player_agent and player_node != null:
				agent_positions[agent_name] = {
					"location": normalize_location_name(player_node.current_location),
					"x": player_node.position.x,
					"y": player_node.position.y
				}
			else:
				agent_positions[agent_name] = {
					"location": loc_name,
					"x": base_center.x,
					"y": base_center.y
				}
			
			# Update non-player agent visuals with the computed/authoritative position.
			if not is_player_agent:
				var agent = agent_nodes[agent_name]
				agent.update_from_backend(agent_data, locations, base_center)
	
	# Also update player if it exists and is in the agents list
	if player_node != null and state.has("agents"):
		for agent_data in state.agents:
			if agent_data.get("name", "") == player_name:
				player_node.update_from_backend(agent_data, locations)
				break

func _progress_simulation():
	"""POST to /state to advance the simulation one step"""
	var http = HTTPRequest.new()
	add_child(http)
	
	var error = http.request(
		backend_url + "/state",
		[],
		HTTPClient.METHOD_POST
	)
	
	if error == OK:
		print("Progressing simulation...")
	else:
		push_error("Failed to progress simulation")
	
	# Clean up after delay
	await get_tree().create_timer(0.1).timeout
	http.queue_free()
	print("Server state updated.")

func enqueue_player_action(player_id: String, action_type: String, target_agent: String = "", 
						   target_location: String = "", player_x: float = 0.0, player_y: float = 0.0,
						   action_description: String = "", speak_text: String = "", intensity: float = 0.5,
						   item: String = "", flair: String = "") -> void:
	"""Enqueue a player action for turn-based execution"""
	if action_type == "move" and turn_request_in_flight:
		pending_move_action = {
			"playerId": player_id,
			"actionType": action_type,
			"targetAgent": target_agent,
			"targetLocation": target_location,
			"playerX": player_x,
			"playerY": player_y,
			"actionDescription": action_description,
			"speakText": speak_text,
			"intensity": intensity,
			"item": item,
			"flair": flair
		}
		return

	var http = HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(_on_action_enqueued.bind(http))
	turn_request_in_flight = true
	
	var headers = ["Content-Type: application/json"]
	var body_dict = {
		"playerId": player_id,
		"actionType": action_type,
		"playerX": player_x,
		"playerY": player_y,
		"intensity": intensity
	}
	
	# Add optional fields
	if target_agent != "":
		body_dict["targetAgent"] = target_agent
	if target_location != "":
		body_dict["targetLocation"] = target_location
	if action_description != "":
		body_dict["actionDescription"] = action_description
	if speak_text != "":
		body_dict["speakText"] = speak_text
	if item != "":
		body_dict["item"] = item
	if flair != "":
		body_dict["flair"] = flair
	
	var body = JSON.stringify(body_dict)
	last_runtime_request = {
		"playerX": player_x,
		"playerY": player_y,
		"awarenessRadius": runtime_awareness_radius,
		"forceDayStart": false
	}
	var error = http.request(
		backend_url + "/player/actions",
		headers,
		HTTPClient.METHOD_POST,
		body
	)
	
	if error == OK:
		print("Enqueued action: ", action_type, " by ", player_id)
	else:
		push_error("Failed to enqueue action")
		turn_request_in_flight = false
		if dialogue_request_in_flight:
			_set_dialogue_busy(false)
		http.queue_free()

func _on_action_enqueued(result, response_code, headers, body, http):
	"""Handle action enqueue response"""
	if response_code == 200:
		var json = JSON.new()
		json.parse(body.get_string_from_utf8())
		var response = json.data
		
		if response.get("success", false):
			print("Action enqueued successfully")
			# After enqueuing, automatically process the turn
			call_deferred("_process_turn")
		else:
			var error_msg = response.get("error", "Unknown error")
			push_error("Action enqueue failed: ", error_msg)
			turn_request_in_flight = false
			if dialogue_request_in_flight:
				_set_dialogue_busy(false)
	else:
		push_error("Action enqueue failed with code: ", response_code, " Body: ", body.get_string_from_utf8())
		turn_request_in_flight = false
		if dialogue_request_in_flight:
			_set_dialogue_busy(false)
	
	if is_instance_valid(http):
		http.queue_free()

func _process_turn():
	"""Process the next turn by calling /turn endpoint"""
	var http = HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(_on_turn_processed.bind(http))
	var request_body = {
		"awarenessRadius": runtime_awareness_radius,
		"forceDayStart": false
	}
	if last_runtime_request.has("playerX"):
		request_body["playerX"] = last_runtime_request["playerX"]
	if last_runtime_request.has("playerY"):
		request_body["playerY"] = last_runtime_request["playerY"]
	if is_dialogue_open() and active_dialogue_target != "":
		request_body["pinnedAgents"] = [active_dialogue_target]

	# Report current NPC positions so the server keeps LLM context accurate
	var npc_pos_list = []
	for agent_name in agent_nodes.keys():
		var agent_node = agent_nodes[agent_name]
		if is_instance_valid(agent_node):
			npc_pos_list.append({
				"name": agent_name,
				"x": agent_node.position.x,
				"y": agent_node.position.y
			})
	if not npc_pos_list.is_empty():
		request_body["npcPositions"] = npc_pos_list
	
	var error = http.request(
		backend_url + "/turn",
		["Content-Type: application/json"],
		HTTPClient.METHOD_POST,
		JSON.stringify(request_body)
	)
	
	if error == OK:
		print("Processing turn...")
	else:
		push_error("Failed to process turn")
		turn_request_in_flight = false
		if dialogue_request_in_flight:
			_set_dialogue_busy(false)
		_dispatch_queued_move_if_any()
		http.queue_free()

func _on_turn_processed(result, response_code, headers, body, http):
	"""Handle turn processing response"""
	if response_code == 200:
		var json = JSON.new()
		json.parse(body.get_string_from_utf8())
		var response = json.data
		# Always apply returned world state. A failed player action can still advance time/NPC runtime.
		_update_agents_from_turn_response(response)
		
		var action_result = response.get("actionResult", {})
		if action_result.get("success", false):
			print("Turn processed successfully")
			if action_result.has("agentReplyText") and str(action_result.get("agentReplyText", "")) != "":
				var speaker = str(action_result.get("agentReplySpeaker", active_dialogue_target))
				append_dialogue_line(speaker, str(action_result.get("agentReplyText", "")))
		else:
			var error_msg = action_result.get("result", action_result.get("error", "Unknown error"))
			push_error("Turn processing failed: ", error_msg)
			if dialogue_status != null:
				dialogue_status.text = "Dialogue failed: " + str(error_msg)
	else:
		push_error("Turn processing failed with code: ", response_code, " Body: ", body.get_string_from_utf8())
		if dialogue_status != null:
			dialogue_status.text = "Dialogue request failed"

	turn_request_in_flight = false
	if dialogue_request_in_flight:
		_set_dialogue_busy(false)
	_dispatch_queued_move_if_any()
	
	if is_instance_valid(http):
		http.queue_free()

func _dispatch_queued_move_if_any() -> void:
	"""Send the most recent queued move after current turn completes."""
	if pending_move_action.is_empty():
		return

	var payload = pending_move_action
	pending_move_action = {}
	enqueue_player_action(
		payload.get("playerId", "Player"),
		payload.get("actionType", "move"),
		payload.get("targetAgent", ""),
		payload.get("targetLocation", ""),
		float(payload.get("playerX", 0.0)),
		float(payload.get("playerY", 0.0)),
		payload.get("actionDescription", "Walking"),
		payload.get("speakText", ""),
		float(payload.get("intensity", 0.5)),
		payload.get("item", ""),
		payload.get("flair", "")
	)

func _update_agents_from_turn_response(response: Dictionary):
	"""Update all agents from turn response"""
	var agents_data = response.get("agents", [])
	var locations_data = response.get("location_states", [])
	if response.has("time") and debug_label != null:
		var simulation_time = str(response.get("time", "--:--"))
		var npc_count = max(0, agents_data.size() - 1)
		var location_count = locations_data.size()
		debug_label.text = "NPCs: %d | Player: %d | Locations: %d | Time: %s" % [
			npc_count, 1, location_count, simulation_time
		]
	
	# Update agents
	for agent_data in agents_data:
		_update_agent_from_response(agent_data)
		agent_positions[agent_data.get("name", "unknown")] = {
			"location": agent_data.get("location", "unknown"),
			"x": float(agent_data.get("x", 0.0)),
			"y": float(agent_data.get("y", 0.0))
		}
		if player_node != null and agent_data.get("name", "") == player_name:
			player_node.update_from_backend(agent_data, locations)

	# Step each NPC one tile toward its target (client-side pathfinding)
	for agent_name in agent_nodes.keys():
		var agent_node = agent_nodes[agent_name]
		if is_instance_valid(agent_node) and agent_node.has_method("step_client_side"):
			agent_node.step_client_side(self)
	
	# Update locations if needed
	for loc_data in locations_data:
		var loc_name = loc_data.get("name", "")
		if locations.has(loc_name):
			locations[loc_name] = loc_data
	
	print("Updated ", agents_data.size(), " agents from turn response")

func submit_player_movement(player_id: String, current_location: String, player_position: Vector2) -> void:
	"""Enqueue a movement action at the player's current coordinates."""
	if current_location == "":
		return
	mark_player_locally_moved()
	enqueue_player_action(
		player_id,
		"move",
		"",
		current_location,
		player_position.x,
		player_position.y,
		"Walking"
	)

func mark_player_locally_moved() -> void:
	player_has_local_movement = true

func should_sync_player_position_from_backend() -> bool:
	return not player_has_local_movement

func get_location_name_for_position(world_position: Vector2, fallback: String = "") -> String:
	"""Resolve location by smallest containing area; outside/street are fallback."""
	var outside_match = ""
	var best_match = ""
	var best_area = INF

	for loc_name in locations.keys():
		var loc = locations[loc_name]
		if not (loc is Dictionary):
			continue

		var min_x = loc.get("minX", 0.0)
		var max_x = loc.get("maxX", 0.0)
		var min_y = loc.get("minY", 0.0)
		var max_y = loc.get("maxY", 0.0)
		if world_position.x < min_x or world_position.x > max_x or world_position.y < min_y or world_position.y > max_y:
			continue

		var loc_type = str(loc.get("type", "")).to_lower()
		var lname = str(loc_name).to_lower()
		var is_outside = loc_type == "outside" or loc_type == "street" or lname == "outside" or lname == "street" or lname == "road"
		if is_outside:
			outside_match = loc_name
			continue

		var area = max(0.0, max_x - min_x) * max(0.0, max_y - min_y)
		if best_match == "" or area < best_area:
			best_match = loc_name
			best_area = area

	if best_match != "":
		return best_match
	if outside_match != "":
		return outside_match
	return fallback

func _update_agent_from_response(agent_data: Dictionary):
	"""Update a single agent from action response"""
	var agent_name = agent_data.get("name", "unknown")
	if agent_nodes.has(agent_name):
		var agent = agent_nodes[agent_name]
		agent.update_from_backend(agent_data, locations)

func _save_state():
	"""Save current backend state to file"""
	var http = HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(_on_save_state_received.bind(http))
	
	# Get current state from backend
	http.request(backend_url + "/state")

func _on_save_state_received(result, response_code, headers, body, http):
	"""Save the received state to disk"""
	if is_instance_valid(http):
		http.queue_free()
	
	if response_code == 200:
		var file = FileAccess.open(save_file_path, FileAccess.WRITE)
		if file:
			file.store_string(body.get_string_from_utf8())
			file.close()
			print("State saved to: ", save_file_path)
		else:
			push_error("Failed to open save file")
	else:
		push_error("Failed to fetch state for saving")

func _bootstrap_agent_schedules() -> void:
	"""Check each agent's schedule; call server bootstrap (blocking) for those
	without one. Shows the loading overlay with live status while waiting."""
	print("[BOOTSTRAP] Checking agent schedules...")
	_set_loading(true, "Checking NPC schedules...")

	# Fetch agent list
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request(backend_url + "/agents")
	if err != OK:
		http.queue_free()
		return
	var agent_resp = await http.request_completed
	http.queue_free()
	if agent_resp[1] != 200:
		return

	var json = JSON.new()
	json.parse(agent_resp[3].get_string_from_utf8())
	var all_agents = json.data.get("agents", [])

	var needs_schedule: Array = []
	for agent_data in all_agents:
		var aname = agent_data.get("name", "")
		if aname == "" or aname == player_name:
			continue
		var sh = HTTPRequest.new()
		add_child(sh)
		var serr = sh.request(backend_url + "/agents/" + aname + "/schedule")
		if serr != OK:
			sh.queue_free()
			needs_schedule.append(aname)
			continue
		var sr = await sh.request_completed
		sh.queue_free()
		if sr[1] != 200:
			needs_schedule.append(aname)
			continue
		json = JSON.new()
		json.parse(sr[3].get_string_from_utf8())
		var items = json.data.get("items", [])
		if items.is_empty():
			needs_schedule.append(aname)
		else:
			print("[BOOTSTRAP] ", aname, " already has ", items.size(), " plan(s).")

	if needs_schedule.is_empty():
		print("[BOOTSTRAP] All agents have schedules — skipping.")
		return

	print("[BOOTSTRAP] Generating schedules for: ", needs_schedule)
	var total = needs_schedule.size()
	_set_loading(true, "Generating schedules for %d NPC(s)...\nThis may take a moment." % total)

	var bhttp = HTTPRequest.new()
	add_child(bhttp)
	var berr = bhttp.request(
		backend_url + "/world/bootstrap",
		["Content-Type: application/json"],
		HTTPClient.METHOD_POST,
		"{}"
	)
	if berr != OK:
		bhttp.queue_free()
		return
	var bresp = await bhttp.request_completed
	bhttp.queue_free()
	if bresp[1] == 200:
		json = JSON.new()
		json.parse(bresp[3].get_string_from_utf8())
		var res = json.data.get("result", {})
		print("[BOOTSTRAP] Done. Bootstrapped: ", res.get("bootstrapped", []), " | Skipped: ", res.get("skipped", []))
	else:
		push_error("[BOOTSTRAP] Schedule bootstrap failed with code: " + str(bresp[1]))


func _load_and_initialize_state():
	"""Load saved state and recreate it in the backend"""
	var file = FileAccess.open(save_file_path, FileAccess.READ)
	if not file:
		push_error("Failed to open save file")
		return
	
	var json_string = file.get_as_text()
	file.close()
	
	var json = JSON.new()
	var parse_result = json.parse(json_string)
	
	if parse_result != OK:
		push_error("Failed to parse saved state")
		return
	
	var saved_state = json.data
	var saved_agents = saved_state.get("agents", [])
	print("[LOAD] Loaded saved state with %d agents" % saved_agents.size())

	# If the save has no usable cast, bootstrap a fresh world so interactions work.
	if saved_agents.size() < 2:
		print("[LOAD] Saved state is sparse; creating a fresh world instead.")
		await _initialize_new_world()
		return
	
	# CRITICAL: Recreate the world structure (locations) first
	# Use the same configuration as _initialize_new_world()
	var locations_config = {
		"market": {"type": "market", "bounds": {"minX": 0, "maxX": 600, "minY": 0, "maxY": 500}},
		"tavern": {"type": "tavern", "bounds": {"minX": 700, "maxX": 1200, "minY": 0, "maxY": 450}},
		"coffee_shop": {"type": "cafe", "bounds": {"minX": 1300, "maxX": 1800, "minY": 0, "maxY": 400}},
		"street": {"type": "outside", "bounds": {"minX": 0, "maxX": 1800, "minY": 0, "maxY": 1200}},
		"town_square": {"type": "public", "bounds": {"minX": 400, "maxX": 1100, "minY": 600, "maxY": 1200}},
		"home": {"type": "residential", "bounds": {"minX": 80, "maxX": 350, "minY": 620, "maxY": 980}}
	}
	
	print("[LOAD] Recreating locations...")
	for loc_name in locations_config.keys():
		var loc_data = locations_config[loc_name]
		_create_location(loc_name, loc_data.get("type", "generic"), loc_data.get("bounds", {}))
	
	# Wait for location creation to complete
	print("[LOAD] Waiting for locations to be created...")
	await get_tree().create_timer(2.0).timeout
	await _fetch_locations_async()

	if initial_object_seed_enabled:
		print("[LOAD] Seeding world objects...")
		_set_loading(true, "Populating world objects...")
		await _seed_world_objects()
	
	# NOW recreate agents (locations exist)
	if saved_state.has("agents"):
		print("[LOAD] Recreating agents...")
		for agent_data in saved_state.agents:
			# saved_state agents come from /state which uses a different schema
			var name = agent_data.get("name", "")
			var location = normalize_location_name(agent_data.get("location", "unknown"))
			var activity = agent_data.get("action", agent_data.get("activity", ""))
			var memories = agent_data.get("memories", ["restored from save"])
			# if this record represents the player, recreate via player endpoint
			if name == player_name:
				print("[LOAD] recreating player from save: ", name)
				var p = {"name": name, "location": location, "activity": activity, "memories": memories}
				await _create_player_async(p)
			else:
				var clean = {"name": name, "location": location, "activity": activity, "memories": memories}
				print("[LOAD] sending create_agent for", clean)
				await _create_agent_async(clean)
	
	print("[LOAD] Load and initialization complete!")

# Dev Testing actions
func _input(event):
	"""Handle keyboard shortcuts (dev tools — use Ctrl+key to avoid conflict with WASD)"""
	if event is InputEventKey and event.pressed and not event.echo:
		# Allow closing the talk UI with Esc.
		if event.keycode == KEY_ESCAPE and is_dialogue_open():
			close_dialogue_panel()
			get_viewport().set_input_as_handled()
			return

	if event is InputEventKey and event.pressed and event.ctrl_pressed:
		# Ctrl+S to save state
		if event.keycode == KEY_S:
			print("Saving state...")
			_save_state()
		
		# Ctrl+P to progress simulation manually
		if event.keycode == KEY_P:
			_progress_simulation()
		
		# Ctrl+N to create a new random agent
		if event.keycode == KEY_N:
			_create_random_agent()

		# Ctrl+O to manually reseed server objects from Godot
		if event.keycode == KEY_O:
			print("[DEV] Manual object reseed triggered")
			await _seed_world_objects()

func _create_random_agent():
	"""Create a random agent for testing"""
	var names = ["Alice", "Bob", "Charlie", "Diana", "Eve", "Frank"]
	var locs = locations.keys()
	
	if locs.size() == 0:
		print("No locations available yet")
		return
	
	var random_name = names[randi() % names.size()]
	var random_loc = locs[randi() % locs.size()]
	
	var new_agent = {
		"name": random_name,
		"location": random_loc,
		"memories": ["I just arrived in town."],
		"activity": "Looking around."
	}
	
	_create_agent(new_agent)
	print("Created random agent: ", random_name)

func _exit_tree():
	"""Clean up on exit"""
	# Optional: Auto-save on exit
	_save_state()
	
	# Clean up any remaining HTTP requests
	for child in get_children():
		if child is HTTPRequest:
			child.queue_free()

func _ensure_location_overlay_container():
	"""Create a world-space container for location color overlays."""
	if world_node == null:
		return

	if world_node.has_node("LocationOverlays"):
		location_overlays = world_node.get_node("LocationOverlays") as Node2D
		return

	location_overlays = Node2D.new()
	location_overlays.name = "LocationOverlays"
	location_overlays.z_index = -5
	world_node.add_child(location_overlays)

	if world_node.has_node("TileMapLayer"):
		var tile_layer = world_node.get_node("TileMapLayer")
		world_node.move_child(location_overlays, tile_layer.get_index() + 1)

func _ensure_object_overlay_container():
	"""Create a world-space container for object markers and zones."""
	if world_node == null:
		return

	if world_node.has_node("ObjectOverlays"):
		object_overlays = world_node.get_node("ObjectOverlays") as Node2D
		return

	object_overlays = Node2D.new()
	object_overlays.name = "ObjectOverlays"
	object_overlays.z_index = 6
	world_node.add_child(object_overlays)

func _redraw_location_overlays():
	"""Render translucent color blocks for each location bounds."""
	if location_overlays == null:
		_ensure_location_overlay_container()
	if location_overlays == null:
		return

	for child in location_overlays.get_children():
		child.queue_free()

	for loc_name in locations.keys():
		var loc = locations[loc_name]
		if not (loc is Dictionary):
			continue

		var min_x = float(loc.get("minX", 0.0))
		var max_x = float(loc.get("maxX", min_x))
		var min_y = float(loc.get("minY", 0.0))
		var max_y = float(loc.get("maxY", min_y))
		if max_x <= min_x or max_y <= min_y:
			continue

		var rect_poly = Polygon2D.new()
		rect_poly.polygon = PackedVector2Array([
			Vector2(min_x, min_y),
			Vector2(max_x, min_y),
			Vector2(max_x, max_y),
			Vector2(min_x, max_y)
		])
		rect_poly.color = _get_location_overlay_color(str(loc.get("type", "generic")))
		location_overlays.add_child(rect_poly)

		var border = Line2D.new()
		border.width = 2.0
		border.default_color = Color(1, 1, 1, 0.35)
		border.closed = true
		border.points = PackedVector2Array([
			Vector2(min_x, min_y),
			Vector2(max_x, min_y),
			Vector2(max_x, max_y),
			Vector2(min_x, max_y)
		])
		location_overlays.add_child(border)

func _redraw_object_overlays():
	"""Render object markers with distinct visuals per object type."""
	if object_overlays == null:
		_ensure_object_overlay_container()
	if object_overlays == null:
		return

	for child in object_overlays.get_children():
		child.queue_free()

	for obj in world_objects:
		if not (obj is Dictionary):
			continue

		var object_type = str(obj.get("type", "fixture"))
		var object_name = str(obj.get("name", obj.get("id", "object")))
		var x = float(obj.get("x", 0.0))
		var y = float(obj.get("y", 0.0))
		var properties = obj.get("properties", {})

		var color = _get_object_type_color(object_type)
		var radius = _get_object_marker_radius(object_type)

		if object_type == "idle_zone":
			var zone_radius = radius
			if properties is Dictionary and properties.has("zoneRadius"):
				zone_radius = float(properties.get("zoneRadius", radius))
			var zone = _make_circle_polygon(Vector2(x, y), zone_radius, color, 0.12)
			object_overlays.add_child(zone)

			var zone_border = Line2D.new()
			zone_border.width = 2.0
			zone_border.default_color = Color(color.r, color.g, color.b, 0.75)
			zone_border.closed = true
			zone_border.points = _make_circle_points(Vector2(x, y), zone_radius, 24)
			object_overlays.add_child(zone_border)

		var marker = _make_object_marker(object_type, Vector2(x, y), radius, color)
		object_overlays.add_child(marker)

		var label = Label.new()
		label.text = "%s (%s)" % [object_name, object_type]
		label.position = Vector2(x + radius + 4.0, y - radius - 2.0)
		label.add_theme_font_size_override("font_size", 10)
		label.modulate = Color(0.98, 0.98, 0.98, 0.95)
		object_overlays.add_child(label)

func _make_object_marker(object_type: String, center: Vector2, radius: float, color: Color) -> Node2D:
	var node = Node2D.new()
	if object_type == "entrance_anchor":
		var diamond = Polygon2D.new()
		diamond.polygon = PackedVector2Array([
			center + Vector2(0, -radius),
			center + Vector2(radius, 0),
			center + Vector2(0, radius),
			center + Vector2(-radius, 0)
		])
		diamond.color = Color(color.r, color.g, color.b, 0.92)
		node.add_child(diamond)
	elif object_type == "work_spot":
		var rect = ColorRect.new()
		rect.position = center - Vector2(radius, radius)
		rect.size = Vector2(radius * 2.0, radius * 2.0)
		rect.color = Color(color.r, color.g, color.b, 0.92)
		node.add_child(rect)
	elif object_type == "decor":
		var tri = Polygon2D.new()
		tri.polygon = PackedVector2Array([
			center + Vector2(0, -radius),
			center + Vector2(radius, radius),
			center + Vector2(-radius, radius)
		])
		tri.color = Color(color.r, color.g, color.b, 0.92)
		node.add_child(tri)
	else:
		var circle = _make_circle_polygon(center, radius, color, 0.92)
		node.add_child(circle)

	return node

func _make_circle_polygon(center: Vector2, radius: float, color: Color, alpha: float) -> Polygon2D:
	var poly = Polygon2D.new()
	poly.polygon = _make_circle_points(center, radius, 24)
	poly.color = Color(color.r, color.g, color.b, alpha)
	return poly

func _make_circle_points(center: Vector2, radius: float, segments: int) -> PackedVector2Array:
	var points = PackedVector2Array()
	var safe_segments = max(8, segments)
	for i in range(safe_segments):
		var theta = TAU * float(i) / float(safe_segments)
		points.append(center + Vector2(cos(theta), sin(theta)) * radius)
	return points

func _get_object_type_color(object_type: String) -> Color:
	match object_type:
		"entrance_anchor":
			return Color(1.00, 0.57, 0.12)
		"idle_zone":
			return Color(0.16, 0.72, 1.00)
		"work_spot":
			return Color(1.00, 0.86, 0.18)
		"decor":
			return Color(0.98, 0.37, 0.68)
		"fixture":
			return Color(0.72, 0.76, 0.82)
		_:
			return Color(0.85, 0.85, 0.85)

func _get_object_marker_radius(object_type: String) -> float:
	match object_type:
		"entrance_anchor":
			return 10.0
		"idle_zone":
			return 14.0
		"work_spot":
			return 8.0
		"decor":
			return 8.0
		"fixture":
			return 7.0
		_:
			return 7.0

func _get_location_overlay_color(location_type: String) -> Color:
	var lower = location_type.to_lower()
	if lower == "market":
		return Color(0.13, 0.77, 0.37, 0.20)
	if lower == "tavern":
		return Color(0.92, 0.34, 0.05, 0.20)
	if lower == "cafe":
		return Color(0.92, 0.74, 0.05, 0.20)
	if lower == "residential":
		return Color(0.23, 0.51, 0.95, 0.20)
	if lower == "public":
		return Color(0.66, 0.33, 0.97, 0.20)
	if lower == "street" or lower == "outside":
		return Color(0.20, 0.20, 0.24, 0.22)
	return Color(0.60, 0.60, 0.60, 0.18)

# UTILITY FUNCTIONS
func get_location_data(location_name: String) -> Dictionary:
	"""Get spatial data for a location"""
	return locations.get(normalize_location_name(location_name), {})

func get_location_display_name(location_name: String) -> String:
	"""Return user-friendly location text: <name> (<type>)."""
	var normalized = normalize_location_name(location_name)
	var loc = get_location_data(normalized)
	if loc is Dictionary:
		return "%s (%s)" % [normalized, loc.get("type", "generic")]
	return normalized

func get_world_bounds() -> Dictionary:
	"""Get global map bounds across all known locations."""
	if locations.size() == 0:
		return {"minX": 0.0, "maxX": 1800.0, "minY": 0.0, "maxY": 1200.0}

	var min_x = 1e12
	var max_x = -1e12
	var min_y = 1e12
	var max_y = -1e12
	for loc_name in locations.keys():
		var loc = locations[loc_name]
		if loc is Dictionary:
			min_x = min(min_x, loc.get("minX", min_x))
			max_x = max(max_x, loc.get("maxX", max_x))
			min_y = min(min_y, loc.get("minY", min_y))
			max_y = max(max_y, loc.get("maxY", max_y))

	return {"minX": min_x, "maxX": max_x, "minY": min_y, "maxY": max_y}

func get_tile_size() -> float:
	"""Expose canonical tile size used for movement and occupancy mapping."""
	return tile_size

func world_to_tile(world_position: Vector2) -> Vector2i:
	"""Convert world coordinates to integer tile coordinates."""
	return Vector2i(int(floor(world_position.x / tile_size)), int(floor(world_position.y / tile_size)))

func tile_to_world(tile_position: Vector2i) -> Vector2:
	"""Convert integer tile coordinates to world-space top-left coordinate."""
	return Vector2(float(tile_position.x) * tile_size, float(tile_position.y) * tile_size)

func snap_to_tile(world_position: Vector2) -> Vector2:
	"""Snap an arbitrary world coordinate to the nearest tile coordinate."""
	var tx = int(round(world_position.x / tile_size))
	var ty = int(round(world_position.y / tile_size))
	return tile_to_world(Vector2i(tx, ty))

func normalize_location_name(location_name: String) -> String:
	"""Convert legacy location:sublocation labels to top-level location names."""
	if location_name == "":
		return location_name
	var parts = location_name.split(":")
	return parts[0]

func get_agent_position(agent_name: String) -> Dictionary:
	"""Get stored position data for an agent"""
	return agent_positions.get(agent_name, {"location": "unknown", "x": 0.0, "y": 0.0})

func is_coordinate_occupied(location_name: String, world_position: Vector2, ignore_agent_name: String = "") -> bool:
	"""Check if another player/agent is already occupying this tile-sized coordinate."""
	var normalized = normalize_location_name(location_name)
	var tile_pos = world_to_tile(world_position)
	var tile_x = tile_pos.x
	var tile_y = tile_pos.y

	for agent_name in agent_positions.keys():
		if ignore_agent_name != "" and agent_name == ignore_agent_name:
			continue
		var pos = agent_positions[agent_name]
		if normalize_location_name(str(pos.get("location", ""))) != normalized:
			continue
		var other_tile = world_to_tile(Vector2(float(pos.get("x", 0.0)), float(pos.get("y", 0.0))))
		var other_tile_x = other_tile.x
		var other_tile_y = other_tile.y
		if other_tile_x == tile_x and other_tile_y == tile_y:
			return true

	return false

func can_interact(agent1: String, agent2: String) -> bool:
	"""Check if two agents can interact (same location, within range)"""
	var pos1 = get_agent_position(agent1)
	var pos2 = get_agent_position(agent2)
	
	# Check same location
	if normalize_location_name(pos1.get("location", "")) != normalize_location_name(pos2.get("location", "")):
		return false
	
	# Check distance (max 75 units)
	var dx = pos1.get("x", 0.0) - pos2.get("x", 0.0)
	var dy = pos1.get("y", 0.0) - pos2.get("y", 0.0)
	var distance = sqrt(dx * dx + dy * dy)
	
	return distance <= 75.0
