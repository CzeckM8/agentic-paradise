extends Node

# Configuration
var backend_url = "http://localhost:8080"
var use_delta_endpoint = true  # Use lightweight /state/delta instead of full /state
var runtime_awareness_radius = 20.0  # tile Manhattan distance
var auto_start_backend = true
var backend_wait_attempts = 180  # up to 90s for cold JVM + model startup
var backend_wait_interval = 0.5
var tile_size = 32.0
var dialogue_interaction_tiles = 3
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
@onready var context_action_panel = get_node("../UI/ContextActionPanel")
@onready var context_action_title = get_node("../UI/ContextActionPanel/ActionVBox/ActionTitle")
@onready var context_action_status = get_node("../UI/ContextActionPanel/ActionVBox/ActionStatus")
@onready var context_action_list = get_node("../UI/ContextActionPanel/ActionVBox/ActionList")
@onready var context_action_close_button = get_node("../UI/ContextActionPanel/ActionVBox/ActionCloseButton")
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
var floor_tiles_container: Node2D = null
var object_overlays: Node2D = null
var grid_overlay: Node2D = null
var world_objects: Array = []
var blocked_tiles: Dictionary = {}        # movement-blocking tiles
var los_blocking_tiles: Dictionary = {}  # LOS-blocking tiles (solid + not transparent)
var active_dialogue_target = ""
var dialogue_request_in_flight = false
var context_actions_request_in_flight = false
var context_actions_cache: Array = []
var context_last_click_world: Vector2 = Vector2.ZERO
var context_has_click_focus = false
var pending_context_followup_action: Dictionary = {}
var object_interaction_in_last_turn = false
var carry_action_in_last_turn = false
var player_has_local_movement = false
var force_player_position_sync_once = false
var last_conversation_signature = ""
## Prevents re-appending the same server dialog line every /turn (full conversation history is replayed).
var _dialogue_signatures_seen: Dictionary = {}
var entity_anchors: Dictionary = {}  # name -> Vector2 live node position
var debug_header_text = "Connecting to backend..."
var mouse_tile_debug_text = " | Mouse tile: (--, --)"
var carried_object_id = ""
var write_panel: Panel = null
var write_panel_label: Label = null
var write_panel_input: LineEdit = null
var _pending_write_action: Dictionary = {}
var save_load_button: Button = null
var home_button: Button = null
var exit_to_menu_dialog: ConfirmationDialog = null
var save_load_panel: Panel = null
var save_load_title: Label = null
var save_load_slots: VBoxContainer = null
var save_load_status: Label = null
var save_load_save_button: Button = null
var save_load_load_button: Button = null
var save_slot_buttons: Dictionary = {}
var save_slot_data: Dictionary = {}
var selected_save_slot = "slot-1"
var save_load_panel_height = 420.0
var save_load_panel_min_width = 560.0
var save_load_panel_max_width = 980.0

# ── Target-property rules ────────────────────────────────────────────────────
# Actions available based on what the TARGET object/entity offers.
var property_action_rules: Dictionary = {
	"interactive": [
		{"actionKey": "inspect", "label": "Inspect", "actionType": "interact", "description": "Inspecting"}
	],
	"can_talk": [
		{"actionKey": "talk", "label": "Talk", "actionType": "speak", "description": "Talking with",
		 "maxDistance": 3},
	],
	"can_observe": [
		{"actionKey": "observe", "label": "Observe", "actionType": "interact", "description": "Observing"}
	],
	"can_attack": [
		{"actionKey": "attack", "label": "Attack", "actionType": "attack", "description": "Attacking",
		 "requiresAny": ["weapon", "blade", "knife"]}
	],
	# writable surfaces show Write if the actor carries any item tagged "writing_utensil".
	# tool_action_rules provides Write for all other interactive objects via the same tag check.
	"writable": [
		{"actionKey": "write", "label": "Write", "actionType": "interact", "description": "Writing on",
		 "requiresAny": ["writing_utensil"]}
	],
	# has_writing stores the text string; truthy check catches non-empty strings
	"has_writing": [
		{"actionKey": "read", "label": "Read", "actionType": "interact", "description": "Reading"}
	],
	"flat_surface": [
		{"actionKey": "place_object", "label": "Place Object", "actionType": "interact", "description": "Placing object on", "requiresInventory": true}
	],
	"carriable": [
		{"actionKey": "carry", "label": "Carry", "actionType": "interact", "description": "Carrying"}
	],
	# held_by_player is set client-side when deriving properties for inventory items on the ground
	"held_by_player": [
		{"actionKey": "drop", "label": "Drop", "actionType": "interact", "description": "Dropping"}
	],
	"transition_point": [
		{"actionKey": "unlock", "label": "Unlock", "actionType": "interact", "description": "Unlocking",
		 "requiresAny": ["key", "lockpick"]},
		{"actionKey": "lock",   "label": "Lock",   "actionType": "interact", "description": "Locking",
		 "requiresAny": ["key", "lockpick"]}
	],
	"can_open_close": [
		{"actionKey": "open",  "label": "Open",  "actionType": "interact", "description": "Opening"},
		{"actionKey": "close", "label": "Close", "actionType": "interact", "description": "Closing"}
	],
	"climbable": [
		{"actionKey": "climb", "label": "Climb onto", "actionType": "interact", "description": "Climbing onto"}
	],
	"sittable": [
		{"actionKey": "sit", "label": "Sit", "actionType": "interact", "description": "Sitting at"}
	]
}

# ── Subject-property rules ────────────────────────────────────────────────────
# Actions available because the ACTOR has these physical properties.
# "targetKinds" restricts which target categories get this action.
var subject_property_rules: Dictionary = {
	"has_arms": [
		{"actionKey": "punch", "label": "Punch", "actionType": "attack",   "description": "Punching",  "targetKinds": ["entity"]},
		{"actionKey": "shove", "label": "Shove", "actionType": "attack",   "description": "Shoving",   "targetKinds": ["entity"]},
		{"actionKey": "grab",  "label": "Grab",  "actionType": "interact", "description": "Grabbing",  "targetKinds": ["entity"]},
	],
	"has_legs": [
		{"actionKey": "kick",  "label": "Kick",  "actionType": "attack",   "description": "Kicking",   "targetKinds": ["entity"]},
	],
}

# ── Tool-action rules ─────────────────────────────────────────────────────────
# Each key IS the tag that must appear in a carried item's properties.tags array.
# Object definitions on the server are authoritative — a pencil defined with
# tags:["writing_utensil"] satisfies the "writing_utensil" key. No synonym guessing.
# "targetKinds" restricts which target categories get this action.
# "targetRequires" requires a specific property on the target (omit for any interactive target).
var tool_action_rules: Dictionary = {
	"writing_utensil": [
		{"actionKey": "write", "label": "Write", "actionType": "interact", "description": "Writing on",
		 "targetKinds": ["object"]},
	],
	"blade": [
		{"actionKey": "cut",   "label": "Cut",   "actionType": "interact", "description": "Cutting",
		 "targetKinds": ["object"], "targetRequires": "cuttable"},
		{"actionKey": "carve", "label": "Carve", "actionType": "interact", "description": "Carving into",
		 "targetKinds": ["object"], "targetRequires": "carveable"},
	],
	"knife": [
		{"actionKey": "cut",   "label": "Cut",   "actionType": "interact", "description": "Cutting",
		 "targetKinds": ["object"], "targetRequires": "cuttable"},
	],
	"coins": [
		{"actionKey": "trade", "label": "Trade", "actionType": "interact", "description": "Trading with",
		 "targetKinds": ["entity"]},
	],
	"herbs": [
		{"actionKey": "heal", "label": "Heal", "actionType": "interact", "description": "Healing",
		 "targetKinds": ["entity"]},
		{"actionKey": "apply_herbs", "label": "Apply Herbs", "actionType": "interact", "description": "Applying herbs to",
		 "targetKinds": ["entity"], "maxDistance": 1},
	],
	"medicine": [
		{"actionKey": "heal", "label": "Heal", "actionType": "interact", "description": "Healing",
		 "targetKinds": ["entity"]},
	],
	"rope": [
		{"actionKey": "bind", "label": "Bind", "actionType": "attack", "description": "Binding",
		 "targetKinds": ["entity"]},
		{"actionKey": "climb_rope", "label": "Climb with Rope", "actionType": "interact", "description": "Climbing with rope",
		 "targetKinds": ["object"], "targetRequires": "climbable"},
	],
	"torch": [
		{"actionKey": "light", "label": "Light Area", "actionType": "interact", "description": "Illuminating",
		 "targetKinds": ["object"]},
	],
	"lockpick": [
		{"actionKey": "unlock", "label": "Unlock", "actionType": "interact", "description": "Picking lock on",
		 "targetKinds": ["object"], "targetRequires": "transition_point"},
	],
	"book": [
		{"actionKey": "study", "label": "Study", "actionType": "interact", "description": "Studying",
		 "targetKinds": ["object"]},
	],
}

# ── Player inherent properties ────────────────────────────────────────────────
# The player is assumed to always have these physical capabilities.
# NPCs share these too; they are humanoid defaults.
var player_inherent_properties: Dictionary = {
	"has_arms":    true,
	"has_legs":    true,
	"has_voice":   true,
	"is_humanoid": true,
}

# Save/load file path
var save_file_path = "user://game_state.json"

func _set_loading(visible: bool, message: String = ""):
	if loading_overlay != null:
		loading_overlay.visible = visible
		if message != "" and loading_status_label != null:
			loading_status_label.text = message

func _apply_game_session_from_menu() -> void:
	"""Apply name (and related session data) chosen in the main-menu flow."""
	var session = get_node_or_null("/root/GameSession")
	if session == null:
		return
	var n = str(session.player_name).strip_edges()
	player_name = n if n != "" else "Player"
	session.player_name = player_name

func _ready():
	print("Backend Connector initialized")
	_apply_game_session_from_menu()
	_refresh_debug_label()
	_ensure_location_overlay_container()
	_ensure_object_overlay_container()
	_ensure_grid_overlay_container()
	_wire_dialogue_ui()
	_wire_context_action_ui()
	_wire_save_load_ui()
	_set_loading(true, "Connecting to server...")

	var backend_ready = await _wait_for_backend_ready()
	if not backend_ready:
		_set_loading(true, "ERROR: Server unreachable. Run start_server.bat or start_server.sh")
		push_error("Backend not reachable at " + backend_url + ". Check start_server.bat/start_server.sh or run server manually.")
		return

	var session = get_node_or_null("/root/GameSession")
	var load_slot = ""
	if session != null:
		load_slot = str(session.pending_load_slot)
		session.pending_load_slot = ""

	if load_slot != "":
		print("Loading saved world: ", load_slot)
		_set_loading(true, "Loading saved world...")
		var loaded = await _post_load_slot(load_slot)
		if not loaded:
			_set_loading(true, "ERROR: Could not load saved world.")
			return
	else:
		print("Creating world...")
		_set_loading(true, "Creating world...")
		await _post_reset_world()
		await _initialize_new_world()

	# Fetch world state first so the map is visible in the background
	# while schedule generation runs.
	await _fetch_locations_async()
	await _fetch_state_snapshot_async()
	if agent_nodes.is_empty():
		await _fetch_agents_snapshot_async()
	await _fetch_objects_async()
	# Load player inventory so context menus show correct tool-based actions on first click
	await _sync_player_inventory_async()

	# Generate schedules — map is now rendered and visible underneath the overlay.
	await _bootstrap_agent_schedules()

	# Hide loading overlay — world objects (including walls) are now loaded
	_set_loading(false)

	_create_write_panel()
	call_deferred("_poll_backend")

func _wait_for_backend_ready() -> bool:
	"""Verify backend availability and optionally start it."""
	if await _ping_backend():
		print("Backend is already reachable")
		return true

	if auto_start_backend:
		_set_loading(true, "Starting server (first launch may take ~30s)...")
		print("Launching backend server...")
		_start_backend_server()

	for i in range(backend_wait_attempts):
		if await _ping_backend():
			print("Backend is reachable")
			return true

		var elapsed = int(i * backend_wait_interval)
		if auto_start_backend:
			_set_loading(true, "Waiting for server... (%ds)" % elapsed)

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
	var project_dir = ProjectSettings.globalize_path("res://")
	project_dir = project_dir.replace("\\", "/").trim_suffix("/")
	var repo_root = project_dir.get_base_dir()
	var bat_path = repo_root + "/start_server.bat"
	var sh_path = repo_root + "/start_server.sh"

	if OS.get_name() == "Windows":
		if not FileAccess.file_exists(bat_path):
			push_error("[SERVER] start_server.bat not found at: " + bat_path)
			_set_loading(true, "start_server.bat not found — start server manually")
			return
		print("[SERVER] Launching: " + bat_path)
		var windows_pid = OS.create_process("cmd.exe", ["/c", "start", "", bat_path])
		if windows_pid > 0:
			print("[SERVER] Launch command sent (pid=" + str(windows_pid) + ")")
		else:
			push_error("[SERVER] Failed to launch start_server.bat (pid=" + str(windows_pid) + ")")
		return

	if not FileAccess.file_exists(sh_path):
		push_error("[SERVER] start_server.sh not found at: " + sh_path)
		_set_loading(true, "start_server.sh not found — start server manually")
		return

	print("[SERVER] Launching: " + sh_path)
	var pid = OS.create_process("/usr/bin/env", ["bash", sh_path])
	if pid > 0:
		print("[SERVER] Launch command sent (pid=" + str(pid) + ")")
	else:
		push_error("[SERVER] Failed to launch start_server.sh (pid=" + str(pid) + ")")

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
		"name": player_name,
		"location": "home",
		"activity": "Looking around home",
		"memories": ["I've arrived in this strange town.", "I should explore and meet the locals."]
	})

	# Fetch agents so agent_nodes is populated for inventory seeding
	await _fetch_agents_snapshot_async()
	# Assign randomized starting items to player and agents
	print("[INIT] Seeding starting inventories...")
	await _seed_starting_inventories()

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

func _seed_starting_inventories() -> void:
	"""Assign a randomized starting loadout to the player and each agent."""
	var rng = RandomNumberGenerator.new()
	rng.randomize()

	# Pool of pocket-sized items agents can start with (name, tags)
	var agent_item_pool = [
		{"name": "Pencil",        "tags": ["writing_utensil", "pocket_size"], "desc": "A short wooden pencil."},
		{"name": "Coin Purse",    "tags": ["coins", "currency", "pocket_size"], "desc": "A small leather coin purse."},
		{"name": "Herb Bundle",   "tags": ["herbs", "medicine", "pocket_size"], "desc": "Dried herbs tied with twine."},
		{"name": "Pocket Knife",  "tags": ["knife", "blade", "pocket_size"], "desc": "A small folding knife."},
		{"name": "Tinderbox",     "tags": ["torch", "fire_starter", "pocket_size"], "desc": "Flint and steel in a tin box."},
		{"name": "Lockpick Set",  "tags": ["lockpick", "key", "pocket_size"], "desc": "A small set of picks."},
		{"name": "Notebook",      "tags": ["book", "scholarly", "pocket_size"], "desc": "A well-worn notebook."},
		{"name": "Chalk",         "tags": ["writing_utensil", "pocket_size"], "desc": "A piece of chalk."},
		{"name": "Rope",          "tags": ["rope", "pocket_size"], "desc": "A coil of thin rope."},
	]

	# Player always starts with a pencil and coin purse
	var player_items = [
		{"name": "Pencil",     "tags": ["writing_utensil", "pocket_size"], "desc": "A short wooden pencil."},
		{"name": "Coin Purse", "tags": ["coins", "currency", "pocket_size"], "desc": "A small leather coin purse."},
	]
	for i in range(player_items.size()):
		var item = player_items[i]
		await _upsert_object_instance_async({
			"id": "inv_player_%d" % i,
			"type": "pocket_item",
			"name": item["name"],
			"x": 224, "y": 720,
			"location": "home",
			"properties": {
				"carriable": true, "pocket_size": true, "passable": true, "height": "low",
				"tags": item["tags"], "description": item["desc"],
				"heldBy": player_name
			}
		})

	# Each agent gets 1-2 random items
	var agent_names = agent_nodes.keys()
	for idx in range(agent_names.size()):
		var agent = str(agent_names[idx])
		if agent == player_name:
			continue
		var pos = agent_positions.get(agent, {"x": 760.0, "y": 900.0, "location": "town_square"})
		var count = 1 + (rng.randi() % 2)  # 1 or 2 items
		var used: Dictionary = {}
		for j in range(count):
			var pick_idx = rng.randi() % agent_item_pool.size()
			# avoid duplicates
			var tries = 0
			while used.has(pick_idx) and tries < 10:
				pick_idx = rng.randi() % agent_item_pool.size()
				tries += 1
			used[pick_idx] = true
			var item = agent_item_pool[pick_idx]
			await _upsert_object_instance_async({
				"id": "inv_agent_%d_%d" % [idx, j],
				"type": "pocket_item",
				"name": item["name"],
				"x": float(pos.get("x", 760.0)),
				"y": float(pos.get("y", 900.0)),
				"location": str(pos.get("location", "town_square")),
				"properties": {
					"carriable": true, "pocket_size": true, "passable": true, "height": "low",
					"tags": item["tags"], "description": item["desc"],
					"heldBy": agent
				}
			})

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
	# Re-fetch objects immediately so wall tiles are in world_objects and the
	# blocked_tiles cache is populated before any movement is possible.
	await _fetch_objects_async()
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
	"""Compute building perimeter wall tiles and seed them as real wall objects.
	Tiles that contain an entrance_anchor are excluded so those squares remain passable."""

	if locations.is_empty():
		await _fetch_locations_async()
	if locations.is_empty():
		push_error("[WALLS] Locations not loaded yet, skipping wall seed.")
		return

	# Build a quick-lookup set of entrance-anchor tile keys ("<x>,<y>").
	var door_tiles: Dictionary = {}
	for obj in seeded_objects:
		if str(obj.get("type", "")) == "entrance_anchor":
			var door_world = snap_to_tile(Vector2(float(obj.get("x", 0.0)), float(obj.get("y", 0.0))))
			var door_tile = world_to_tile(door_world)
			door_tiles["%d,%d" % [door_tile.x, door_tile.y]] = true

	# Collect perimeter tiles for every enclosed (non-transit) location.
	var seeded_walls = 0
	for loc_name in locations.keys():
		var loc = locations[loc_name]
		if not (loc is Dictionary):
			continue
		if _is_transit_location_name(loc_name, loc):
			continue

		# Keep perimeter walls on tiles INSIDE the location bounds.
		var min_x = floor(float(loc.get("minX", 0.0)) / tile_size) * tile_size
		var max_x = floor((float(loc.get("maxX", 0.0)) - 0.001) / tile_size) * tile_size
		var min_y = floor(float(loc.get("minY", 0.0)) / tile_size) * tile_size
		var max_y = floor((float(loc.get("maxY", 0.0)) - 0.001) / tile_size) * tile_size

		if max_x <= min_x or max_y <= min_y:
			continue

		var perim = _compute_perimeter_tiles(min_x, max_x, min_y, max_y)
		for tile in perim:
			var tile_idx = world_to_tile(tile)
			var key = "%d,%d" % [tile_idx.x, tile_idx.y]
			if not door_tiles.has(key):
				var wall_id = "wall_%s_%d_%d" % [loc_name, int(tile.x), int(tile.y)]
				var wall_obj = {
					"id": wall_id,
					"type": "wall",
					"name": "Wall",
					"x": tile.x,
					"y": tile.y,
					"location": loc_name,
					"properties": {
						"interactive": true,
						"walkable": false,
						"writable": true,
						"transparent": false,
						"tags": ["wall"]
					}
				}
				if await _upsert_object_instance_async(wall_obj):
					seeded_walls += 1

	if seeded_walls == 0:
		print("[WALLS] No wall tiles to seed.")
		return

	print("[WALLS] Seeded ", seeded_walls, " wall objects.")

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
	"""Client-side movement gate. O(1) blocked_tiles cache for everything;
	fallback scan for transition_point objects whose state may have changed."""
	var snapped = snap_to_tile(world_position)
	var key = "%d,%d" % [int(snapped.x), int(snapped.y)]
	if blocked_tiles.has(key):
		return true
	# Targeted scan only for transition_points (doors/gates) — state changes at runtime.
	var tile = world_to_tile(world_position)
	for obj in world_objects:
		if not (obj is Dictionary):
			continue
		var props = obj.get("properties", {})
		if not (props is Dictionary):
			continue
		var is_transition = bool(props.get("transition_point", false)) \
			or bool(props.get("can_open_close", false)) \
			or props.has("doorOpen") \
			or _contains_tag(props, "door") \
			or _contains_tag(props, "entrance")
		if not is_transition:
			continue
		if world_to_tile(Vector2(float(obj.get("x", 0.0)), float(obj.get("y", 0.0)))) != tile:
			continue
		if _is_object_blocking_movement(props):
			return true
	return false

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
		# fixtures are solid by default — tables, stages, fountains take up space
		"fixture": {
			"anchor": false,
			"portable": false,
			"interactive": true,
			"passable": false,
			"height": "medium",
			"interactionMode": "nearby",
			"interactionRadius": 48,
			"tags": ["environment"]
		},
		# entrance_anchors are transition points — passable when unlocked
		"entrance_anchor": {
			"anchor": true,
			"anchorKind": "entrance",
			"portable": false,
			"interactive": true,
			"passable": true,
			"transition_point": true,
			"locked": false,
			"height": "door",
			"interactionMode": "nearby",
			"interactionRadius": 40,
			"tags": ["entrance", "pathing"]
		},
		# work_spots (counters, bars) are solid — you work adjacent, not on top
		"work_spot": {
			"anchor": true,
			"anchorKind": "work",
			"portable": false,
			"interactive": true,
			"passable": false,
			"height": "counter",
			"flat_surface": true,
			"interactionMode": "adjacent",
			"interactionRadius": 32,
			"tags": ["task", "service"]
		},
		"decor": {
			"anchor": false,
			"portable": true,
			"interactive": true,
			"passable": true,
			"height": "low",
			"interactionMode": "adjacent",
			"interactionRadius": 32,
			"tags": ["decorative", "stealable", "writable"]
		},
		"wall": {
			"anchor": true,
			"portable": false,
			"interactive": false,
			"passable": false,
			"walkable": false,
			"interactionMode": "none",
			"interactionRadius": 0,
			"tags": ["wall"]
		},
		# pocket_items are carryable small objects — anything that fits in a pocket or bag
		"pocket_item": {
			"anchor": false,
			"portable": true,
			"pocket_size": true,
			"carriable": true,
			"interactive": true,
			"passable": true,
			"height": "low",
			"interactionMode": "adjacent",
			"interactionRadius": 32,
			"tags": ["pocket_size", "carriable"]
		}
	}

func _get_default_world_objects() -> Array:
	return [
		# Market
		{"id":"market_entry_street","type":"entrance_anchor","name":"Market Entrance","x":600,"y":260,"location":"market","properties":{"linkedHint":"street","building":"market","transition_point":true,"locked":false,"passable":true}},
		{"id":"market_counter","type":"work_spot","name":"Produce Counter","x":460,"y":200,"location":"market","properties":{"activity":["sell","buy","trade"],"adjacentPreferred":true,"passable":false,"height":"counter","flat_surface":true}},
		{"id":"market_crates","type":"fixture","name":"Crate Stack","x":250,"y":300,"location":"market","properties":{"inspectable":true,"passable":false,"height":"medium","description":"A stack of wooden crates, some dented at the corners."}},
		{"id":"market_notice_wall","type":"decor","name":"Market Notice Wall","x":120,"y":120,"location":"market","properties":{"writable":true,"graffiti":true,"passable":true,"height":"tall","has_writing":"Today's produce: fresh apples, potatoes, dried herbs. Haggling welcome."}},

		# Tavern
		{"id":"tavern_entry_street","type":"entrance_anchor","name":"Tavern Entrance","x":700,"y":230,"location":"tavern","properties":{"linkedHint":"street","building":"tavern","transition_point":true,"locked":false,"passable":true}},
		{"id":"tavern_bar","type":"work_spot","name":"Bar Counter","x":980,"y":140,"location":"tavern","properties":{"activity":["serve","chat","order"],"passable":false,"height":"counter","flat_surface":true,"description":"A long wooden bar, its surface worn smooth and faintly sticky."}},
		{"id":"tavern_table_a","type":"fixture","name":"Round Table A","x":830,"y":300,"location":"tavern","properties":{"sitAround":true,"passable":false,"height":"medium","flat_surface":true,"comfort":"worn but sturdy"}},
		{"id":"tavern_table_b","type":"fixture","name":"Round Table B","x":1030,"y":320,"location":"tavern","properties":{"sitAround":true,"passable":false,"height":"medium","flat_surface":true,"comfort":"a bit wobbly"}},
		{"id":"tavern_wall_sign","type":"decor","name":"Tavern Wall Sign","x":1140,"y":80,"location":"tavern","properties":{"stealable":true,"writable":true,"passable":true,"height":"tall","has_writing":"The Rusty Flagon — Est. Year 12. No credit. No exceptions."}},

		# Coffee shop
		{"id":"coffee_entry_street","type":"entrance_anchor","name":"Coffee Shop Entrance","x":1300,"y":210,"location":"coffee_shop","properties":{"linkedHint":"street","building":"coffee_shop","transition_point":true,"locked":false,"passable":true}},
		{"id":"coffee_machine","type":"work_spot","name":"Espresso Station","x":1680,"y":120,"location":"coffee_shop","properties":{"activity":["brew","calibrate","clean"],"passable":false,"height":"counter","flat_surface":true,"description":"A gleaming brass espresso machine, warm to the touch."}},
		{"id":"coffee_register","type":"work_spot","name":"Register","x":1580,"y":140,"location":"coffee_shop","properties":{"activity":["charge","serve"],"passable":false,"height":"counter","flat_surface":true}},
		{"id":"coffee_table","type":"fixture","name":"Window Table","x":1440,"y":290,"location":"coffee_shop","properties":{"sitAround":true,"passable":false,"height":"medium","flat_surface":true,"comfort":"comfortable","description":"A small round table by the window, with two chairs."}},
		{"id":"coffee_bulletin","type":"decor","name":"Community Bulletin Board","x":1335,"y":95,"location":"coffee_shop","properties":{"writable":true,"noteBoard":true,"passable":true,"height":"tall","has_writing":"Lost cat — answers to Biscuit. Reward offered. Also: open mic night Friday."}},

		# Town square
		{"id":"square_stage","type":"fixture","name":"Public Stage","x":620,"y":760,"location":"town_square","properties":{"performable":true,"passable":false,"height":"low","flat_surface":true,"climbable":true,"description":"A raised wooden platform, scuffed from many performances."}},
		{"id":"square_fountain","type":"fixture","name":"Fountain","x":780,"y":860,"location":"town_square","properties":{"landmark":true,"passable":false,"height":"medium","description":"A stone fountain, still running. Coins glint at the bottom."}},
		{"id":"square_notice","type":"decor","name":"Public Notice Wall","x":980,"y":720,"location":"town_square","properties":{"writable":true,"graffiti":true,"passable":true,"height":"tall","has_writing":"Town meeting postponed. Curfew reminder: gates close at dusk."}},

		# Home
		{"id":"home_entry_street","type":"entrance_anchor","name":"Home Entrance","x":224,"y":620,"location":"home","properties":{"linkedHint":"street","building":"home","transition_point":true,"locked":false,"passable":true}},
		{"id":"home_bed","type":"work_spot","name":"Bedside","x":170,"y":860,"location":"home","properties":{"activity":["rest","sleep"],"passable":false,"height":"low","flat_surface":true,"climbable":true,"sittable":true,"comfort":"soft","description":"A modest bed with rumpled sheets."}},
		{"id":"home_kitchen","type":"work_spot","name":"Kitchen Counter","x":250,"y":720,"location":"home","properties":{"activity":["cook","clean"],"passable":false,"height":"counter","flat_surface":true}},
		{"id":"home_picture","type":"decor","name":"Framed Picture","x":120,"y":680,"location":"home","properties":{"stealable":true,"passable":true,"height":"low","description":"A faded painting of a countryside scene."}},

		# Carriable world items — can be picked up and placed
		{"id":"item_pencil_market","type":"decor","name":"Pencil","x":470,"y":200,"location":"market","properties":{"carriable":true,"passable":true,"height":"low","tags":["writing_utensil","pen"],"description":"A short wooden pencil, worn to a nub."}},
		{"id":"item_pencil_coffee","type":"decor","name":"Pencil","x":1345,"y":95,"location":"coffee_shop","properties":{"carriable":true,"passable":true,"height":"low","tags":["writing_utensil","pen"],"description":"A pencil left near the bulletin board."}},
		{"id":"item_knife_tavern","type":"decor","name":"Pocket Knife","x":995,"y":145,"location":"tavern","properties":{"carriable":true,"passable":true,"height":"low","tags":["knife","blade","tool"],"description":"A folding knife with a worn wooden handle."}},
		{"id":"item_coin_square","type":"decor","name":"Coin Purse","x":790,"y":870,"location":"town_square","properties":{"carriable":true,"passable":true,"height":"low","tags":["coins","currency","valuables"],"description":"A small leather coin purse, a few coins jingling inside."}},
		{"id":"item_key_home","type":"decor","name":"House Key","x":240,"y":725,"location":"home","properties":{"carriable":true,"passable":true,"height":"low","tags":["key","unlock"],"opens":"home_entry_street","description":"A brass door key on a simple ring."}},

		# ── Market extras ────────────────────────────────────────────────────────
		{"id":"market_stall_b","type":"work_spot","name":"Dry Goods Stall","x":300,"y":200,"location":"market","properties":{"activity":["sell","buy","barter"],"passable":false,"height":"counter","flat_surface":true,"description":"A wooden stall piled with dried beans, grain sacks, and spices."}},
		{"id":"market_table_a","type":"fixture","name":"Display Table","x":350,"y":300,"location":"market","properties":{"passable":false,"height":"medium","flat_surface":true,"description":"A rough-hewn table used to display today's produce."}},
		{"id":"market_chair_a","type":"decor","name":"Stool","x":420,"y":350,"location":"market","properties":{"sittable":true,"passable":true,"height":"low","description":"A rickety three-legged stool behind the counter."}},
		{"id":"market_barrel_a","type":"fixture","name":"Pickle Barrel","x":100,"y":380,"location":"market","properties":{"passable":false,"height":"medium","inspectable":true,"description":"A large barrel. It smells strongly of brine."}},
		{"id":"market_barrel_b","type":"fixture","name":"Grain Barrel","x":160,"y":380,"location":"market","properties":{"passable":false,"height":"medium","inspectable":true,"description":"A barrel stuffed with rough-ground flour."}},
		{"id":"item_basket_market","type":"decor","name":"Wicker Basket","x":260,"y":200,"location":"market","properties":{"carriable":true,"passable":true,"height":"low","tags":["container"],"description":"A wicker basket filled with bruised apples."}},

		# ── Tavern extras ─────────────────────────────────────────────────────────
		{"id":"tavern_table_c","type":"fixture","name":"Corner Table","x":750,"y":380,"location":"tavern","properties":{"sitAround":true,"passable":false,"height":"medium","flat_surface":true,"description":"A small corner table, sticky from years of spilled ale."}},
		{"id":"tavern_chair_a","type":"decor","name":"Chair","x":800,"y":340,"location":"tavern","properties":{"sittable":true,"passable":true,"height":"low","description":"A plain wooden chair, one leg slightly shorter than the others."}},
		{"id":"tavern_chair_b","type":"decor","name":"Chair","x":860,"y":260,"location":"tavern","properties":{"sittable":true,"passable":true,"height":"low","description":"A plain wooden chair."}},
		{"id":"tavern_chair_c","type":"decor","name":"Chair","x":1060,"y":260,"location":"tavern","properties":{"sittable":true,"passable":true,"height":"low","description":"A plain wooden chair."}},
		{"id":"tavern_chair_d","type":"decor","name":"Chair","x":1100,"y":350,"location":"tavern","properties":{"sittable":true,"passable":true,"height":"low","description":"A plain wooden chair."}},
		{"id":"tavern_chair_e","type":"decor","name":"Chair","x":780,"y":420,"location":"tavern","properties":{"sittable":true,"passable":true,"height":"low","description":"A plain wooden chair beside the corner table."}},
		{"id":"tavern_stool_a","type":"decor","name":"Barstool","x":920,"y":180,"location":"tavern","properties":{"sittable":true,"passable":true,"height":"low","description":"A tall stool at the bar, the seat worn smooth."}},
		{"id":"tavern_stool_b","type":"decor","name":"Barstool","x":1040,"y":180,"location":"tavern","properties":{"sittable":true,"passable":true,"height":"low","description":"A tall stool at the bar."}},

		# ── Coffee shop extras ────────────────────────────────────────────────────
		{"id":"coffee_table_b","type":"fixture","name":"Corner Table","x":1550,"y":280,"location":"coffee_shop","properties":{"sitAround":true,"passable":false,"height":"medium","flat_surface":true,"description":"A small round table tucked into the corner."}},
		{"id":"coffee_chair_a","type":"decor","name":"Chair","x":1410,"y":330,"location":"coffee_shop","properties":{"sittable":true,"passable":true,"height":"low","description":"A cushioned chair by the window."}},
		{"id":"coffee_chair_b","type":"decor","name":"Chair","x":1480,"y":330,"location":"coffee_shop","properties":{"sittable":true,"passable":true,"height":"low","description":"A cushioned chair by the window."}},
		{"id":"coffee_chair_c","type":"decor","name":"Chair","x":1510,"y":320,"location":"coffee_shop","properties":{"sittable":true,"passable":true,"height":"low","description":"A chair at the corner table."}},
		{"id":"coffee_chair_d","type":"decor","name":"Chair","x":1600,"y":320,"location":"coffee_shop","properties":{"sittable":true,"passable":true,"height":"low","description":"A chair at the corner table."}},
		{"id":"coffee_shelf","type":"fixture","name":"Pastry Display","x":1630,"y":220,"location":"coffee_shop","properties":{"passable":false,"height":"counter","flat_surface":true,"description":"A glass-fronted case holding a few pastries and a wedge of hard cheese."}},

		# ── Town square extras ────────────────────────────────────────────────────
		{"id":"square_bench_a","type":"fixture","name":"Park Bench","x":550,"y":900,"location":"town_square","properties":{"sittable":true,"passable":false,"height":"low","description":"A weathered wooden bench. Good for watching the crowd."}},
		{"id":"square_bench_b","type":"fixture","name":"Park Bench","x":970,"y":860,"location":"town_square","properties":{"sittable":true,"passable":false,"height":"low","description":"A weathered wooden bench near the fountain."}},
		{"id":"square_table_a","type":"fixture","name":"Picnic Table","x":680,"y":1060,"location":"town_square","properties":{"sitAround":true,"passable":false,"height":"medium","flat_surface":true,"description":"A heavy stone picnic table, often used for card games."}},
		{"id":"square_chair_a","type":"decor","name":"Chair","x":640,"y":1100,"location":"town_square","properties":{"sittable":true,"passable":true,"height":"low","description":"A folding wooden chair."}},
		{"id":"square_chair_b","type":"decor","name":"Chair","x":720,"y":1100,"location":"town_square","properties":{"sittable":true,"passable":true,"height":"low","description":"A folding wooden chair."}},
		{"id":"item_book_square","type":"decor","name":"Worn Journal","x":500,"y":800,"location":"town_square","properties":{"carriable":true,"passable":true,"height":"low","writable":true,"has_writing":"Belonged to someone — half the pages are torn out.","tags":["writing_utensil","book"],"description":"A battered journal with a cracked leather cover."}},

		# ── Home extras ───────────────────────────────────────────────────────────
		{"id":"home_table","type":"fixture","name":"Dining Table","x":200,"y":780,"location":"home","properties":{"sitAround":true,"passable":false,"height":"medium","flat_surface":true,"description":"A simple square table with a chipped surface."}},
		{"id":"home_chair_a","type":"decor","name":"Chair","x":160,"y":810,"location":"home","properties":{"sittable":true,"passable":true,"height":"low","description":"A plain wooden chair at the table."}},
		{"id":"home_chair_b","type":"decor","name":"Chair","x":240,"y":810,"location":"home","properties":{"sittable":true,"passable":true,"height":"low","description":"A plain wooden chair at the table."}},
		{"id":"home_wardrobe","type":"fixture","name":"Wardrobe","x":310,"y":880,"location":"home","properties":{"passable":false,"height":"tall","inspectable":true,"description":"A tall wooden wardrobe. The door sticks a little."}}
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
	_redraw_floor_tiles()
	_redraw_grid_overlay()
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
	"""Fire-and-forget GET for world objects (use _fetch_objects_async when order matters)."""
	_fetch_objects_async()

func _fetch_objects_async() -> void:
	"""Awaitable fetch: loads world objects then rebuilds the blocked-tile cache."""
	var http = HTTPRequest.new()
	add_child(http)
	var error = http.request(backend_url + "/objects")
	if error != OK:
		push_error("Failed to fetch objects")
		http.queue_free()
		return
	var response = await http.request_completed
	http.queue_free()
	var response_code = response[1]
	var body = response[3]
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
	_rebuild_blocked_tiles_cache()
	_redraw_object_overlays()

func _on_objects_received(result, response_code, headers, body, http):
	"""Legacy callback kept for any external callers; delegates to async path."""
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
	_rebuild_blocked_tiles_cache()
	_redraw_object_overlays()

func _rebuild_blocked_tiles_cache() -> void:
	"""Build blocked_tiles (movement) and los_blocking_tiles (vision) from world_objects."""
	blocked_tiles.clear()
	los_blocking_tiles.clear()
	for obj in world_objects:
		if not (obj is Dictionary):
			continue
		if _is_object_held(obj):
			continue
		var props = obj.get("properties", {})
		if not (props is Dictionary):
			continue
		var snapped = snap_to_tile(Vector2(float(obj.get("x", 0.0)), float(obj.get("y", 0.0))))
		var key = "%d,%d" % [int(snapped.x), int(snapped.y)]
		if _is_object_blocking_movement(props):
			blocked_tiles[key] = true
		# LOS blocked by solid non-transparent objects (passable:false and no transparent flag)
		if _is_object_blocking_los(props):
			var tile_idx = world_to_tile(snapped)
			los_blocking_tiles["%d,%d" % [tile_idx.x, tile_idx.y]] = true

func _is_object_blocking_los(props: Dictionary) -> bool:
	"""True when this object occludes vision. Solid objects without transparent:true block LOS."""
	if bool(props.get("transparent", false)):
		return false
	# Anything that blocks movement also blocks vision
	return _is_object_blocking_movement(props)

## Supercover Bresenham LOS — checks every cell the ray passes through,
## including both corner cells on diagonal steps (no tunneling through corners).
## start tile is excluded; end tile is NOT blocked (you can see things ON a wall tile).
var los_vision_radius_tiles: int = 10

func has_line_of_sight(from_world: Vector2, to_world: Vector2) -> bool:
	var ft = world_to_tile(from_world)
	var tt = world_to_tile(to_world)
	if ft == tt:
		return true
	# Vision radius limit (Chebyshev distance)
	if abs(tt.x - ft.x) > los_vision_radius_tiles or abs(tt.y - ft.y) > los_vision_radius_tiles:
		return false

	var dx = abs(tt.x - ft.x)
	var dy = abs(tt.y - ft.y)
	var sx = 1 if tt.x > ft.x else -1
	var sy = 1 if tt.y > ft.y else -1
	var cx = ft.x
	var cy = ft.y
	var err = dx - dy

	while cx != tt.x or cy != tt.y:
		var e2 = 2 * err
		var step_x = e2 >= -dy
		var step_y = e2 <= dx
		if step_x and step_y:
			# Diagonal step — check both intermediate corner tiles for occlusion
			if los_blocking_tiles.has("%d,%d" % [cx + sx, cy]):
				return false
			if los_blocking_tiles.has("%d,%d" % [cx, cy + sy]):
				return false
		if step_x:
			err -= dy
			cx += sx
		if step_y:
			err += dx
			cy += sy
		# End tile: visible (you can see the wall/door itself)
		if cx == tt.x and cy == tt.y:
			return true
		# Intermediate tile blocked
		if los_blocking_tiles.has("%d,%d" % [cx, cy]):
			return false
	return true

func _is_object_blocking_movement(props: Dictionary) -> bool:
	"""Canonical blocking check — mirrors server isObjectBlockingMovement."""
	# New vocab: passable: false = solid impassable
	if props.has("passable"):
		if not bool(props.get("passable", true)):
			return true
		# passable: true on a non-transition_point means it's walkable floor
		if not bool(props.get("transition_point", false)):
			return false
	# transition_point (door/gate): blocked when locked
	if bool(props.get("transition_point", false)):
		return bool(props.get("locked", false))
	# Legacy: doorOpen / can_open_close / door tag
	var door_like = bool(props.get("can_open_close", false)) \
		or props.has("doorOpen") \
		or _contains_tag(props, "door") \
		or _contains_tag(props, "entrance")
	if door_like:
		return not bool(props.get("doorOpen", true))
	# Legacy: walkable: false
	return not bool(props.get("walkable", true))

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

func _activate_player_camera(player_nd: Node2D) -> void:
	"""Camera lives on the player scene; enable it once the node exists."""
	if player_nd == null:
		return
	var cam = player_nd.get_node_or_null("Camera")
	if cam is Camera2D:
		cam.enabled = true
		if cam.has_method("make_current"):
			cam.make_current()
		if cam.has_method("reset_smoothing"):
			cam.reset_smoothing()

func _spawn_player_node_for_loaded_state(loaded_player_name: String) -> void:
	if player_node != null:
		return
	var player_node_instance = player_scene.instantiate()
	player_node_instance.name = "Player"
	agents_container.add_child(player_node_instance)
	player_node = player_node_instance
	player_name = loaded_player_name if loaded_player_name.strip_edges() != "" else player_name
	player_node_instance.player_name = player_name
	_activate_player_camera(player_node_instance)

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
		player_node_instance.player_name = str(player_data.get("name", player_name))
		print("Spawned player node: ", player_node_instance.name)
		_activate_player_camera(player_node_instance)
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

func _refresh_debug_label() -> void:
	if debug_label == null:
		return
	debug_label.text = debug_header_text + mouse_tile_debug_text

func set_runtime_debug_header(npc_count: int, player_count: int, location_count: int, simulation_time: String) -> void:
	debug_header_text = "NPCs: %d | Player: %d | Locations: %d | Time: %s" % [
		npc_count,
		player_count,
		location_count,
		simulation_time
	]
	_refresh_debug_label()

func update_mouse_hover(viewport_position: Vector2) -> void:
	var world_position = viewport_to_world(viewport_position)
	var tile = world_to_tile(world_position)
	var tile_origin = tile_to_world(tile)
	var tile_center = tile_to_world_center(tile)
	mouse_tile_debug_text = " | Mouse tile: (%d, %d) origin=(%.0f, %.0f) center=(%.0f, %.0f)" % [
		tile.x,
		tile.y,
		tile_origin.x,
		tile_origin.y,
		tile_center.x,
		tile_center.y
	]
	_refresh_debug_label()

func viewport_to_world(viewport_position: Vector2) -> Vector2:
	"""Convert viewport/screen coordinates into world-space using active canvas transform."""
	if world_node != null and world_node is Node2D:
		return (world_node as Node2D).get_global_transform_with_canvas().affine_inverse() * viewport_position
	return get_viewport().get_canvas_transform().affine_inverse() * viewport_position

func get_entity_anchor(entity_name: String) -> Vector2:
	"""Return the most recently recorded node position for any named entity."""
	if entity_anchors.has(entity_name):
		return entity_anchors[entity_name]
	# Fallback: live player position
	if entity_name == player_name and player_node != null:
		return player_node.position
	return Vector2.ZERO

func _wire_dialogue_ui():
	"""Connect dialogue input events for player-to-agent conversations."""
	if dialogue_send_button != null:
		dialogue_send_button.pressed.connect(_on_dialogue_send_pressed)
	if dialogue_input != null:
		dialogue_input.text_submitted.connect(_on_dialogue_text_submitted)
	if dialogue_log != null:
		dialogue_log.bbcode_enabled = true
	if dialogue_panel != null:
		dialogue_panel.visible = true
		dialogue_panel.modulate = Color(1.0, 1.0, 1.0, 0.72)
	if dialogue_status != null:
		dialogue_status.text = "Click the input box to chat"

func _wire_context_action_ui():
	"""Connect generic context action menu controls."""
	if context_action_close_button != null:
		context_action_close_button.pressed.connect(close_context_action_panel)
	if context_action_panel != null:
		context_action_panel.visible = false

func _set_centered_panel_size(panel: Control, width: float, height: float):
	var half_width = width * 0.5
	var half_height = height * 0.5
	panel.offset_left = -half_width
	panel.offset_top = -half_height
	panel.offset_right = half_width
	panel.offset_bottom = half_height

func _wire_save_load_ui():
	var ui = get_node("../UI")
	save_load_button = Button.new()
	save_load_button.name = "SaveLoadButton"
	save_load_button.text = "Save / Load"
	save_load_button.anchor_left = 1.0
	save_load_button.anchor_right = 1.0
	save_load_button.offset_left = -170.0
	save_load_button.offset_top = 10.0
	save_load_button.offset_right = -10.0
	save_load_button.offset_bottom = 48.0
	save_load_button.pressed.connect(_open_save_load_panel)
	ui.add_child(save_load_button)

	home_button = Button.new()
	home_button.name = "HomeButton"
	home_button.text = "Exit to menu"
	home_button.anchor_left = 1.0
	home_button.anchor_right = 1.0
	home_button.offset_left = -170.0
	home_button.offset_top = 56.0
	home_button.offset_right = -10.0
	home_button.offset_bottom = 94.0
	home_button.pressed.connect(_exit_to_home_screen)
	ui.add_child(home_button)

	exit_to_menu_dialog = ConfirmationDialog.new()
	exit_to_menu_dialog.name = "ExitToMenuDialog"
	exit_to_menu_dialog.title = "Exit to menu"
	exit_to_menu_dialog.dialog_text = "Are you sure? Any unsaved progress will be lost!"
	exit_to_menu_dialog.confirmed.connect(_confirm_exit_to_home_screen)
	ui.add_child(exit_to_menu_dialog)

	save_load_panel = Panel.new()
	save_load_panel.name = "SaveLoadPanel"
	save_load_panel.visible = false
	save_load_panel.anchor_left = 0.5
	save_load_panel.anchor_top = 0.5
	save_load_panel.anchor_right = 0.5
	save_load_panel.anchor_bottom = 0.5
	_set_centered_panel_size(save_load_panel, save_load_panel_min_width, save_load_panel_height)
	ui.add_child(save_load_panel)

	var margin = MarginContainer.new()
	margin.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	margin.add_theme_constant_override("margin_left", 18)
	margin.add_theme_constant_override("margin_right", 18)
	margin.add_theme_constant_override("margin_top", 18)
	margin.add_theme_constant_override("margin_bottom", 18)
	save_load_panel.add_child(margin)

	var vbox = VBoxContainer.new()
	vbox.add_theme_constant_override("separation", 10)
	margin.add_child(vbox)

	save_load_title = Label.new()
	save_load_title.text = "Save / Load Game"
	save_load_title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	save_load_title.add_theme_font_size_override("font_size", 24)
	vbox.add_child(save_load_title)

	save_load_slots = VBoxContainer.new()
	save_load_slots.add_theme_constant_override("separation", 8)
	vbox.add_child(save_load_slots)

	for slot_id in ["slot-1", "slot-2", "slot-3"]:
		var slot_button = Button.new()
		slot_button.custom_minimum_size = Vector2(0, 62)
		slot_button.text = slot_id
		slot_button.pressed.connect(_select_save_slot.bind(slot_id))
		save_load_slots.add_child(slot_button)
		save_slot_buttons[slot_id] = slot_button

	var action_row = HBoxContainer.new()
	action_row.add_theme_constant_override("separation", 8)
	vbox.add_child(action_row)

	save_load_save_button = Button.new()
	save_load_save_button.text = "Save Selected"
	save_load_save_button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	save_load_save_button.pressed.connect(_save_selected_slot)
	action_row.add_child(save_load_save_button)

	save_load_load_button = Button.new()
	save_load_load_button.text = "Load Selected"
	save_load_load_button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	save_load_load_button.pressed.connect(_load_selected_slot)
	action_row.add_child(save_load_load_button)

	var close_button = Button.new()
	close_button.text = "Close"
	close_button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	close_button.pressed.connect(_close_save_load_panel)
	action_row.add_child(close_button)

	save_load_status = Label.new()
	save_load_status.text = "Select a slot."
	save_load_status.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	vbox.add_child(save_load_status)

func _open_save_load_panel():
	save_load_panel.visible = true
	save_load_status.text = "Loading save slots..."
	await _refresh_save_slots()

func _close_save_load_panel():
	save_load_panel.visible = false

func _exit_to_home_screen():
	if exit_to_menu_dialog != null:
		exit_to_menu_dialog.popup_centered()
		return

	_confirm_exit_to_home_screen()

func _confirm_exit_to_home_screen():
	turn_request_in_flight = false
	dialogue_request_in_flight = false
	context_actions_request_in_flight = false
	get_tree().change_scene_to_file("res://scenes/ui/mainMenu.tscn")

func _refresh_save_slots() -> bool:
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request(backend_url + "/saves")
	if err != OK:
		http.queue_free()
		save_load_status.text = "Could not request save slots."
		return false
	var response = await http.request_completed
	http.queue_free()
	if response[1] != 200:
		save_load_status.text = "Could not load save slots."
		return false
	var json = JSON.new()
	if json.parse(response[3].get_string_from_utf8()) != OK:
		save_load_status.text = "Could not parse save slots."
		return false
	save_slot_data.clear()
	var slots = json.data.get("saves", [])
	for slot in slots:
		if slot is Dictionary:
			save_slot_data[str(slot.get("slotId", ""))] = slot
	_render_save_slots()
	return true

func _render_save_slots():
	var longest_text = ""
	for slot_id in save_slot_buttons.keys():
		var button = save_slot_buttons[slot_id]
		var slot = save_slot_data.get(slot_id, {})
		var display_name = str(slot.get("displayName", slot_id))
		var is_empty = bool(slot.get("empty", true))
		var selected_prefix = "> " if slot_id == selected_save_slot else ""
		if is_empty:
			button.text = selected_prefix + display_name + "\nEmpty"
		else:
			var location = str(slot.get("playerLocation", "Unknown"))
			var time = str(slot.get("simulationTime", "--:--"))
			var saved_at = str(slot.get("savedAt", ""))
			button.text = selected_prefix + display_name + "\n" + location + " | " + time + " | " + saved_at
		if button.text.length() > longest_text.length():
			longest_text = button.text
	_resize_save_load_panel_for_slot_text(longest_text)
	save_load_load_button.disabled = bool(save_slot_data.get(selected_save_slot, {}).get("empty", true))
	save_load_status.text = "Selected " + selected_save_slot + "."

func _resize_save_load_panel_for_slot_text(longest_text: String):
	if save_load_panel == null:
		return
	var font = save_load_panel.get_theme_default_font()
	var font_size = 20
	var longest_line = ""
	for line in longest_text.split("\n"):
		if line.length() > longest_line.length():
			longest_line = line
	var text_width = font.get_string_size(longest_line, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size).x if font != null else longest_line.length() * 12.0
	var desired_width = clamp(text_width + 140.0, save_load_panel_min_width, save_load_panel_max_width)
	_set_centered_panel_size(save_load_panel, desired_width, save_load_panel_height)

func _select_save_slot(slot_id: String):
	selected_save_slot = slot_id
	_render_save_slots()

func _save_selected_slot():
	_set_loading(true, "Saving game...")
	var ok = await _post_save_slot(selected_save_slot)
	_set_loading(false)
	if ok:
		save_load_status.text = "Game saved."
		await _refresh_save_slots()
	else:
		save_load_status.text = "Save failed."

func _load_selected_slot():
	if bool(save_slot_data.get(selected_save_slot, {}).get("empty", true)):
		save_load_status.text = "That slot is empty."
		return
	_set_loading(true, "Loading game...")
	var ok = await _post_load_slot(selected_save_slot)
	if ok:
		await _refresh_world_after_load()
		save_load_panel.visible = false
	_set_loading(false)
	save_load_status.text = "Game loaded." if ok else "Load failed."

func _post_save_slot(slot_id: String) -> bool:
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request(backend_url + "/saves/" + slot_id.uri_encode(), ["Content-Type: application/json"], HTTPClient.METHOD_POST, "{}")
	if err != OK:
		http.queue_free()
		return false
	var response = await http.request_completed
	http.queue_free()
	return response[1] == 200

func _post_load_slot(slot_id: String) -> bool:
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request(backend_url + "/saves/" + slot_id.uri_encode() + "/load", ["Content-Type: application/json"], HTTPClient.METHOD_POST, "{}")
	if err != OK:
		http.queue_free()
		return false
	var response = await http.request_completed
	http.queue_free()
	return response[1] == 200

func _post_reset_world() -> bool:
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request(backend_url + "/world/reset", ["Content-Type: application/json"], HTTPClient.METHOD_POST, "{}")
	if err != OK:
		http.queue_free()
		return false
	var response = await http.request_completed
	http.queue_free()
	return response[1] == 200

func _refresh_world_after_load():
	_clear_client_world_for_load()
	player_has_local_movement = false
	force_player_position_sync_once = true
	await _fetch_locations_async()
	await _fetch_state_snapshot_async()
	await _fetch_agents_snapshot_async()
	await _fetch_objects_async()
	_dialogue_signatures_seen.clear()
	last_conversation_signature = ""
	last_runtime_request.clear()
	_refresh_debug_label()

func _clear_client_world_for_load():
	for agent_name in agent_nodes.keys():
		var node = agent_nodes[agent_name]
		if is_instance_valid(node):
			node.queue_free()
	agent_nodes.clear()
	agent_positions.clear()
	locations.clear()
	world_objects.clear()
	blocked_tiles.clear()
	los_blocking_tiles.clear()
	if object_overlays != null:
		for child in object_overlays.get_children():
			child.queue_free()

func _create_write_panel() -> void:
	"""Build the write-text modal panel programmatically."""
	var ui = get_node("../UI")
	write_panel = Panel.new()
	write_panel.name = "WritePanel"
	write_panel.visible = false
	write_panel.anchor_left = 0.5
	write_panel.anchor_top = 0.5
	write_panel.anchor_right = 0.5
	write_panel.anchor_bottom = 0.5
	write_panel.offset_left = -210.0
	write_panel.offset_top = -72.0
	write_panel.offset_right = 210.0
	write_panel.offset_bottom = 72.0
	ui.add_child(write_panel)

	var vbox = VBoxContainer.new()
	vbox.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	vbox.add_theme_constant_override("separation", 8)
	var mg = MarginContainer.new()
	mg.add_theme_constant_override("margin_left", 12)
	mg.add_theme_constant_override("margin_right", 12)
	mg.add_theme_constant_override("margin_top", 10)
	mg.add_theme_constant_override("margin_bottom", 10)
	mg.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	write_panel.add_child(mg)
	mg.add_child(vbox)

	write_panel_label = Label.new()
	write_panel_label.text = "Write on object:"
	write_panel_label.add_theme_font_size_override("font_size", 13)
	vbox.add_child(write_panel_label)

	write_panel_input = LineEdit.new()
	write_panel_input.placeholder_text = "What do you write?"
	write_panel_input.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	write_panel_input.text_submitted.connect(_on_write_confirm)
	vbox.add_child(write_panel_input)

	var hbox = HBoxContainer.new()
	hbox.add_theme_constant_override("separation", 8)
	vbox.add_child(hbox)

	var confirm_btn = Button.new()
	confirm_btn.text = "Write"
	confirm_btn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	confirm_btn.pressed.connect(func(): _on_write_confirm(write_panel_input.text))
	hbox.add_child(confirm_btn)

	var cancel_btn = Button.new()
	cancel_btn.text = "Cancel"
	cancel_btn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	cancel_btn.pressed.connect(_on_write_cancel)
	hbox.add_child(cancel_btn)

func _on_write_confirm(submitted_text: String = "") -> void:
	"""Send the write action with the player's text."""
	var text = submitted_text if submitted_text != "" else (write_panel_input.text if write_panel_input != null else "")
	write_panel.visible = false
	if text.strip_edges() == "" or _pending_write_action.is_empty():
		_pending_write_action = {}
		return
	var pa = _pending_write_action
	_pending_write_action = {}
	close_context_action_panel()
	object_interaction_in_last_turn = true
	enqueue_player_action(
		player_name,
		"interact",
		str(pa.get("targetAgent", "")),
		str(pa.get("location", "")),
		float(pa.get("playerX", 0.0)),
		float(pa.get("playerY", 0.0)),
		"Writing on " + str(pa.get("targetName", "object")),
		text.strip_edges(),
		0.1,
		"",
		"action:write"
	)

func _on_write_cancel() -> void:
	"""Dismiss the write panel without sending an action."""
	_pending_write_action = {}
	if write_panel != null:
		write_panel.visible = false

func is_context_action_open() -> bool:
	return context_action_panel != null and context_action_panel.visible

func is_write_panel_open() -> bool:
	return write_panel != null and write_panel.visible

func open_context_action_panel() -> void:
	if context_actions_request_in_flight:
		return
	if player_node == null:
		return

	context_has_click_focus = false
	if context_action_panel != null:
		context_action_panel.visible = true
	if context_action_title != null:
		context_action_title.text = "Nearby Actions"
	if context_action_status != null:
		context_action_status.text = "Scanning affordances..."
	if player_node != null:
		player_node.set_action_lock(true, "context_actions")

	context_actions_request_in_flight = true
	await _fetch_context_actions_async(player_name, player_node.position, null)

func open_inventory_panel() -> void:
	"""Open inventory UI using backend-authoritative /player/{name} data."""
	if player_node == null:
		return
	if context_action_panel != null:
		context_action_panel.visible = true
	if context_action_title != null:
		context_action_title.text = "Inventory"
	if context_action_status != null:
		context_action_status.text = "Loading inventory..."
	if player_node != null:
		player_node.set_action_lock(true, "inventory")
	await _fetch_inventory_async(player_name)

func _fetch_inventory_async(player_id: String) -> void:
	_clear_context_action_buttons()
	var http = HTTPRequest.new()
	add_child(http)
	var endpoint = "%s/player/%s" % [backend_url, player_id.uri_encode()]
	var err = http.request(endpoint)
	if err != OK:
		http.queue_free()
		if context_action_status != null:
			context_action_status.text = "Failed to fetch inventory"
		return

	var response = await http.request_completed
	http.queue_free()
	if response[1] != 200:
		if context_action_status != null:
			context_action_status.text = "Inventory unavailable"
		return

	var json = JSON.new()
	if json.parse(response[3].get_string_from_utf8()) != OK:
		if context_action_status != null:
			context_action_status.text = "Invalid inventory data"
		return

	var payload = json.data
	if not (payload is Dictionary):
		return

	# Use rich object data when available, fall back to plain IDs
	var inv_objects: Array = payload.get("inventoryObjects", [])
	if not (inv_objects is Array):
		inv_objects = []

	# Sync to player node for tool-requirement checks
	var inv_ids: Array = payload.get("inventory", [])
	if player_node != null and ("inventory" in player_node):
		player_node.inventory = inv_objects.duplicate() if not inv_objects.is_empty() else inv_ids.duplicate()

	if context_action_status != null:
		context_action_status.text = "Empty" if inv_objects.is_empty() else "%d item(s)" % inv_objects.size()

	if context_action_list == null:
		return

	if inv_objects.is_empty():
		var empty_label = Label.new()
		empty_label.text = "(nothing carried)"
		empty_label.add_theme_font_size_override("font_size", 12)
		empty_label.modulate = Color(0.7, 0.7, 0.7, 0.9)
		context_action_list.add_child(empty_label)
		return

	for item_obj in inv_objects:
		if not (item_obj is Dictionary):
			continue
		var item_id = str(item_obj.get("id", ""))
		var item_name = str(item_obj.get("name", item_id))
		var item_props = item_obj.get("properties", {})
		var item_desc = ""
		if item_props is Dictionary:
			item_desc = str(item_props.get("description", "")).strip_edges()

		var row = HBoxContainer.new()
		row.size_flags_horizontal = Control.SIZE_EXPAND_FILL

		var name_label = Label.new()
		name_label.text = item_name
		name_label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		name_label.add_theme_font_size_override("font_size", 12)
		name_label.modulate = Color(0.95, 0.95, 0.78, 1.0)
		if item_desc != "" and item_desc.to_lower() != "null":
			name_label.tooltip_text = item_desc
		row.add_child(name_label)

		var drop_btn = Button.new()
		drop_btn.text = "Drop"
		drop_btn.add_theme_font_size_override("font_size", 11)
		drop_btn.pressed.connect(_on_inventory_drop_pressed.bind(item_id, item_name))
		row.add_child(drop_btn)

		context_action_list.add_child(row)

func _on_inventory_drop_pressed(item_id: String, item_name: String) -> void:
	close_context_action_panel()
	if player_node == null:
		return
	# Mark so post-turn handler refreshes objects and inventory (reveals dropped item in world)
	object_interaction_in_last_turn = true
	enqueue_player_action(
		player_name, "drop", "object:" + item_id,
		player_node.current_location,
		player_node.position.x, player_node.position.y,
		"Dropping " + item_name, "", 0.1, "", "action:drop"
	)
	# Optimistic: clear heldBy immediately so the item isn't shown in inventory while waiting
	for obj in world_objects:
		if obj is Dictionary and str(obj.get("id", "")) == item_id:
			var props = obj.get("properties", {})
			if props is Dictionary:
				props.erase("heldBy")
			obj["x"] = player_node.position.x
			obj["y"] = player_node.position.y
			break
	_rebuild_blocked_tiles_cache()
	_redraw_object_overlays()

func open_context_action_panel_at(world_focus: Vector2) -> void:
	if context_actions_request_in_flight:
		return
	if player_node == null:
		return

	context_last_click_world = tile_to_world(world_to_tile(world_focus))
	context_has_click_focus = true
	context_actions_request_in_flight = true

	var dist = tile_manhattan_distance_from_world(player_node.position, context_last_click_world)
	# Observe/inspect is available within 2 tiles; all other actions require adjacency (≤1).
	# Beyond 2 tiles show a read-only summary only.
	var in_action_range = dist <= 2

	if not in_action_range:
		# Out of reach — show a read-only summary instead of an action menu.
		await _show_object_summary_async(context_last_click_world)
	else:
		var focus_tile = world_to_tile(context_last_click_world)
		if context_action_panel != null:
			context_action_panel.visible = true
		if context_action_title != null:
			context_action_title.text = "Actions @ tile (%d, %d)" % [focus_tile.x, focus_tile.y]
		if context_action_status != null:
			context_action_status.text = "Scanning affordances..."
		player_node.set_action_lock(true, "context_actions")
		await _fetch_context_actions_async(player_name, player_node.position, context_last_click_world)

func close_context_action_panel() -> void:
	if context_action_panel != null:
		context_action_panel.visible = false
	context_actions_cache.clear()
	context_has_click_focus = false
	if player_node != null and not dialogue_request_in_flight:
		player_node.set_action_lock(false)

# ── Object summary (shown when right-clicking out of interaction range) ──────

func _show_object_summary_async(focus_world: Vector2) -> void:
	"""Display a read-only description of whatever is at the clicked tile."""
	var targets = _collect_targets_for_tile(focus_world)
	_clear_context_action_buttons()

	# Filter out walls and invisible anchors — nothing interesting to describe.
	var describable: Array = []
	for t in targets:
		var props = t.get("properties", {})
		if props is Dictionary and not bool(props.get("walkable", true)) and not bool(props.get("interactive", true)):
			continue  # pure wall, skip
		var ttype = str(t.get("type", ""))
		if ttype == "wall":
			continue
		describable.append(t)

	if describable.is_empty():
		# Nothing here worth describing — silently cancel.
		context_actions_request_in_flight = false
		return

	if context_action_panel != null:
		context_action_panel.visible = true

	# Use the first object's name as the panel title; list all if multiple.
	var names = describable.map(func(t): return str(t.get("name", "Object")))
	if context_action_title != null:
		context_action_title.text = " / ".join(names) if names.size() <= 2 else names[0] + " …"

	# Build summary lines for each describable target.
	var lines: Array = []
	for target in describable:
		var summary = _build_target_summary(target)
		if summary != "":
			lines.append(summary)

	if context_action_status != null:
		context_action_status.text = "\n\n".join(lines) if not lines.is_empty() else "Nothing notable."

	context_actions_request_in_flight = false
	# Note: player action lock is NOT set for summary mode — reading is free.

func _build_target_summary(target: Dictionary) -> String:
	"""Return a brief natural-language description of a target (object or entity)."""
	var kind = str(target.get("kind", ""))

	if kind == "entity":
		var name = str(target.get("name", "Someone"))
		var state = agent_positions.get(name, {})
		var activity = str(state.get("action", "")).strip_edges()
		if activity.is_empty() or activity.to_lower().contains("agentic") or activity.to_lower() == "idle":
			activity = "going about their business"
		return name + " — " + activity + "."

	if kind == "object":
		var obj_type = str(target.get("type", "fixture"))
		var name = str(target.get("name", "Object"))
		var props = target.get("properties", {})
		if not (props is Dictionary):
			props = {}
		var base = _get_object_base_description(obj_type, name, props)
		var relational = _get_relational_context_for(str(target.get("id", "")), target)
		if relational != "":
			base = base.trim_suffix(".") + relational
		return base

	return ""

func _get_object_base_description(obj_type: String, name: String, props: Dictionary) -> String:
	"""Return a one-sentence description of an object based on its type and properties."""
	var lower = name.to_lower()
	match obj_type:
		"fixture":
			if bool(props.get("sitAround", false)):
				return "A " + lower + " with seats arranged around it."
			if bool(props.get("landmark", false)):
				return "The " + lower + ", a local landmark."
			if bool(props.get("performable", false)):
				return "The " + lower + " — a raised platform for performances."
			return "A " + lower + ", fixed in place."
		"decor":
			if bool(props.get("writable", false)) or bool(props.get("noteBoard", false)):
				return "The " + lower + " — writings and notices cover its surface."
			if bool(props.get("stealable", false)):
				return "The " + lower + " catches your eye; small enough to pocket."
			return "The " + lower + "."
		"work_spot":
			var activities: Array = props.get("activity", [])
			if activities is Array and not activities.is_empty():
				return "The " + lower + " — a workstation. Activities: " + ", ".join(activities) + "."
			return "The " + lower + " — a place of work."
		"entrance_anchor":
			return "A doorway."
		_:
			return "A " + lower + "."

func _get_relational_context_for(skip_id: String, target: Dictionary) -> String:
	"""Check if other objects share this tile and return a relational phrase."""
	var tx = float(target.get("x", 0.0))
	var ty = float(target.get("y", 0.0))
	var target_tile = world_to_tile(snap_to_tile(Vector2(tx, ty)))

	var conames: Array = []
	for obj in world_objects:
		if not (obj is Dictionary):
			continue
		var oid = str(obj.get("id", ""))
		if oid == skip_id or oid == "":
			continue
		var otype = str(obj.get("type", ""))
		if otype == "wall" or otype == "entrance_anchor":
			continue
		var opos = snap_to_tile(Vector2(float(obj.get("x", 0.0)), float(obj.get("y", 0.0))))
		if world_to_tile(opos) == target_tile:
			conames.append(str(obj.get("name", "something")).to_lower())

	if conames.is_empty():
		return ""
	if conames.size() == 1:
		return " A " + conames[0] + " rests nearby."
	return " Alongside it: " + ", ".join(conames) + "."

func _show_examine_panel_for_object(obj_id: String, obj_name: String, obj_row: Dictionary) -> void:
	"""Show a detailed examine panel for a world object (inspect/observe action)."""
	_clear_context_action_buttons()
	if context_action_panel != null:
		context_action_panel.visible = true
	if context_action_title != null:
		context_action_title.text = obj_name

	var text = _generate_object_examine_text(obj_id, obj_name, obj_row)
	if context_action_status != null:
		context_action_status.text = text

func _show_examine_panel_for_entity(entity_name: String) -> void:
	"""Show a detailed examine panel for an agent or player."""
	_clear_context_action_buttons()
	if context_action_panel != null:
		context_action_panel.visible = true
	if context_action_title != null:
		context_action_title.text = entity_name

	var text = _generate_entity_examine_text(entity_name)
	if context_action_status != null:
		context_action_status.text = text

func _generate_object_examine_text(obj_id: String, obj_name: String, obj_row: Dictionary) -> String:
	var props = obj_row.get("properties", {})
	if not (props is Dictionary):
		props = {}
	var server_state = obj_row.get("state", {})
	if server_state is Dictionary and server_state.has("isOpen"):
		props["doorOpen"] = bool(server_state.get("isOpen", true))

	var obj_type = str(obj_row.get("type", "fixture"))
	var lines: Array = []

	# Physical description
	var phys = _get_object_physical_description(obj_type, obj_name, props)
	if phys != "":
		lines.append(phys)

	# State description (open/closed/locked/occupied)
	var state_desc = _get_object_state_description(props, server_state if server_state is Dictionary else {})
	if state_desc != "":
		lines.append(state_desc)

	# Activities
	var activities: Array = props.get("activity", [])
	if activities is Array and not activities.is_empty():
		lines.append("Activities: " + ", ".join(activities) + ".")

	# Nearby objects on same tile
	var relational = _get_relational_context_for(obj_id, obj_row)
	if relational != "":
		lines.append(relational.strip_edges())

	return "\n".join(lines) if not lines.is_empty() else "Nothing notable."

func _generate_entity_examine_text(entity_name: String) -> String:
	var state = agent_positions.get(entity_name, {})
	var lines: Array = []

	var location = str(state.get("location", "")).strip_edges()
	if location != "" and location.to_lower() != "null":
		lines.append("Location: " + location + ".")

	var activity = str(state.get("action", "")).strip_edges()
	if activity != "" and not activity.to_lower().contains("agentic") and activity.to_lower() != "idle":
		lines.append(activity + ".")
	else:
		lines.append("Going about their business.")

	return "\n".join(lines) if not lines.is_empty() else entity_name + "."

func _get_object_physical_description(obj_type: String, name: String, props: Dictionary) -> String:
	var lower = name.to_lower()

	# If the object has an explicit free-text description, use it directly.
	var explicit_desc = str(props.get("description", "")).strip_edges()
	if explicit_desc != "" and explicit_desc.to_lower() != "null":
		return explicit_desc

	var door_like = bool(props.get("can_open_close", false)) \
		or _contains_tag(props, "door") \
		or _contains_tag(props, "entrance")

	# Build descriptor words from known property keys.
	var descriptors: Array = []

	var color = str(props.get("color", "")).strip_edges()
	if color != "" and color.to_lower() != "null":
		descriptors.append(color)

	var material = str(props.get("material", "")).strip_edges()
	if material != "" and material.to_lower() != "null":
		descriptors.append(material)

	var condition = str(props.get("condition", "")).strip_edges()
	if condition != "" and condition.to_lower() != "null":
		descriptors.append(condition)

	var size = str(props.get("size", "")).strip_edges()
	if size != "" and size.to_lower() != "null":
		descriptors.append(size)

	var qualifier = (" ".join(descriptors) + " ") if not descriptors.is_empty() else ""

	# Type-specific base sentence, enriched with qualifier.
	if door_like:
		return ("A " + qualifier + "door." if qualifier != "" else "A door.").strip_edges()

	match obj_type:
		"fixture":
			if bool(props.get("sitAround", false)):
				var comfort = str(props.get("comfort", "")).strip_edges()
				var comfort_note = (" It looks " + comfort + ".") if comfort != "" and comfort.to_lower() != "null" else ""
				return "A " + qualifier + lower + " with seats arranged around it." + comfort_note
			if bool(props.get("landmark", false)):
				return "The " + qualifier + lower + ", a local landmark."
			if bool(props.get("performable", false)):
				return "The " + qualifier + lower + " — a raised platform for performances."
			return ("A " + qualifier + lower + ".").strip_edges()
		"decor":
			if bool(props.get("writable", false)) or bool(props.get("noteBoard", false)):
				return "The " + qualifier + lower + " — writings and notices cover its surface."
			if bool(props.get("stealable", false)):
				return "The " + qualifier + lower + " — small enough to pocket."
			return ("The " + qualifier + lower + ".").strip_edges()
		"work_spot":
			return ("The " + qualifier + lower + " — a workstation.").strip_edges()
		"entrance_anchor":
			return "A doorway."
		_:
			return ("A " + qualifier + lower + ".").strip_edges()

func _get_object_state_description(props: Dictionary, server_state: Dictionary) -> String:
	var parts: Array = []

	var is_transition = bool(props.get("transition_point", false)) \
		or bool(props.get("can_open_close", false)) \
		or _contains_tag(props, "door") \
		or _contains_tag(props, "entrance")

	if is_transition:
		var locked = bool(props.get("locked", false))
		# Fall back to legacy doorOpen if locked not set
		if not props.has("locked"):
			locked = not bool(props.get("doorOpen", true))
		parts.append("It is " + ("locked" if locked else "unlocked") + ".")

	if bool(props.get("locked", false)) or bool(server_state.get("isLocked", false)):
		if not is_transition:  # avoid double-printing for doors
			parts.append("It appears to be locked.")

	var occupant = str(server_state.get("occupiedBy", "")).strip_edges()
	if occupant != "" and occupant.to_lower() != "null":
		parts.append(occupant + " is here.")

	return " ".join(parts)

func _clear_context_action_buttons() -> void:
	if context_action_list == null:
		return
	for child in context_action_list.get_children():
		child.queue_free()

func _fetch_context_actions_async(player_id: String, player_position: Vector2, focus_world = null) -> void:
	_clear_context_action_buttons()
	var targets: Array = []
	if focus_world != null:
		var snapped_focus = tile_to_world(world_to_tile(focus_world))
		var focus_tile = world_to_tile(snapped_focus)
		if context_action_title != null:
			context_action_title.text = "Actions @ tile (%d, %d)" % [focus_tile.x, focus_tile.y]
		targets = _collect_targets_for_tile(snapped_focus)
	else:
		targets = _collect_targets_near(player_position, 120.0)

	# Primary: fetch server-authoritative action descriptors and build menu from them.
	# Server computes affordances, range, key-lock binding, and carry-conflict in one pass.
	# Fallback: if the server is unreachable or returns empty, build client-side as before.
	var server_descriptors = await _fetch_player_legal_descriptors_async(player_id, player_position)
	var grouped_actions: Array
	if not server_descriptors.is_empty():
		grouped_actions = _build_groups_from_server_descriptors(server_descriptors, player_position)
	else:
		grouped_actions = _build_actions_grouped_by_target(targets)
		var server_legal = await _fetch_player_legal_actions_async(player_id, player_position)
		if not server_legal.is_empty():
			grouped_actions = _filter_grant_gated_actions(grouped_actions, server_legal)

	context_actions_cache = grouped_actions
	_render_grouped_context_actions(grouped_actions)

	# Always show a description of the focused target below the action buttons.
	if focus_world != null and context_action_status != null:
		var desc_lines: Array = []
		for t in targets:
			if not (t is Dictionary):
				continue
			var ttype = str(t.get("type", ""))
			if ttype == "wall":
				continue
			var summary = _build_target_summary(t)
			if summary != "":
				desc_lines.append(summary)
		context_action_status.text = "\n".join(desc_lines) if not desc_lines.is_empty() else ""
	elif context_action_status != null:
		context_action_status.text = ""

	context_actions_request_in_flight = false

func _fetch_player_legal_actions_async(player_id: String, player_position: Vector2) -> Dictionary:
	"""Query server for legal actions at the player's current position.
	Returns a Dictionary keyed by 'verb:targetId' → true for fast lookup.
	Returns empty Dictionary on failure (caller falls back to local computation)."""
	var http = HTTPRequest.new()
	add_child(http)
	var px = int(player_position.x)
	var py = int(player_position.y)
	var url = "%s/player/%s/legal_actions?x=%d&y=%d" % [backend_url, player_id.uri_encode(), px, py]
	var err = http.request(url)
	if err != OK:
		http.queue_free()
		return {}
	var response = await http.request_completed
	http.queue_free()
	if response[1] != 200:
		return {}
	var json = JSON.new()
	if json.parse(response[3].get_string_from_utf8()) != OK:
		return {}
	var data = json.data
	if not (data is Array):
		return {}
	var result: Dictionary = {}
	for entry in data:
		if entry is Dictionary:
			var verb = str(entry.get("verb", ""))
			var target_id = str(entry.get("targetId", ""))
			if verb != "":
				result[verb + ":" + target_id] = true
	return result

func _fetch_player_legal_descriptors_async(player_id: String, player_position: Vector2) -> Array:
	"""Fetch full action descriptors [{verb, targetId, targetName, targetKind, label}] from server.
	Returns empty Array on failure — caller falls back to client-side action building."""
	var http = HTTPRequest.new()
	add_child(http)
	var px = int(player_position.x)
	var py = int(player_position.y)
	var url = "%s/player/%s/legal_actions?x=%d&y=%d" % [backend_url, player_id.uri_encode(), px, py]
	var err = http.request(url)
	if err != OK:
		http.queue_free()
		return []
	var response = await http.request_completed
	http.queue_free()
	if response[1] != 200:
		return []
	var json = JSON.new()
	if json.parse(response[3].get_string_from_utf8()) != OK:
		return []
	var data = json.data
	if not (data is Array):
		return []
	return data

func _build_groups_from_server_descriptors(descriptors: Array, player_position: Vector2) -> Array:
	"""Convert server legalDescriptorsFor() response into the grouped_actions structure
	expected by _render_grouped_context_actions(). Groups actions by targetId."""
	var by_target: Dictionary = {}
	var order: Array = []  # preserve insertion order for stable menu ordering

	for desc in descriptors:
		if not (desc is Dictionary): continue
		var verb = str(desc.get("verb", ""))
		var target_id = str(desc.get("targetId", ""))
		var target_name = str(desc.get("targetName", target_id))
		var target_kind = str(desc.get("targetKind", "object"))
		var label = str(desc.get("label", verb.capitalize()))
		if verb == "wait": continue  # wait is always available; skip cluttering the menu

		var action_type = "interact"
		if verb == "speak" or verb == "talk": action_type = "speak"
		elif verb == "attack": action_type = "attack"

		var target_agent = ("object:" + target_id) if target_kind == "object" else target_id
		var action_desc_text = "%s %s" % [verb, target_name]
		var action = {
			"label": label,
			"actionType": action_type,
			"targetKind": target_kind,
			"targetId": target_id,
			"targetName": target_name,
			"distance": 0.0,
			"hint": action_desc_text,
			"payload": {
				"targetAgent": target_agent,
				"actionDescription": action_desc_text,
				"flair": "action:%s" % verb
			}
		}

		var group_key = target_id if target_id != "" else verb
		if not by_target.has(group_key):
			by_target[group_key] = {
				"targetKind": target_kind,
				"targetId": target_id,
				"targetName": target_name,
				"distance": 0.0,
				"actions": []
			}
			order.append(group_key)
		by_target[group_key]["actions"].append(action)

	var result: Array = []
	for key in order:
		result.append(by_target[key])
	return result

func _filter_grant_gated_actions(groups: Array, server_legal: Dictionary) -> Array:
	"""Remove grant-gated actions (unlock, lock, write, carry) that the server did not permit.
	Non-grant-gated actions (inspect, talk, sit, climb, etc.) are kept as-is."""
	const GRANT_GATED := ["unlock", "lock", "write", "carry"]
	var filtered: Array = []
	for group in groups:
		if not (group is Dictionary):
			filtered.append(group)
			continue
		var kind = str(group.get("targetKind", ""))
		var target_id = str(group.get("targetId", ""))
		var actions: Array = group.get("actions", [])
		var kept: Array = []
		for action in actions:
			if not (action is Dictionary):
				kept.append(action)
				continue
			var payload = action.get("payload", {})
			var flair = str(payload.get("flair", ""))
			# Extract action key from flair "action:verb"
			var action_key = ""
			if flair.begins_with("action:"):
				action_key = flair.substr(7)
			if action_key in GRANT_GATED:
				# Only keep if server confirmed this verb+targetId is legal
				var server_key = action_key + ":" + target_id
				if not server_legal.has(server_key):
					continue
			kept.append(action)
		if not kept.is_empty():
			var g = group.duplicate(true)
			g["actions"] = kept
			filtered.append(g)
	return filtered

func _is_object_held(row: Dictionary) -> bool:
	"""True when this world object is currently in someone's inventory."""
	var props = row.get("properties", {})
	if not (props is Dictionary):
		return false
	var held_by = str(props.get("heldBy", "")).strip_edges()
	return held_by != "" and held_by.to_lower() != "null"

func _collect_targets_for_tile(focus_world: Vector2) -> Array:
	var targets: Array = []
	var focus_tile = world_to_tile(focus_world)
	var player_pos = player_node.position if player_node != null else focus_world

	for row in world_objects:
		if not (row is Dictionary):
			continue
		if _is_object_held(row):
			continue
		var snapped_obj = snap_to_tile(Vector2(float(row.get("x", 0.0)), float(row.get("y", 0.0))))
		if world_to_tile(snapped_obj) != focus_tile:
			continue
		if not has_line_of_sight(player_pos, snapped_obj):
			continue
		targets.append({
			"kind": "object",
			"id": str(row.get("id", "")),
			"type": str(row.get("type", "fixture")),
			"name": str(row.get("name", row.get("id", "object"))),
			"x": snapped_obj.x,
			"y": snapped_obj.y,
			"location": str(row.get("location", "")),
			"properties": row.get("properties", {})
		})

	for agent_name in agent_nodes.keys():
		var pos = _get_live_entity_position(agent_name)
		if pos == Vector2.ZERO:
			continue
		if world_to_tile(pos) != focus_tile:
			continue
		if not has_line_of_sight(player_pos, pos):
			continue
		targets.append({
			"kind": "entity",
			"name": agent_name,
			"x": pos.x,
			"y": pos.y,
			"location": get_location_name_for_position(pos, "")
		})

	return targets

func _collect_targets_near(center: Vector2, radius: float) -> Array:
	var targets: Array = []
	var radius_tiles = max(1, int(round(radius / tile_size)))

	for row in world_objects:
		if not (row is Dictionary):
			continue
		if _is_object_held(row):
			continue
		var snapped_obj = snap_to_tile(Vector2(float(row.get("x", 0.0)), float(row.get("y", 0.0))))
		if tile_manhattan_distance_from_world(center, snapped_obj) > radius_tiles:
			continue
		if not has_line_of_sight(center, snapped_obj):
			continue
		targets.append({
			"kind": "object",
			"id": str(row.get("id", "")),
			"type": str(row.get("type", "fixture")),
			"name": str(row.get("name", row.get("id", "object"))),
			"x": snapped_obj.x,
			"y": snapped_obj.y,
			"location": str(row.get("location", "")),
			"properties": row.get("properties", {})
		})

	for agent_name in agent_nodes.keys():
		var pos = _get_live_entity_position(agent_name)
		if pos == Vector2.ZERO:
			continue
		if tile_manhattan_distance_from_world(center, pos) > radius_tiles:
			continue
		if not has_line_of_sight(center, pos):
			continue
		targets.append({
			"kind": "entity",
			"name": agent_name,
			"x": pos.x,
			"y": pos.y,
			"location": get_location_name_for_position(pos, "")
		})

	return targets

func _get_live_entity_position(entity_name: String) -> Vector2:
	if entity_name == "" or entity_name == player_name:
		return Vector2.ZERO
	if entity_anchors.has(entity_name):
		return entity_anchors[entity_name]
	if agent_nodes.has(entity_name):
		var node = agent_nodes[entity_name]
		if is_instance_valid(node):
			return node.position
	var pos = agent_positions.get(entity_name, null)
	if pos is Dictionary:
		return Vector2(float(pos.get("x", 0.0)), float(pos.get("y", 0.0)))
	return Vector2.ZERO

func _build_actions_grouped_by_target(tile_objects: Array) -> Array:
	var groups: Array = []
	for row in tile_objects:
		if not (row is Dictionary):
			continue
		var kind = str(row.get("kind", ""))
		if kind == "player" and str(row.get("name", "")) == player_name:
			continue

		var target_id = ""
		var target_name = ""
		if kind == "object":
			target_id = str(row.get("id", ""))
			target_name = str(row.get("name", target_id))
		else:
			target_id = str(row.get("name", ""))
			target_name = target_id

		if target_id == "":
			continue

		var properties = _derive_target_properties(row)
		var target_pos = Vector2(float(row.get("x", 0.0)), float(row.get("y", 0.0)))
		var distance = tile_manhattan_distance_from_world(player_node.position, target_pos) if player_node != null else 0.0
		var actions = _derive_actions_from_properties(kind, target_id, target_name, properties, distance)
		if actions.is_empty():
			continue

		groups.append({
			"targetKind": kind,
			"targetId": target_id,
			"targetName": target_name,
			"distance": distance,
			"actions": actions
		})

	groups.sort_custom(func(a, b): return int(a.get("distance", 0)) < int(b.get("distance", 0)))
	return groups

func _derive_target_properties(target_row: Dictionary) -> Dictionary:
	var kind = str(target_row.get("kind", ""))
	if kind == "object":
		var props = target_row.get("properties", {})
		if props is Dictionary:
			var merged = props.duplicate(true)
			if not merged.has("interactive"):
				merged["interactive"] = true
			# pocket_size implies the object can be carried (backpack-carryable)
			if _is_truthy(merged.get("pocket_size", false)) and not merged.has("carriable"):
				merged["carriable"] = true
			# Mark items held by the player so the Drop action appears
			if str(merged.get("heldBy", "")) == player_name:
				merged["held_by_player"] = true

			# Ensure transition_point is set for door-like legacy objects
			if _contains_tag(merged, "entrance") or _contains_tag(merged, "door"):
				merged["transition_point"] = true
			if merged.has("doorOpen") and not merged.has("transition_point"):
				merged["transition_point"] = true
			# Do NOT add can_open_close for transition_point objects — they use lock/unlock

			# Climbable: explicit property takes priority; fallback-derive from shape
			var height = str(merged.get("height", "")).to_lower()
			var has_flat = _is_truthy(merged.get("flat_surface", false))
			var passable = _is_truthy(merged.get("passable", true))
			var climbable_heights = ["low", "medium", "counter"]
			if not merged.has("climbable"):
				if not passable and has_flat and climbable_heights.has(height):
					merged["climbable"] = true

			# Sittable: explicit or derive from shape
			if not merged.has("sittable"):
				if _is_truthy(merged.get("sitAround", false)) or (height == "low" and has_flat):
					merged["sittable"] = true

			return merged
		return {"interactive": true}

	# Entities
	# Entities: richer physical properties so target-based and subject-based rules work
	var entity_props = target_row.get("properties", {})
	var ep: Dictionary = (entity_props.duplicate(true) if entity_props is Dictionary else {})
	if not ep.has("interactive"):       ep["interactive"]       = true
	if not ep.has("can_talk"):          ep["can_talk"]          = true
	if not ep.has("can_observe"):       ep["can_observe"]       = true
	if not ep.has("can_attack"):        ep["can_attack"]        = true
	if not ep.has("is_entity"):         ep["is_entity"]         = true
	# Entities (agents/players) can't be written on with normal tools
	if not ep.has("hard_to_write_on"):  ep["hard_to_write_on"]  = true
	return ep

## Safe truthiness check that handles bool, int, float, String, Array, Dict.
## Replaces bare bool() calls on values that may come from JSON (strings, etc.)
func _is_truthy(v) -> bool:
	if v == null: return false
	if v is bool: return v
	if v is int:  return v != 0
	if v is float: return v != 0.0
	if v is String: return v != "" and v.to_lower() != "false" and v.to_lower() != "null" and v != "0"
	if v is Array: return not (v as Array).is_empty()
	if v is Dictionary: return not (v as Dictionary).is_empty()
	return true

func _derive_actions_from_properties(kind: String, target_id: String, target_name: String, properties: Dictionary, distance: float) -> Array:
	var actions: Array = []
	if not _is_truthy(properties.get("interactive", true)):
		return actions

	# ── Target-property rules ────────────────────────────────────────────────
	var candidate_rules: Array = []
	for property_key in property_action_rules.keys():
		if _is_truthy(properties.get(property_key, false)):
			var mapped = property_action_rules[property_key]
			if mapped is Array:
				candidate_rules.append_array(mapped)

	# Always allow inspect on any interactive target.
	if _is_truthy(properties.get("interactive", true)):
		# Append inspect only if not already added via the loop above
		candidate_rules.append_array(property_action_rules.get("interactive", []))

	var seen_keys: Dictionary = {}
	for rule in candidate_rules:
		if not (rule is Dictionary):
			continue
		var action_key = str(rule.get("actionKey", "")).strip_edges()
		if action_key == "" or seen_keys.has(action_key):
			continue

		# Per-rule maxDistance; defaults: observe/inspect=2, speak=3, everything else=1.
		var max_dist: float = float(rule.get("maxDistance", -1))
		if max_dist < 0:
			if action_key == "observe" or action_key == "inspect":
				max_dist = 2.0
			elif str(rule.get("actionType", "")) == "speak":
				max_dist = 3.0
			else:
				max_dist = 1.0
		if distance > max_dist:
			continue

		# Lock/unlock: only show the action that matches the current locked state
		var is_locked = _is_truthy(properties.get("locked", false))
		if action_key == "unlock" and not is_locked:
			continue
		if action_key == "lock" and is_locked:
			continue

		# open/close legacy: suppress for transition_point objects (they use lock/unlock)
		if (action_key == "open" or action_key == "close") and _is_truthy(properties.get("transition_point", false)):
			continue
		if action_key == "close" and not _is_truthy(properties.get("doorOpen", true)):
			continue
		if action_key == "open" and _is_truthy(properties.get("doorOpen", true)):
			continue
		if action_key == "carry" and (_is_truthy(properties.get("rooted", false)) or _is_truthy(properties.get("uncarriable", false))):
			continue
		if action_key == "carry" and str(properties.get("heldBy", "")) == player_name:
			continue

		if _is_truthy(rule.get("requiresInventory", false)) and not _player_has_any_inventory():
			continue
		if rule.has("requiresAny") and rule.get("requiresAny") is Array:
			if not _player_has_any_inventory_tag(rule.get("requiresAny", [])):
				continue
		if action_key == "write" and _is_truthy(properties.get("hard_to_write_on", false)):
			if not _player_has_any_inventory_tag(["paint", "marker_paint"]):
				continue

		seen_keys[action_key] = true
		_append_action(actions, action_key, rule.get("actionType", "interact"),
			rule.get("description", "Interacting with"), rule.get("label", action_key.capitalize()),
			kind, target_id, target_name, distance)

	# ── Subject-property rules (actor-inherent physical actions) ─────────────
	for subject_prop in subject_property_rules.keys():
		if not _is_truthy(player_inherent_properties.get(subject_prop, false)):
			continue
		for rule in subject_property_rules[subject_prop]:
			if not (rule is Dictionary): continue
			var action_key = str(rule.get("actionKey", "")).strip_edges()
			if action_key == "" or seen_keys.has(action_key): continue
			var target_kinds = rule.get("targetKinds", [])
			if (target_kinds is Array) and not target_kinds.is_empty() and not target_kinds.has(kind):
				continue
			if distance > 1: continue  # physical actions require adjacency
			seen_keys[action_key] = true
			_append_action(actions, action_key, rule.get("actionType", "attack"),
				rule.get("description", "Acting on"), rule.get("label", action_key.capitalize()),
				kind, target_id, target_name, distance)

	# ── Tool-action rules (inventory-tag-based actions) ───────────────────────
	# The dict key is the exact tag to match in carried items' properties.tags.
	# Object definitions are the authority — no synonym guessing here.
	for tool_tag in tool_action_rules.keys():
		if not _player_has_any_inventory_tag([tool_tag]): continue
		for rule in tool_action_rules[tool_tag]:
			if not (rule is Dictionary): continue
			var action_key = str(rule.get("actionKey", "")).strip_edges()
			if action_key == "" or seen_keys.has(action_key): continue
			var target_kinds = rule.get("targetKinds", [])
			if (target_kinds is Array) and not target_kinds.is_empty() and not target_kinds.has(kind):
				continue
			# If the rule requires a specific target property, check it
			var req_prop = str(rule.get("targetRequires", ""))
			if req_prop != "" and not _is_truthy(properties.get(req_prop, false)):
				continue
			# Tool actions on objects need target to be interactive; entities always eligible
			if kind == "object" and not _is_truthy(properties.get("interactive", true)):
				continue
			if distance > 1: continue
			seen_keys[action_key] = true
			_append_action(actions, action_key, rule.get("actionType", "interact"),
				rule.get("description", "Using on"), rule.get("label", action_key.capitalize()),
				kind, target_id, target_name, distance)

	# ── Throw (universal: any target within range when player has inventory) ──
	if _player_has_any_inventory() and not seen_keys.has("throw"):
		var throw_max_dist := 4.0
		if distance <= throw_max_dist and not _is_truthy(properties.get("heavy", false)):
			seen_keys["throw"] = true
			actions.append({
				"label": "Throw...",
				"actionType": "interact",
				"targetKind": kind,
				"targetId": target_id,
				"targetName": target_name,
				"distance": distance,
				"hint": "Throw an item at %s" % target_name,
				"needsInventoryPick": true,
				"inventoryPickMode": "throw",
				"payload": {
					"targetAgent": ("object:" + target_id) if kind == "object" else target_id,
					"actionDescription": "Throwing at %s" % target_name,
					"flair": "action:throw"
				}
			})

	return actions

## Helper: build and append one action dict to the list.
func _append_action(actions: Array, action_key: String, action_type, description, label,
		kind: String, target_id: String, target_name: String, distance: float) -> void:
	var verb = str(description)
	var target_agent = ("object:" + target_id) if kind == "object" else target_id
	actions.append({
		"label": str(label),
		"actionType": str(action_type),
		"targetKind": kind,
		"targetId": target_id,
		"targetName": target_name,
		"distance": distance,
		"hint": "%s %s" % [verb, target_name],
		"payload": {
			"targetAgent": target_agent,
			"actionDescription": "%s %s" % [verb, target_name],
			"flair": "action:%s" % action_key
		}
	})

func _player_has_any_inventory() -> bool:
	if player_node == null:
		return false
	if not ("inventory" in player_node):
		return false
	var inv = player_node.inventory
	return inv is Array and not inv.is_empty()

func _player_has_any_inventory_tag(required_tags: Array) -> bool:
	if player_node == null:
		return false
	if not ("inventory" in player_node):
		return false
	var inv = player_node.inventory
	if not (inv is Array):
		return false
	for item in inv:
		if item is Dictionary:
			# Rich item object — check properties.tags array and item name
			var props = item.get("properties", {})
			var item_tags: Array = (props.get("tags", []) if props is Dictionary else [])
			var item_name = str(item.get("name", "")).to_lower()
			for req in required_tags:
				var req_lower = str(req).to_lower()
				if item_name.contains(req_lower):
					return true
				for t in item_tags:
					if str(t).to_lower() == req_lower:
						return true
		else:
			# Plain string ID fallback
			var item_text = str(item).to_lower()
			for req in required_tags:
				if item_text.contains(str(req).to_lower()):
					return true
	return false

func _contains_tag(properties: Dictionary, tag_name: String) -> bool:
	if not properties.has("tags"):
		return false
	var tags = properties.get("tags", [])
	if not (tags is Array):
		return false
	for tag in tags:
		if str(tag).to_lower() == tag_name.to_lower():
			return true
	return false

## Category assignment for an action key.
const ACTION_CATEGORIES := {
	"inspect": "Examine", "observe": "Examine", "read": "Examine",
	"talk": "Social", "trade": "Social",
	"carry": "Items", "drop": "Items", "place_object": "Items",
	"write": "Items", "study": "Items", "light": "Items",
	"unlock": "Items", "lock": "Items",
	"heal": "Items", "apply_herbs": "Items",
	"cut": "Items", "carve": "Items", "bind": "Items",
	"climb_rope": "Items", "unlock_pick": "Items",
	"throw": "Combat",
	"open": "Interact", "close": "Interact", "climb": "Interact", "sit": "Interact",
	"attack": "Combat", "punch": "Combat", "kick": "Combat",
	"shove": "Combat", "grab": "Combat",
}

func _action_category(action_key: String) -> String:
	return ACTION_CATEGORIES.get(action_key, "Interact")

## Nested context menu state — populated in _render_grouped_context_actions.
var _context_menu_groups: Array = []

func _render_grouped_context_actions(groups: Array) -> void:
	_context_menu_groups = groups
	_show_category_level()

## Level 1: show one category-button per category that has at least one action.
func _show_category_level() -> void:
	_clear_context_action_buttons()
	if context_action_status != null:
		context_action_status.text = "Select a category" if _context_menu_groups.size() > 0 else "No interactables on clicked tile"
	if context_action_list == null:
		return

	var cat_order = ["Examine", "Social", "Interact", "Items", "Combat"]

	# Collect which categories are present across all groups
	var present_cats: Dictionary = {}
	for group in _context_menu_groups:
		if not (group is Dictionary): continue
		var group_actions = group.get("actions", [])
		if not (group_actions is Array): continue
		for action in group_actions:
			if not (action is Dictionary): continue
			var flair = str(action.get("payload", {}).get("flair", ""))
			var ak = flair.trim_prefix("action:")
			var cat = _action_category(ak)
			present_cats[cat] = true

	# Target headers for context (non-interactive)
	for group in _context_menu_groups:
		if not (group is Dictionary): continue
		var header = Label.new()
		header.text = "%s (%.0f tiles)" % [str(group.get("targetName", "target")), float(group.get("distance", 0.0))]
		header.add_theme_font_size_override("font_size", 11)
		header.modulate = Color(0.95, 0.95, 0.75, 0.95)
		context_action_list.add_child(header)

	# Separator
	var sep = HSeparator.new()
	context_action_list.add_child(sep)

	# Category buttons
	for cat in cat_order:
		if not present_cats.has(cat): continue
		var btn = Button.new()
		btn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		btn.text = cat + "  ▶"
		btn.pressed.connect(_on_category_selected.bind(cat))
		context_action_list.add_child(btn)
	for cat in present_cats.keys():
		if cat_order.has(cat): continue
		var btn = Button.new()
		btn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		btn.text = cat + "  ▶"
		btn.pressed.connect(_on_category_selected.bind(cat))
		context_action_list.add_child(btn)

	_fit_context_panel()

func _on_category_selected(category: String) -> void:
	_show_action_level(category)

## Level 2: show back button + all actions in the selected category.
func _show_action_level(category: String) -> void:
	_clear_context_action_buttons()
	if context_action_status != null:
		context_action_status.text = "[%s]" % category
	if context_action_list == null:
		return

	# Back button
	var back_btn = Button.new()
	back_btn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	back_btn.text = "◀  Back"
	back_btn.pressed.connect(_show_category_level)
	context_action_list.add_child(back_btn)

	for group in _context_menu_groups:
		if not (group is Dictionary): continue
		var group_actions = group.get("actions", [])
		if not (group_actions is Array): continue

		var cat_actions: Array = []
		for action in group_actions:
			if not (action is Dictionary): continue
			var flair = str(action.get("payload", {}).get("flair", ""))
			var ak = flair.trim_prefix("action:")
			if _action_category(ak) == category:
				cat_actions.append(action)
		if cat_actions.is_empty(): continue

		var header = Label.new()
		header.text = "%s (%.0f tiles)" % [str(group.get("targetName", "target")), float(group.get("distance", 0.0))]
		header.add_theme_font_size_override("font_size", 11)
		header.modulate = Color(0.95, 0.95, 0.75, 0.95)
		context_action_list.add_child(header)

		for action in cat_actions:
			var button = Button.new()
			button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
			button.text = "  " + str(action.get("label", "Action"))
			button.tooltip_text = str(action.get("hint", ""))
			button.pressed.connect(_on_context_action_selected.bind(action))
			context_action_list.add_child(button)

	_fit_context_panel()

func _render_context_actions(actions: Array) -> void:
	_clear_context_action_buttons()
	if context_action_status != null:
		if actions.size() > 0:
			context_action_status.text = "Select an action"
		elif context_has_click_focus:
			context_action_status.text = "No interactables on clicked tile"
		else:
			context_action_status.text = "No relevant actions nearby"
	if context_action_list == null:
		return

	for action in actions:
		if not (action is Dictionary):
			continue
		var button = Button.new()
		button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		var label = str(action.get("label", "Action"))
		var distance = float(action.get("distance", 0.0))
		button.text = "%s (%.0f)" % [label, distance]
		button.tooltip_text = str(action.get("hint", ""))
		button.pressed.connect(_on_context_action_selected.bind(action))
		context_action_list.add_child(button)

	_fit_context_panel()

func _fit_context_panel() -> void:
	"""Resize the ContextActionPanel to exactly contain its content (up to 540px tall)."""
	if context_action_panel == null: return
	# Defer one frame so VBoxContainer has calculated its children's minimum sizes
	await get_tree().process_frame
	if not is_instance_valid(context_action_panel): return
	var vbox = context_action_panel.get_node_or_null("ActionVBox")
	if vbox == null: return
	var min_size = vbox.get_combined_minimum_size()
	var panel_w = 375.0
	var panel_h = clamp(min_size.y + 22.0, 110.0, 540.0)
	context_action_panel.set_offset(SIDE_RIGHT,  context_action_panel.get_offset(SIDE_LEFT) + panel_w)
	context_action_panel.set_offset(SIDE_BOTTOM, context_action_panel.get_offset(SIDE_TOP)  + panel_h)
	vbox.set_offset(SIDE_RIGHT,  panel_w - 10.0)
	vbox.set_offset(SIDE_BOTTOM, panel_h - 10.0)

## Opens the context panel in "inventory picker" mode for place/throw.
## The caller action dict is stored and re-executed with the chosen item ID.
var _inventory_pick_pending_action: Dictionary = {}

func _open_inventory_picker(source_action: Dictionary) -> void:
	if player_node == null or not ("inventory" in player_node):
		return
	var inv = player_node.inventory
	if not (inv is Array) or inv.is_empty():
		if context_action_status != null:
			context_action_status.text = "Inventory is empty."
		return

	_inventory_pick_pending_action = source_action
	_clear_context_action_buttons()
	if context_action_title != null:
		var action_key = str(source_action.get("payload", {}).get("flair", "")).trim_prefix("action:")
		context_action_title.text = ("Place which item?" if action_key == "place_object" else "Throw which item?")
	if context_action_status != null:
		context_action_status.text = "Choose from inventory:"

	for item in inv:
		if not (item is Dictionary): continue
		var item_id = str(item.get("id", ""))
		var item_name = str(item.get("name", item_id))
		if item_id == "": continue
		var button = Button.new()
		button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		button.text = "  " + item_name
		button.pressed.connect(_on_inventory_pick_selected.bind(item_id, item_name))
		context_action_list.add_child(button)

	_fit_context_panel()

func _on_inventory_pick_selected(item_id: String, item_name: String) -> void:
	var action = _inventory_pick_pending_action.duplicate(true)
	_inventory_pick_pending_action = {}
	if action.is_empty() or player_node == null: return

	var payload: Dictionary = action.get("payload", {})
	payload["item"] = item_id
	action["payload"] = payload

	var flair = str(payload.get("flair", ""))
	var action_key = flair.trim_prefix("action:")
	var target_kind = str(action.get("targetKind", ""))
	var target_id = str(action.get("targetId", ""))
	var target_name = str(action.get("targetName", target_id))

	close_context_action_panel()

	if action_key == "place_object":
		var target_obj = _get_world_object_by_id(target_id) if target_kind == "object" else null
		var obj_location = str(target_obj.get("location", player_node.current_location)) if target_obj != null else player_node.current_location
		enqueue_player_action(
			player_name, "interact",
			("object:" + target_id) if target_kind == "object" else target_id,
			obj_location,
			player_node.position.x, player_node.position.y,
			"Placing %s on %s" % [item_name, target_name],
			"", 0.1, item_id, "action:place_object"
		)
		object_interaction_in_last_turn = true
	elif action_key == "throw":
		var target_obj = _get_world_object_by_id(target_id) if target_kind == "object" else null
		var target_agent_id = ("object:" + target_id) if target_kind == "object" else target_id
		var act_loc = player_node.current_location
		if target_obj != null:
			act_loc = str(target_obj.get("location", act_loc))
		enqueue_player_action(
			player_name, "interact",
			target_agent_id, act_loc,
			player_node.position.x, player_node.position.y,
			"Throwing %s at %s" % [item_name, target_name],
			"", 0.3, item_id, "action:throw"
		)
		object_interaction_in_last_turn = true
	elif action_key == "drop":
		# Drop action: item already carried, just place at player's feet
		enqueue_player_action(
			player_name, "interact",
			"object:" + item_id, player_node.current_location,
			player_node.position.x, player_node.position.y,
			"Dropping " + item_name,
			"", 0.1, item_id, "action:drop"
		)
		object_interaction_in_last_turn = true

func _on_context_action_selected(action: Dictionary) -> void:
	if player_node == null:
		return
	if dialogue_request_in_flight or turn_request_in_flight:
		return

	var payload = action.get("payload", {})
	var action_type = str(action.get("actionType", "interact"))
	var target_agent = str(payload.get("targetAgent", ""))
	var action_description = str(payload.get("actionDescription", action.get("hint", "Interacting")))
	var speak_text = str(payload.get("speakText", ""))
	var intensity = float(payload.get("intensity", 0.5))
	var item = str(payload.get("item", ""))
	var flair = str(payload.get("flair", ""))
	var target_kind = str(action.get("targetKind", ""))
	var target_id = str(action.get("targetId", ""))
	object_interaction_in_last_turn = false

	if action_type == "speak" and not target_agent.begins_with("object:"):
		var action_key_pre = flair.trim_prefix("action:")
		if action_key_pre == "shout":
			# Broadcast: open dialogue with empty target so server picks nearest listener
			open_dialogue_panel("", false)
			close_context_action_panel()
		else:
			open_dialogue_panel(target_agent)
			close_context_action_panel()
		return

	var action_key = flair.trim_prefix("action:")
	var target_name = str(action.get("targetName", target_id))

	# Place / Throw / Drop → open inventory picker to choose which item.
	if action_key == "place_object" or action.get("needsInventoryPick", false) or action_key == "throw":
		_open_inventory_picker(action)
		return

	# Inspect / observe → local examine panel, no server round-trip.
	if action_key == "inspect" or action_key == "observe":
		close_context_action_panel()
		var target_obj = _get_world_object_by_id(target_id) if target_kind == "object" else null
		if target_obj != null:
			_show_examine_panel_for_object(target_id, str(action.get("targetName", target_id)), target_obj)
		elif target_kind == "entity":
			_show_examine_panel_for_entity(target_id)
		return

	# Climb / sit → move player to object tile (climb) or enqueue activity (sit).
	if action_key == "climb" or action_key == "sit":
		close_context_action_panel()
		var target_obj = _get_world_object_by_id(target_id) if target_kind == "object" else null
		if target_obj != null:
			var obj_world = snap_to_tile(Vector2(float(target_obj.get("x", player_node.position.x)), float(target_obj.get("y", player_node.position.y))))
			var obj_location = str(target_obj.get("location", player_node.current_location))
			var desc = ("Climbing onto " if action_key == "climb" else "Sitting at ") + target_name
			if action_key == "climb":
				# Optimistically move player to the object tile so the client stays in sync
				if player_node != null:
					player_node.position = obj_world
					if "current_location" in player_node:
						player_node.current_location = obj_location
					_rebuild_blocked_tiles_cache()
				# Move to the object's tile with climb flair — server allows it for climbable objects
				object_interaction_in_last_turn = true
				enqueue_player_action(player_name, "move", "", obj_location,
					obj_world.x, obj_world.y, desc, "", 0.3, "", "action:climb")
			else:
				# Sit: stay in place, just update activity
				enqueue_player_action(player_name, "interact", "object:" + target_id,
					obj_location, player_node.position.x, player_node.position.y,
					desc, "", 0.1, "", "action:sit")
		return

	# Write → show text-input dialog; action is sent when the player confirms.
	if action_key == "write":
		close_context_action_panel()
		var target_obj = _get_world_object_by_id(target_id) if target_kind == "object" else null
		var obj_location = str(target_obj.get("location", player_node.current_location)) if target_obj != null else player_node.current_location
		_pending_write_action = {
			"targetAgent": "object:" + target_id if target_kind == "object" else target_id,
			"targetName": target_name,
			"location": obj_location,
			"playerX": player_node.position.x if player_node != null else 0.0,
			"playerY": player_node.position.y if player_node != null else 0.0
		}
		if write_panel != null:
			if write_panel_label != null:
				write_panel_label.text = "Write on " + target_name + ":"
			if write_panel_input != null:
				write_panel_input.text = ""
			write_panel.visible = true
			if write_panel_input != null:
				write_panel_input.grab_focus()
		return

	# Read → show has_writing content locally, no server round-trip.
	if action_key == "read":
		close_context_action_panel()
		var target_obj = _get_world_object_by_id(target_id) if target_kind == "object" else null
		if target_obj != null:
			var props = target_obj.get("properties", {})
			var writing = str(props.get("has_writing", "")).strip_edges() if props is Dictionary else ""
			var obj_name = str(action.get("targetName", target_id))
			_clear_context_action_buttons()
			if context_action_panel != null:
				context_action_panel.visible = true
			if context_action_title != null:
				context_action_title.text = obj_name
			if context_action_status != null:
				context_action_status.text = writing if writing != "" and writing.to_lower() != "null" else "(nothing written)"
		return

	var target_world = Vector2.ZERO
	var target_location = player_node.current_location
	if target_kind == "object":
		var target_obj = _get_world_object_by_id(target_id)
		if target_obj != null:
			var object_world = snap_to_tile(Vector2(float(target_obj.get("x", player_node.position.x)), float(target_obj.get("y", player_node.position.y))))
			target_world = _resolve_interaction_stand_tile(object_world, target_obj)
			target_location = str(target_obj.get("location", target_location))
	elif target_kind == "entity":
		target_world = _get_live_entity_position(target_id)
		target_location = get_location_name_for_position(target_world, target_location)

	# Sanity guard: the context menu is only shown for adjacent/same-tile targets,
	# so this path should only execute when the player is already in range.
	# If somehow they aren't (e.g. they moved between right-click and selection),
	# reject silently rather than auto-pathing.
	var is_reachable = true
	if target_kind == "object":
		var tobj = _get_world_object_by_id(target_id)
		if tobj != null:
			var ow = snap_to_tile(Vector2(float(tobj.get("x", player_node.position.x)), float(tobj.get("y", player_node.position.y))))
			is_reachable = _player_within_object_interaction(player_node.position, ow, tobj)
	elif target_world != Vector2.ZERO:
		is_reachable = tile_manhattan_distance_from_world(player_node.position, target_world) <= dialogue_interaction_tiles

	if not is_reachable:
		if context_action_status != null:
			context_action_status.text = "Too far — move adjacent first."
		close_context_action_panel()
		return

	_execute_context_followup_payload({
		"actionType": action_type,
		"targetAgent": target_agent,
		"actionDescription": action_description,
		"speakText": speak_text,
		"intensity": intensity,
		"item": item,
		"flair": flair,
		"targetKind": target_kind,
		"targetId": target_id
	})

	if context_action_status != null:
		context_action_status.text = "Queued: " + str(action.get("label", action_type))
	if flair.begins_with("action:"):
		if action_key == "carry" and target_kind == "object":
			carry_action_in_last_turn = true
			carried_object_id = target_id
			# Optimistically mark as held so it vanishes from world immediately
			var carry_obj = _get_world_object_by_id(target_id)
			if carry_obj != null:
				var carry_props = carry_obj.get("properties", {})
				if not (carry_props is Dictionary):
					carry_props = {}
					carry_obj["properties"] = carry_props
				carry_props["heldBy"] = player_name
			_rebuild_blocked_tiles_cache()
			_redraw_object_overlays()
		elif action_key == "place_object":
			carried_object_id = ""
	close_context_action_panel()

func _execute_context_followup_payload(payload: Dictionary) -> void:
	if player_node == null:
		return
	object_interaction_in_last_turn = true
	var action_description = str(payload.get("actionDescription", "Interacting"))
	var target_kind = str(payload.get("targetKind", ""))
	var target_id = str(payload.get("targetId", ""))
	if target_kind == "object":
		var object_row = _get_world_object_by_id(target_id)
		if object_row != null:
			_apply_local_passability_effect(action_description, str(payload.get("flair", "")), object_row)

	enqueue_player_action(
		player_name,
		str(payload.get("actionType", "interact")),
		str(payload.get("targetAgent", "")),
		"",
		player_node.position.x,
		player_node.position.y,
		action_description,
		str(payload.get("speakText", "")),
		float(payload.get("intensity", 0.5)),
		str(payload.get("item", "")),
		str(payload.get("flair", ""))
	)

func cancel_pending_context_followup_action() -> void:
	if pending_context_followup_action.is_empty():
		return
	pending_context_followup_action = {}
	if context_action_status != null:
		context_action_status.text = "Queued pathing interrupted"

func _get_world_object_by_id(object_id: String):
	for row in world_objects:
		if row is Dictionary and str(row.get("id", "")) == object_id:
			return row
	return null

func _player_adjacent_to_object_tile(player_world: Vector2, object_world: Vector2) -> bool:
	"""True when on or one tile away from the object (distance 0 = standing on it, 1 = adjacent)."""
	var pt = world_to_tile(player_world)
	var ot = world_to_tile(object_world)
	return tile_manhattan_distance(pt, ot) <= 1


func _player_within_object_interaction(player_world: Vector2, object_world: Vector2, object_row: Variant) -> bool:
	# Path completion and stand resolution use strict adjacency so interactions (e.g. doors) face from a free neighbor tile.
	return _player_adjacent_to_object_tile(player_world, object_world)


func _resolve_interaction_stand_tile(object_world: Vector2, object_row: Variant = null) -> Vector2:
	"""Pick a reachable walkable tile one Manhattan step from the object's tile (prefer ring 1; expand if needed)."""
	if player_node == null:
		return object_world

	var object_tile = world_to_tile(object_world)
	var player_tile = world_to_tile(player_node.position)
	if _player_adjacent_to_object_tile(player_node.position, object_world) and not is_coordinate_blocked(player_node.position):
		return snap_to_tile(player_node.position)

	var bounds = get_world_bounds()
	var best_tile = player_tile
	var best_score = INF
	var found = false
	for ring in range(1, 8):
		var ring_best = INF
		var ring_tile = player_tile
		var any_in_ring = false
		for dx in range(-ring, ring + 1):
			for dy in range(-ring, ring + 1):
				if abs(dx) + abs(dy) != ring:
					continue
				var candidate_tile = object_tile + Vector2i(dx, dy)
				var candidate_world = tile_to_world(candidate_tile)
				if is_coordinate_blocked(candidate_world):
					continue
				if candidate_world.x < bounds.get("minX", 0.0) or candidate_world.x > bounds.get("maxX", 1800.0):
					continue
				if candidate_world.y < bounds.get("minY", 0.0) or candidate_world.y > bounds.get("maxY", 1200.0):
					continue
				var score = tile_manhattan_distance(player_tile, candidate_tile)
				if score < ring_best:
					ring_best = score
					ring_tile = candidate_tile
					any_in_ring = true
		if any_in_ring:
			best_tile = ring_tile
			best_score = ring_best
			found = true
			break

	if not found:
		return snap_to_tile(player_node.position)
	return tile_to_world(best_tile)

func _apply_local_passability_effect(action_description: String, flair: String, object_row: Dictionary) -> void:
	"""Apply lock/unlock (or legacy open/close) locally and persist to server."""
	if object_row == null:
		return
	var lower = str(action_description).to_lower()
	var flair_lower = str(flair).to_lower()
	var patch: Dictionary = {}

	if flair_lower.contains("action:unlock") or (lower.contains("unlock") and not lower.contains("lock")):
		patch = {"locked": false, "passable": true, "doorOpen": true}
	elif flair_lower.contains("action:lock") or lower.begins_with("lock") or lower.contains(" lock"):
		patch = {"locked": true, "passable": false, "doorOpen": false}
	elif lower.contains("close"):
		patch = {"locked": true, "passable": false, "doorOpen": false}
	elif lower.contains("open"):
		patch = {"locked": false, "passable": true, "doorOpen": true}
	else:
		return

	var props = object_row.get("properties", {})
	if not (props is Dictionary):
		props = {}
		object_row["properties"] = props
	for k in patch:
		props[k] = patch[k]
	_rebuild_blocked_tiles_cache()
	var obj_id = str(object_row.get("id", ""))
	if obj_id != "":
		_patch_object_properties_async(obj_id, patch)

func _sync_player_inventory_async() -> void:
	"""Re-fetch /player/{name} and push inventoryObjects into player_node.inventory."""
	if player_node == null:
		return
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request("%s/player/%s" % [backend_url, player_name.uri_encode()])
	if err != OK:
		http.queue_free()
		return
	var response = await http.request_completed
	http.queue_free()
	if response[1] != 200:
		return
	var json = JSON.new()
	if json.parse(response[3].get_string_from_utf8()) != OK:
		return
	var payload = json.data
	if not (payload is Dictionary):
		return
	var inv_objects: Array = payload.get("inventoryObjects", [])
	if not (inv_objects is Array):
		inv_objects = []
	if "inventory" in player_node:
		if not inv_objects.is_empty():
			player_node.inventory = inv_objects.duplicate()
		else:
			var inv_ids: Array = payload.get("inventory", [])
			player_node.inventory = (inv_ids if inv_ids is Array else []).duplicate()
	# If the inventory panel is currently open, re-render it with fresh data
	if context_action_panel != null and context_action_panel.visible \
			and context_action_title != null and context_action_title.text == "Inventory":
		_clear_context_action_buttons()
		if context_action_status != null:
			context_action_status.text = "Empty" if inv_objects.is_empty() else "%d item(s)" % inv_objects.size()
		if context_action_list != null:
			if inv_objects.is_empty():
				var empty_label = Label.new()
				empty_label.text = "(nothing carried)"
				empty_label.add_theme_font_size_override("font_size", 12)
				empty_label.modulate = Color(0.7, 0.7, 0.7, 0.9)
				context_action_list.add_child(empty_label)
			else:
				for item_obj in inv_objects:
					if not (item_obj is Dictionary):
						continue
					var item_id = str(item_obj.get("id", ""))
					var item_name = str(item_obj.get("name", item_id))
					var item_props = item_obj.get("properties", {})
					var item_desc = ""
					if item_props is Dictionary:
						item_desc = str(item_props.get("description", "")).strip_edges()
					var row = HBoxContainer.new()
					row.size_flags_horizontal = Control.SIZE_EXPAND_FILL
					var name_label = Label.new()
					name_label.text = item_name
					name_label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
					name_label.add_theme_font_size_override("font_size", 12)
					name_label.modulate = Color(0.95, 0.95, 0.78, 1.0)
					if item_desc != "" and item_desc.to_lower() != "null":
						name_label.tooltip_text = item_desc
					row.add_child(name_label)
					var drop_btn = Button.new()
					drop_btn.text = "Drop"
					drop_btn.add_theme_font_size_override("font_size", 11)
					drop_btn.pressed.connect(_on_inventory_drop_pressed.bind(item_id, item_name))
					row.add_child(drop_btn)
					context_action_list.add_child(row)

func _patch_object_properties_async(obj_id: String, patch: Dictionary) -> void:
	"""PATCH /objects/{id}/properties — merge-update a subset of an object's properties."""
	var http = HTTPRequest.new()
	add_child(http)
	var err = http.request(
		backend_url + "/objects/" + obj_id.uri_encode() + "/properties",
		["Content-Type: application/json"],
		HTTPClient.METHOD_PATCH,
		JSON.stringify(patch)
	)
	if err != OK:
		http.queue_free()
		push_error("[OBJECTS] Failed to PATCH properties for: " + obj_id)
		return
	var response = await http.request_completed
	http.queue_free()
	if response[1] != 200:
		push_error("[OBJECTS] PATCH /objects/%s/properties failed: %d" % [obj_id, response[1]])
		return
	# Update local world_objects entry so future describe calls see the new state.
	var json = JSON.new()
	if json.parse(response[3].get_string_from_utf8()) == OK and json.data is Dictionary:
		var updated_props = json.data.get("properties", null)
		if updated_props is Dictionary:
			for obj in world_objects:
				if obj is Dictionary and str(obj.get("id", "")) == obj_id:
					obj["properties"] = updated_props
					break

func open_dialogue_panel(target_agent: String, focus_input: bool = true):
	"""Set current conversation target and optionally focus input."""
	if is_context_action_open():
		close_context_action_panel()
	active_dialogue_target = target_agent.strip_edges()
	if dialogue_panel != null:
		dialogue_panel.visible = true
	if dialogue_target_label != null:
		dialogue_target_label.text = "Talking to: " + (active_dialogue_target if active_dialogue_target != "" else "(broadcast)")
	if dialogue_status != null:
		dialogue_status.text = "Shouting (nearest agent hears)" if active_dialogue_target == "" else "Talking with " + active_dialogue_target
	if dialogue_input != null and focus_input:
		dialogue_input.grab_focus()

func close_dialogue_panel(clear_target: bool = true):
	if dialogue_input != null:
		dialogue_input.release_focus()
	if clear_target:
		active_dialogue_target = ""
	if dialogue_target_label != null:
		dialogue_target_label.text = "Talking to: " + (active_dialogue_target if active_dialogue_target != "" else "(auto-nearest)")
	if dialogue_status != null:
		dialogue_status.text = "Click the input box to chat"
	last_conversation_signature = ""  # allow same opener to show again next encounter

func is_dialogue_open() -> bool:
	return dialogue_input != null and dialogue_input.has_focus()


func should_suppress_context_menu_for_conversation() -> bool:
	"""Avoid opening the context panel while waiting on dialogue or typing in the chat field (prevents layout glitches)."""
	if dialogue_request_in_flight:
		return true
	if dialogue_input != null and dialogue_input.has_focus():
		return true
	return false

func is_dialogue_text_input_active() -> bool:
	return is_dialogue_open()

func _is_agent_within_dialogue_range(agent_name: String, max_distance: float = -1.0) -> bool:
	if player_node == null or agent_name.strip_edges() == "":
		return false
	var range_limit = dialogue_interaction_tiles if max_distance <= 0.0 else int(max_distance)
	var pos_player = get_agent_position(player_name)
	var pos_other = get_agent_position(agent_name)
	if normalize_location_name(str(pos_player.get("location", ""))) != normalize_location_name(str(pos_other.get("location", ""))):
		return false
	var player_world = Vector2(float(pos_player.get("x", 0.0)), float(pos_player.get("y", 0.0)))
	var other_world = Vector2(float(pos_other.get("x", 0.0)), float(pos_other.get("y", 0.0)))
	return tile_manhattan_distance_from_world(player_world, other_world) <= range_limit

func _resolve_nearest_dialogue_target(max_distance: float = -1.0) -> String:
	if player_node == null:
		return ""
	var range_limit = dialogue_interaction_tiles if max_distance <= 0.0 else int(max_distance)
	var nearest_name = ""
	var nearest_distance = 999999
	for agent_name in agent_nodes.keys():
		if str(agent_name) == player_name:
			continue
		if not _is_agent_within_dialogue_range(str(agent_name), range_limit):
			continue
		var pos_other = get_agent_position(str(agent_name))
		var player_pos = Vector2(player_node.position.x, player_node.position.y)
		var other_pos = Vector2(float(pos_other.get("x", 0.0)), float(pos_other.get("y", 0.0)))
		var d = tile_manhattan_distance_from_world(player_pos, other_pos)
		if d < nearest_distance:
			nearest_distance = d
			nearest_name = str(agent_name)
	return nearest_name

func _resolve_dialogue_target_for_player() -> String:
	if active_dialogue_target != "" and _is_agent_within_dialogue_range(active_dialogue_target):
		return active_dialogue_target
	return _resolve_nearest_dialogue_target()

func _show_entity_speech(entity_name: String, text: String) -> void:
	"""Display a floating speech bubble above an entity node."""
	if entity_name == player_name:
		if player_node != null and player_node.has_method("show_speech"):
			player_node.show_speech(text)
		return
	if agent_nodes.has(entity_name):
		var node = agent_nodes[entity_name]
		if is_instance_valid(node) and node.has_method("show_speech"):
			node.show_speech(text)

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
	var speaker_safe = _escape_bbcode_text(clean_speaker)
	var text_safe = _escape_bbcode_text(clean_text)
	var speaker_color = "#ff8a3d" if clean_speaker == player_name else "#a9d9ff"
	var line = "[color=%s]%s:[/color] %s" % [speaker_color, speaker_safe, text_safe]
	if dialogue_log.text == "":
		dialogue_log.text = line
	else:
		dialogue_log.append_text("\n" + line)
	dialogue_log.scroll_to_line(9999)

func _escape_bbcode_text(value: String) -> String:
	return value.replace("[", "(").replace("]", ")")

func _apply_conversations_from_response(conversations: Array) -> void:
	"""Consume backend conversation events and surface NPC-initiated dialogue."""
	if conversations.is_empty():
		return

	for row in conversations:
		if not (row is Dictionary):
			continue
		var speaker = str(row.get("name", "")).strip_edges()
		var message = str(row.get("message", "")).strip_edges()
		if speaker == "" or message == "":
			continue

		var signature = speaker + "::" + message
		if signature == last_conversation_signature or _dialogue_signatures_seen.has(signature):
			continue
		last_conversation_signature = signature
		_dialogue_signatures_seen[signature] = true

		if speaker != player_name:
			var speaker_nearby = can_interact(player_name, speaker)
			if speaker_nearby:
				if active_dialogue_target != speaker:
					open_dialogue_panel(speaker, false)
				if dialogue_status != null:
					dialogue_status.text = speaker + " says something nearby"

		append_dialogue_line(speaker, message)
		_show_entity_speech(speaker, message)

func _set_dialogue_busy(is_busy: bool):
	dialogue_request_in_flight = is_busy
	if dialogue_send_button != null:
		dialogue_send_button.disabled = is_busy
	if dialogue_input != null:
		dialogue_input.editable = not is_busy
	if dialogue_status != null:
		dialogue_status.text = "Thinking..." if is_busy else "Type a message and send"
	if player_node != null:
		player_node.set_action_lock(false)

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
	# Broadcast mode: active_dialogue_target == "" means shout — no specific target required
	var is_broadcast = active_dialogue_target == ""
	var target_agent = ""
	if is_broadcast:
		target_agent = ""  # server picks nearest listener
	else:
		target_agent = _resolve_dialogue_target_for_player()
		if target_agent == "":
			if dialogue_status != null:
				dialogue_status.text = "No one nearby to talk to"
			return
		active_dialogue_target = target_agent
		if dialogue_target_label != null:
			dialogue_target_label.text = "Talking to: " + active_dialogue_target

	append_dialogue_line(player_name, message)
	if player_node != null and player_node.has_method("show_speech"):
		player_node.show_speech(message)
	enqueue_player_action(
		player_name,
		"speak",
		target_agent,
		"",
		get_player_position().x,
		get_player_position().y,
		"Speaking" + (" with " + target_agent if target_agent != "" else " aloud"),
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
		debug_header_text = "Backend error: " + str(response_code)
		_refresh_debug_label()
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
		_fetch_objects_async()

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
		_redraw_floor_tiles()
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
		_redraw_floor_tiles()
	
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
		set_runtime_debug_header(npc_count, player_count, location_count, str(simulation_time))

	if state.has("conversations") and state.conversations is Array:
		_apply_conversations_from_response(state.conversations)
	
	# Update or create agent nodes
	if state.has("agents"):
		# keep simple counters per location so we can space agents out
		var locCounters = {}
		for agent_data in state.agents:
			var agent_name = agent_data.get("name", "unknown")
			var is_player_agent = agent_name == player_name
			if is_player_agent and player_node == null:
				_spawn_player_node_for_loaded_state(agent_name)
			
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
				var force_loaded_position = force_player_position_sync_once
				player_node.update_from_backend(agent_data, locations, force_loaded_position)
				if force_loaded_position:
					force_player_position_sync_once = false
					player_has_local_movement = true
					agent_positions[player_name] = {
						"location": normalize_location_name(player_node.current_location),
						"x": player_node.position.x,
						"y": player_node.position.y
					}
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
			push_error("Action enqueue failed: " + str(error_msg))
			turn_request_in_flight = false
			if dialogue_request_in_flight:
				_set_dialogue_busy(false)
	else:
		push_error("Action enqueue failed with code: " + str(response_code))
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
	# Server uses playerX/Y for awareness (findLocationAt). Must be the avatar's *current* world position,
	# not the last move *destination* (last_runtime_request), or pathing/affordances desync mid-walk.
	if player_node != null:
		request_body["playerX"] = player_node.position.x
		request_body["playerY"] = player_node.position.y
	elif last_runtime_request.has("playerX"):
		request_body["playerX"] = last_runtime_request["playerX"]
		request_body["playerY"] = last_runtime_request["playerY"]
	if active_dialogue_target != "" and _is_agent_within_dialogue_range(active_dialogue_target):
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
		if player_node != null:
			last_runtime_request["playerX"] = player_node.position.x
			last_runtime_request["playerY"] = player_node.position.y
		
		var action_result = response.get("actionResult", {})
		# Sync player inventory whenever the turn response includes playerState
		var player_state = action_result.get("playerState", null)
		if player_state is Dictionary and player_node != null and ("inventory" in player_node):
			var inv_objects = player_state.get("inventoryObjects", [])
			if inv_objects is Array and not inv_objects.is_empty():
				player_node.inventory = inv_objects.duplicate()
			else:
				var inv_ids = player_state.get("inventory", [])
				if inv_ids is Array:
					player_node.inventory = inv_ids.duplicate()

		# Apply knocked-back agent position immediately from the action result.
		# update_from_backend only sets position on first spawn, so we set it directly.
		var target_agent_state = action_result.get("targetAgentState", null)
		if target_agent_state is Dictionary and target_agent_state.has("name") \
				and target_agent_state.has("x") and target_agent_state.has("y"):
			var tname = str(target_agent_state.get("name", ""))
			if tname != "" and agent_nodes.has(tname):
				var knocked_node = agent_nodes[tname]
				if is_instance_valid(knocked_node):
					var new_pos = snap_to_tile(Vector2(
						float(target_agent_state.get("x", 0.0)),
						float(target_agent_state.get("y", 0.0))
					))
					knocked_node.position = new_pos
					entity_anchors[tname] = new_pos
					agent_positions[tname] = {
						"location": str(target_agent_state.get("location", agent_positions.get(tname, {}).get("location", ""))),
						"x": new_pos.x,
						"y": new_pos.y
					}

		if action_result.get("success", false):
			if action_result.has("agentReplyText") and str(action_result.get("agentReplyText", "")) != "":
				var speaker = str(action_result.get("agentReplySpeaker", active_dialogue_target))
				var reply_text = str(action_result.get("agentReplyText", ""))
				append_dialogue_line(speaker, reply_text)
				_show_entity_speech(speaker, reply_text)
		else:
			var error_msg = action_result.get("result", action_result.get("error", "Unknown error"))
			push_error("Turn processing failed: ", error_msg)
			if dialogue_status != null:
				dialogue_status.text = "Dialogue failed: " + str(error_msg)
	else:
		push_error("Turn processing failed with code: " + str(response_code))
		if dialogue_status != null:
			dialogue_status.text = "Dialogue request failed"

	turn_request_in_flight = false
	if dialogue_request_in_flight:
		_set_dialogue_busy(false)
	_dispatch_pending_context_followup_if_any()
	if object_interaction_in_last_turn:
		object_interaction_in_last_turn = false
		await _fetch_objects_async()
		await _sync_player_inventory_async()
		if carry_action_in_last_turn:
			carry_action_in_last_turn = false
			# Auto-show inventory panel so the player sees their newly acquired item
			if context_action_panel == null or not context_action_panel.visible:
				open_inventory_panel()
	_dispatch_queued_move_if_any()
	
	if is_instance_valid(http):
		http.queue_free()

func _dispatch_pending_context_followup_if_any() -> void:
	if pending_context_followup_action.is_empty():
		return
	# Do not block walk-to-interact on dialogue UI; only one turn HTTP chain at a time.
	if turn_request_in_flight:
		return
	if player_node == null:
		pending_context_followup_action = {}
		return

	var payload = pending_context_followup_action
	var target_kind = str(payload.get("targetKind", ""))
	var target_id = str(payload.get("targetId", ""))
	var target_world = Vector2(float(payload.get("targetX", 0.0)), float(payload.get("targetY", 0.0)))
	var target_location = str(payload.get("targetLocation", player_node.current_location))

	if target_kind == "object":
		var target_obj = _get_world_object_by_id(target_id)
		if target_obj != null:
			var object_world = snap_to_tile(Vector2(float(target_obj.get("x", target_world.x)), float(target_obj.get("y", target_world.y))))
			target_world = _resolve_interaction_stand_tile(object_world, target_obj)
			target_location = str(target_obj.get("location", target_location))
	elif target_kind == "entity":
		var entity_pos = _get_live_entity_position(target_id)
		if entity_pos != Vector2.ZERO:
			target_world = snap_to_tile(entity_pos)
			target_location = get_location_name_for_position(target_world, target_location)

	payload["targetX"] = target_world.x
	payload["targetY"] = target_world.y
	payload["targetLocation"] = target_location
	pending_context_followup_action = payload

	var still_need_move = false
	if target_kind == "object":
		var tobj2 = _get_world_object_by_id(target_id)
		if tobj2 != null:
			var ow2 = snap_to_tile(Vector2(float(tobj2.get("x", target_world.x)), float(tobj2.get("y", target_world.y))))
			still_need_move = not _player_within_object_interaction(player_node.position, ow2, tobj2)
	elif target_world != Vector2.ZERO:
		still_need_move = tile_manhattan_distance_from_world(player_node.position, target_world) > 1

	if still_need_move and target_world != Vector2.ZERO:
		player_has_local_movement = false
		enqueue_player_action(
			player_name,
			"move",
			"",
			target_location,
			target_world.x,
			target_world.y,
			"Moving toward " + str(payload.get("targetName", target_id))
		)
		return

	pending_context_followup_action = {}
	_execute_context_followup_payload(payload)

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
	var conversations = response.get("conversations", [])
	if response.has("time") and debug_label != null:
		var simulation_time = str(response.get("time", "--:--"))
		var npc_count = max(0, agents_data.size() - 1)
		var location_count = locations_data.size()
		set_runtime_debug_header(npc_count, 1, location_count, simulation_time)
	
	# Update agents
	for agent_data in agents_data:
		_update_agent_from_response(agent_data)
		agent_positions[agent_data.get("name", "unknown")] = {
			"location": agent_data.get("location", "unknown"),
			"x": float(agent_data.get("x", 0.0)),
			"y": float(agent_data.get("y", 0.0))
		}
		if player_node != null and agent_data.get("name", "") == player_name:
			var force_pos = not pending_context_followup_action.is_empty()
			player_node.update_from_backend(agent_data, locations, force_pos)

	# Snapshot live node positions as anchors before any NPC steps so that
	# approach pathing always targets actual pixel positions, not stale coords.
	if player_node != null:
		entity_anchors[player_name] = player_node.position
	for _aname in agent_nodes.keys():
		var _an = agent_nodes[_aname]
		if is_instance_valid(_an):
			entity_anchors[_aname] = _an.position

	# Step each NPC one tile toward its target (client-side pathfinding)
	for agent_name in agent_nodes.keys():
		var agent_node = agent_nodes[agent_name]
		if is_instance_valid(agent_node) and agent_node.has_method("step_client_side"):
			agent_node.step_client_side(self)
			_apply_los_to_agent(agent_name, agent_node)
			# Update anchor after the step so other NPCs see the new position next turn
			entity_anchors[agent_name] = agent_node.position
			agent_positions[agent_name] = {
				"location": get_location_name_for_position(agent_node.position, str(agent_positions.get(agent_name, {}).get("location", ""))),
				"x": agent_node.position.x,
				"y": agent_node.position.y
			}
	
	# Update locations if needed
	for loc_data in locations_data:
		var loc_name = loc_data.get("name", "")
		if locations.has(loc_name):
			locations[loc_name] = loc_data

	if conversations is Array:
		_apply_conversations_from_response(conversations)

	# Refresh LOS-based visibility every turn so overlays follow the player
	_redraw_object_overlays()
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
		_apply_los_to_agent(agent_name, agent)

func _apply_los_to_agent(agent_name: String, agent_node) -> void:
	"""Show/hide an agent node based on line-of-sight from the player."""
	if player_node == null or not is_instance_valid(agent_node):
		return
	var agent_pos = agent_node.position
	var visible_to_player = has_line_of_sight(player_node.position, agent_pos)
	agent_node.modulate.a = 1.0 if visible_to_player else 0.0

func _save_state():
	await _save_selected_slot()

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
	bhttp.timeout = 180.0  # 3-minute ceiling — LLM calls can be slow but shouldn't hang forever
	add_child(bhttp)
	var berr = bhttp.request(
		backend_url + "/world/bootstrap",
		["Content-Type: application/json"],
		HTTPClient.METHOD_POST,
		"{}"
	)
	if berr != OK:
		bhttp.queue_free()
		push_error("[BOOTSTRAP] Failed to send /world/bootstrap request")
		return
	var bresp = await bhttp.request_completed
	bhttp.queue_free()
	if bresp[1] == 200:
		json = JSON.new()
		json.parse(bresp[3].get_string_from_utf8())
		var res = json.data.get("result", {})
		print("[BOOTSTRAP] Done. Bootstrapped: ", res.get("bootstrapped", []), " | Skipped: ", res.get("skipped", []))
	elif bresp[1] == -1:
		push_error("[BOOTSTRAP] Schedule bootstrap timed out after 3 minutes — continuing without schedules")
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
		# Esc clears focus from the input and (optionally) current target.
		if event.keycode == KEY_ESCAPE and is_dialogue_open():
			close_dialogue_panel()
			get_viewport().set_input_as_handled()
			return
		if event.keycode == KEY_ESCAPE and is_context_action_open():
			close_context_action_panel()
			get_viewport().set_input_as_handled()
			return

	if event is InputEventKey and event.pressed and event.ctrl_pressed:
		if event.keycode == KEY_S:
			print("Saving state...")
			await _save_state()

		if event.keycode == KEY_L:
			await _open_save_load_panel()
		
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

func _ensure_floor_tiles_container():
	"""Create a world-space container for tiled floor sprites, drawn below all overlays."""
	if world_node == null:
		return

	if world_node.has_node("FloorTiles"):
		floor_tiles_container = world_node.get_node("FloorTiles") as Node2D
		return

	floor_tiles_container = Node2D.new()
	floor_tiles_container.name = "FloorTiles"
	floor_tiles_container.z_index = -10
	world_node.add_child(floor_tiles_container)

func _get_floor_texture_path(loc_type: String, loc_name: String) -> String:
	"""Map a location type (and name fallback) to the appropriate floor sprite."""
	var t = loc_type.to_lower()
	var n = loc_name.to_lower()
	if t in ["outside", "street", "road"] or n == "street" or n == "outside":
		return "res://assets/floor_sprites/grass_floor.png"
	if t == "tavern" or n == "tavern":
		return "res://assets/floor_sprites/wood_floor_fine.png"
	if t in ["cafe", "coffee", "cafe_shop"] or n.contains("coffee") or n.contains("cafe"):
		return "res://assets/floor_sprites/tile_floor.png"
	if t == "market" or n == "market":
		return "res://assets/floor_sprites/stone_floor.png"
	if t in ["public", "plaza", "park"] or n.contains("square") or n.contains("plaza"):
		return "res://assets/floor_sprites/stone_floor_grassy.png"
	if t in ["residential", "home", "house"] or n == "home":
		return "res://assets/floor_sprites/wood_floor_pane.png"
	return "res://assets/floor_sprites/stone_floor.png"

func _redraw_floor_tiles():
	"""Fill each location's bounds with a tiled floor sprite."""
	if floor_tiles_container == null:
		_ensure_floor_tiles_container()
	if floor_tiles_container == null:
		return

	for child in floor_tiles_container.get_children():
		child.queue_free()

	# Separate outdoor (background) from enclosed so outdoor is drawn first.
	var outdoor_locs: Array = []
	var enclosed_locs: Array = []
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
		if _is_transit_location_name(loc_name, loc):
			outdoor_locs.append(loc_name)
		else:
			enclosed_locs.append(loc_name)

	for loc_name in outdoor_locs + enclosed_locs:
		var loc = locations[loc_name]
		var min_x = float(loc.get("minX", 0.0))
		var max_x = float(loc.get("maxX", min_x))
		var min_y = float(loc.get("minY", 0.0))
		var max_y = float(loc.get("maxY", min_y))
		var loc_type = str(loc.get("type", "generic"))
		var tex_path = _get_floor_texture_path(loc_type, loc_name)
		var tex = load(tex_path) as Texture2D
		if tex == null:
			continue

		var w = max_x - min_x
		var h = max_y - min_y
		var tex_size = tex.get_size()
		# Scale UVs so exactly 1 game tile (tile_size px) = 1 full texture repeat.
		var uv_w = (w / tile_size) * tex_size.x
		var uv_h = (h / tile_size) * tex_size.y

		var floor_poly = Polygon2D.new()
		floor_poly.texture = tex
		floor_poly.texture_repeat = CanvasItem.TEXTURE_REPEAT_ENABLED
		floor_poly.modulate = Color(1.0, 1.0, 1.0, 1.0)
		floor_poly.polygon = PackedVector2Array([
			Vector2(min_x, min_y),
			Vector2(max_x, min_y),
			Vector2(max_x, max_y),
			Vector2(min_x, max_y)
		])
		floor_poly.uv = PackedVector2Array([
			Vector2(0.0,  0.0),
			Vector2(uv_w, 0.0),
			Vector2(uv_w, uv_h),
			Vector2(0.0,  uv_h)
		])
		floor_tiles_container.add_child(floor_poly)

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

func _ensure_grid_overlay_container():
	"""Create a world-space container for tile grid debug lines and coordinate labels."""
	if world_node == null:
		return

	if world_node.has_node("GridOverlay"):
		grid_overlay = world_node.get_node("GridOverlay") as Node2D
		return

	grid_overlay = Node2D.new()
	grid_overlay.name = "GridOverlay"
	grid_overlay.z_index = 10
	world_node.add_child(grid_overlay)

func _redraw_grid_overlay():
	"""Render a semi-transparent world grid where each 32px tile maps to one tile coordinate."""
	if grid_overlay == null:
		_ensure_grid_overlay_container()
	if grid_overlay == null:
		return

	for child in grid_overlay.get_children():
		child.queue_free()

	var bounds = get_world_bounds()
	var min_x = float(bounds.get("minX", 0.0))
	var max_x = float(bounds.get("maxX", 1800.0))
	var min_y = float(bounds.get("minY", 0.0))
	var max_y = float(bounds.get("maxY", 1200.0))

	var start_x = floor(min_x / tile_size) * tile_size
	var end_x = floor(max_x / tile_size) * tile_size
	var start_y = floor(min_y / tile_size) * tile_size
	var end_y = floor(max_y / tile_size) * tile_size

	var x = start_x
	while x <= end_x:
		var tile_index_x = int(floor(x / tile_size))
		var major_x = tile_index_x % 5 == 0
		var vline = Line2D.new()
		vline.width = 1.5 if major_x else 1.0
		vline.default_color = Color(1, 1, 1, 0.24 if major_x else 0.12)
		vline.points = PackedVector2Array([Vector2(x, start_y), Vector2(x, end_y)])
		grid_overlay.add_child(vline)

		if major_x:
			var xlabel = Label.new()
			xlabel.text = "x:%d" % tile_index_x
			xlabel.position = Vector2(x + (tile_size * 0.5) + 2.0, start_y + 2.0)
			xlabel.add_theme_font_size_override("font_size", 9)
			xlabel.modulate = Color(0.85, 0.95, 1.0, 0.72)
			grid_overlay.add_child(xlabel)
		x += tile_size

	var y = start_y
	while y <= end_y:
		var tile_index_y = int(floor(y / tile_size))
		var major_y = tile_index_y % 5 == 0
		var hline = Line2D.new()
		hline.width = 1.5 if major_y else 1.0
		hline.default_color = Color(1, 1, 1, 0.24 if major_y else 0.12)
		hline.points = PackedVector2Array([Vector2(start_x, y), Vector2(end_x, y)])
		grid_overlay.add_child(hline)

		if major_y:
			var ylabel = Label.new()
			ylabel.text = "y:%d" % tile_index_y
			ylabel.position = Vector2(start_x + 2.0, y + (tile_size * 0.5) + 2.0)
			ylabel.add_theme_font_size_override("font_size", 9)
			ylabel.modulate = Color(1.0, 0.92, 0.80, 0.72)
			grid_overlay.add_child(ylabel)
		y += tile_size

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

	var player_pos_for_los = player_node.position if player_node != null else Vector2.ZERO
	for obj in world_objects:
		if not (obj is Dictionary):
			continue
		if _is_object_held(obj):
			continue  # held items live in inventory, not in the world

		var object_type = str(obj.get("type", "fixture"))
		var object_name = str(obj.get("name", obj.get("id", "object")))
		var tile_origin = snap_to_tile(Vector2(float(obj.get("x", 0.0)), float(obj.get("y", 0.0))))
		var tile_center = tile_to_world_center(world_to_tile(tile_origin))
		var properties = obj.get("properties", {})

		# Walls always drawn; other objects fade when outside LOS
		var in_los = object_type == "wall" or has_line_of_sight(player_pos_for_los, tile_origin)

		var color = _get_object_type_color(object_type)
		var radius = _get_object_marker_radius(object_type)

		var los_alpha = 1.0 if in_los else 0.15
		var sprite_path = _get_object_sprite_path(obj)
		var marker: Node2D
		if sprite_path != "":
			var tex = load(sprite_path) as Texture2D
			if tex != null:
				var sprite_node = Node2D.new()
				var sprite = Sprite2D.new()
				sprite.texture = tex
				sprite.position = tile_center
				var ts = tex.get_size()
				sprite.scale = Vector2(tile_size / ts.x, tile_size / ts.y)
				sprite_node.add_child(sprite)
				marker = sprite_node
		if marker == null:
			marker = _make_object_marker(object_type, tile_center, radius, color)
		marker.modulate.a = los_alpha
		object_overlays.add_child(marker)

		if object_type == "wall":
			continue

		if not in_los:
			continue  # don't draw labels for out-of-sight objects

		var label = Label.new()
		label.text = "%s (%s)" % [object_name, object_type]
		label.position = Vector2(tile_center.x + radius + 4.0, tile_center.y - radius - 2.0)
		label.add_theme_font_size_override("font_size", 10)
		label.modulate = Color(0.98, 0.98, 0.98, 0.95)
		object_overlays.add_child(label)

func _get_object_sprite_path(obj: Dictionary) -> String:
	"""Return res:// path to a sprite for this object, or "" to fall back to geometric marker."""
	var otype = str(obj.get("type", "")).to_lower()
	var oid   = str(obj.get("id",   "")).to_lower()
	var oname = str(obj.get("name", "")).to_lower()
	var loc   = str(obj.get("location", "")).to_lower()

	if otype == "wall":
		return "res://assets/objects/brick_wall.png"

	if otype == "entrance_anchor":
		if loc == "tavern":
			return "res://assets/objects/tavern_door.png"
		if loc == "coffee_shop":
			return "res://assets/objects/coffee_shop_door.png"
		if loc == "market":
			return "res://assets/objects/market_door.png"
		return "res://assets/objects/house_door.png"

	if oid.contains("coffee_machine") or oname.contains("espresso"):
		return "res://assets/objects/coffee_machine.png"
	if oid.contains("register") or oname.contains("register"):
		return "res://assets/objects/register.png"
	if oname.contains("table") or oid.contains("table"):
		return "res://assets/objects/table.png"
	if oname.contains("chair") or oid.contains("chair") or oname.contains("stool") or oid.contains("stool"):
		return "res://assets/objects/chair.png"
	if oname.contains("bed") or oid.contains("bed") or oname.contains("bedside"):
		return "res://assets/objects/bed.png"
	if oname.contains("pencil") or oid.contains("pencil"):
		return "res://assets/objects/pencil.png"
	if (oname.contains("key") or oid.contains("key")) and not oname.contains("turkey"):
		return "res://assets/objects/key.png"
	if oname.contains("coin") or oid.contains("coin") or oname.contains("purse"):
		return "res://assets/objects/coin_purse.png"
	if oname.contains("knife") or oid.contains("knife") or oname.contains("blade"):
		return "res://assets/objects/pocket_kinfe.png"

	return ""

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

func tile_manhattan_distance(tile_a: Vector2i, tile_b: Vector2i) -> int:
	"""Compute Manhattan distance in tiles (cardinal adjacency metric)."""
	return abs(tile_a.x - tile_b.x) + abs(tile_a.y - tile_b.y)

func tile_manhattan_distance_from_world(world_a: Vector2, world_b: Vector2) -> int:
	var tile_a = world_to_tile(world_a)
	var tile_b = world_to_tile(world_b)
	return tile_manhattan_distance(tile_a, tile_b)

func tile_to_world(tile_position: Vector2i) -> Vector2:
	"""Convert integer tile coordinates to world-space top-left coordinate."""
	return Vector2(float(tile_position.x) * tile_size, float(tile_position.y) * tile_size)

func tile_to_world_center(tile_position: Vector2i) -> Vector2:
	"""Convert integer tile coordinates to the center point of that tile cell."""
	var origin = tile_to_world(tile_position)
	return origin + Vector2(tile_size * 0.5, tile_size * 0.5)

func snap_to_tile(world_position: Vector2) -> Vector2:
	"""Snap an arbitrary world coordinate to its containing tile origin."""
	var tx = int(floor(world_position.x / tile_size))
	var ty = int(floor(world_position.y / tile_size))
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
	
	# Check Manhattan tile distance (<= 3 tiles)
	var world1 = Vector2(float(pos1.get("x", 0.0)), float(pos1.get("y", 0.0)))
	var world2 = Vector2(float(pos2.get("x", 0.0)), float(pos2.get("y", 0.0)))
	return tile_manhattan_distance_from_world(world1, world2) <= dialogue_interaction_tiles
