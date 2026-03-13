extends Node

# Configuration
var backend_url = "http://localhost:8080"
var use_delta_endpoint = true  # Use lightweight /state/delta instead of full /state
var runtime_awareness_radius = 180.0
var auto_start_backend = true
var backend_wait_attempts = 24
var backend_wait_interval = 0.5
var tile_size = 32.0

# References
@onready var agents_container = get_node("../World/Agents")
@onready var debug_label = get_node("../UI/DebugLabel")
@onready var world_node = get_node("../World")

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

# Save/load file path
var save_file_path = "user://game_state.json"

func _ready():
	print("Backend Connector initialized")
	_ensure_location_overlay_container()

	var backend_ready = await _wait_for_backend_ready()
	if not backend_ready:
		push_error("Backend not reachable at " + backend_url + ". Check start_server.bat or run server manually.")
		return
	
	# Now proceed with initialization
	if FileAccess.file_exists(save_file_path):
		print("Found saved state, loading...")
		await _load_and_initialize_state()
	else:
		print("No saved state, creating new world...")
		await _initialize_new_world()
	
	# NOW fetch locations after world is fully initialized
	_fetch_locations()

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

func _initialize_new_world():
	"""Create initial agents and top-level locations with spatial bounds."""
	var locations_config = {
		"market": {"type": "market", "bounds": {"minX": 0, "maxX": 600, "minY": 0, "maxY": 500}},
		"tavern": {"type": "tavern", "bounds": {"minX": 700, "maxX": 1200, "minY": 0, "maxY": 450}},
		"coffee_shop": {"type": "cafe", "bounds": {"minX": 1300, "maxX": 1800, "minY": 0, "maxY": 400}},
		"town_square": {"type": "public", "bounds": {"minX": 400, "maxX": 1100, "minY": 600, "maxY": 1200}},
		"home": {"type": "residential", "bounds": {"minX": 80, "maxX": 350, "minY": 620, "maxY": 980}}
	}
	
	print("[INIT] Creating locations...")
	for loc_name in locations_config.keys():
		var loc_data = locations_config[loc_name]
		_create_location(loc_name, loc_data.get("type", "generic"), loc_data.get("bounds", {}))
	
	# Wait much longer for location creation to complete
	print("[INIT] Waiting for locations to be created on server...")
	await get_tree().create_timer(2.0).timeout
	
	# Create initial agents with starting positions
	var starting_agents = [
		{
			"name": "Klaus",
			"location": "coffee_shop",
			"memories": ["I love a good cup of coffee in the morning."],
			"activity": "Entering the coffee shop."
		},
		{
			"name": "Maria",
			"location": "market",
			"memories": ["I need to buy fresh vegetables today."],
			"activity": "Looking at the produce."
		},
		{
			"name": "John",
			"location": "tavern",
			"memories": ["The tavern is a good place to hear local gossip."],
			"activity": "Sitting at the bar."
		}
	]
	
	print("[INIT] Creating agents...")
	for agent_data in starting_agents:
		_create_agent(agent_data)
	
	# Wait for agents to be created
	await get_tree().create_timer(1.0).timeout
	
	# Create the player character
	print("[INIT] Creating player character...")
	_create_player({
		"name": "Player",
		"location": "town_square",
		"activity": "Looking around the town square",
		"memories": ["I've arrived in this strange town.", "I should explore and meet the locals."]
	})
	
	print("[INIT] Initialization complete!")

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
	http.request_completed.connect(_on_locations_received)
	
	var error = http.request(backend_url + "/locations")
	if error != OK:
		push_error("Failed to fetch locations")

func _on_locations_received(result, response_code, headers, body):
	"""Handle locations response with spatial bounds"""
	var http_nodes = get_children().filter(func(n): return n is HTTPRequest)
	if http_nodes.size() > 0:
		http_nodes[0].queue_free()
	
	if response_code == 200:
		var json = JSON.new()
		json.parse(body.get_string_from_utf8())
		var response_data = json.data
		
		# Extract locations array
		var locs = []
		if response_data is Dictionary and response_data.has("locations"):
			locs = response_data.locations
		elif response_data is Array:
			locs = response_data
		else:
			push_error("Unexpected locations format: " + str(response_data))
			return
		
		# Store locations with their spatial data
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
	else:
		push_error("Failed to fetch locations, code: " + str(response_code))

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
	"""POST request to create a player character"""
	print("Starting player creation with data: ", player_data)
	var http = HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(_on_player_created.bind(http))
	
	var headers = ["Content-Type: application/json"]
	var body = JSON.stringify(player_data)
	
	var error = http.request(
		backend_url + "/player",
		headers,
		HTTPClient.METHOD_POST,
		body
	)
	
	if error != OK:
		push_error("Failed to create player: " + player_data.get("name", "unknown"))
		http.queue_free()
	else:
		print("Player creation request sent")

func _on_player_created(result, response_code, headers, body, http):
	"""Handle player creation response"""
	print("Player creation response - code: ", response_code, " body: ", body.get_string_from_utf8())
	if response_code == 200 or response_code == 201:
		var json = JSON.new()
		json.parse(body.get_string_from_utf8())
		var player_data = json.data
		print("Player created: ", player_data.get("name", "unknown"))
		
		# Spawn the player node in the scene
		var player_node_instance = player_scene.instantiate()
		player_node_instance.name = "Player"
		agents_container.add_child(player_node_instance)
		player_node = player_node_instance
		print("Spawned player node in scene at position: ", player_node_instance.position)
		print("Player added to container: ", agents_container.name, " (", agents_container.get_path(), ")")
		print("Player global position: ", player_node_instance.global_position)
		print("Agents container children: ", agents_container.get_children().map(func(c): return c.name))
		
		# Small delay to ensure player is fully initialized
		await get_tree().create_timer(0.1).timeout
		
		# PLAYER-DRIVEN: Poll backend after player creation
		call_deferred("_poll_backend")
	else:
		push_error("Player creation failed with code: " + str(response_code) + " body: " + body.get_string_from_utf8())
	
	if is_instance_valid(http):
		http.queue_free()

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



func _poll_backend():
	"""Poll the backend /state/delta endpoint (lightweight) or /state (full)"""
	print("[BACKEND] _poll_backend called")
	var http = HTTPRequest.new()
	add_child(http)
	http.request_completed.connect(_on_state_received)
	
	var endpoint = "/state/delta" if use_delta_endpoint else "/state"
	var error = http.request(backend_url + endpoint)
	if error != OK:
		push_error("Failed to poll backend on " + endpoint)

func _on_state_received(result, response_code, headers, body):
	"""Handle state response"""
	# Remove the HTTPRequest node
	var http_nodes = get_children().filter(func(n): return n is HTTPRequest)
	if http_nodes.size() > 0:
		http_nodes[0].queue_free()
	
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
		var conversation_count = state.get("conversations", []).size()
		debug_label.text = "NPCs: %d | Player: %d | Locations: %d | Convos: %d" % [
			npc_count, player_count, location_count, conversation_count
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
			
			# store agent position data
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
	else:
		push_error("Action enqueue failed with code: ", response_code, " Body: ", body.get_string_from_utf8())
		turn_request_in_flight = false
	
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
		http.queue_free()

func _on_turn_processed(result, response_code, headers, body, http):
	"""Handle turn processing response"""
	if response_code == 200:
		var json = JSON.new()
		json.parse(body.get_string_from_utf8())
		var response = json.data
		
		var action_result = response.get("actionResult", {})
		if action_result.get("success", false):
			print("Turn processed successfully")
			
			# Update all agents from the response
			_update_agents_from_turn_response(response)
		else:
			var error_msg = action_result.get("error", "Unknown error")
			push_error("Turn processing failed: ", error_msg)
	else:
		push_error("Turn processing failed with code: ", response_code, " Body: ", body.get_string_from_utf8())

	turn_request_in_flight = false
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
	enqueue_player_action(
		player_id,
		"move",
		"",
		current_location,
		player_position.x,
		player_position.y,
		"Walking"
	)

func get_location_name_for_position(world_position: Vector2, fallback: String = "") -> String:
	"""Resolve a location name from loaded location bounds."""
	for loc_name in locations.keys():
		var loc = locations[loc_name]
		if loc is Dictionary:
			var min_x = loc.get("minX", 0.0)
			var max_x = loc.get("maxX", 0.0)
			var min_y = loc.get("minY", 0.0)
			var max_y = loc.get("maxY", 0.0)
			if world_position.x >= min_x and world_position.x <= max_x and world_position.y >= min_y and world_position.y <= max_y:
				return loc_name
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
	http.request_completed.connect(_on_save_state_received)
	
	# Get current state from backend
	http.request(backend_url + "/state")

func _on_save_state_received(result, response_code, headers, body):
	"""Save the received state to disk"""
	var http = get_children().filter(func(n): return n is HTTPRequest)[0]
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
	print("[LOAD] Loaded saved state with %d agents" % saved_state.get("agents", []).size())
	
	# CRITICAL: Recreate the world structure (locations) first
	# Use the same configuration as _initialize_new_world()
	var locations_config = {
		"market": {"type": "market", "bounds": {"minX": 0, "maxX": 600, "minY": 0, "maxY": 500}},
		"tavern": {"type": "tavern", "bounds": {"minX": 700, "maxX": 1200, "minY": 0, "maxY": 450}},
		"coffee_shop": {"type": "cafe", "bounds": {"minX": 1300, "maxX": 1800, "minY": 0, "maxY": 400}},
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
				await _create_player(p)
			else:
				var clean = {"name": name, "location": location, "activity": activity, "memories": memories}
				print("[LOAD] sending create_agent for", clean)
				await _create_agent_async(clean)
	
	print("[LOAD] Load and initialization complete!")

# Dev Testing actions
func _input(event):
	"""Handle keyboard shortcuts"""
	if event is InputEventKey and event.pressed:
		# Press 'S' to save state
		if event.keycode == KEY_S:
			print("Saving state...")
			_save_state()
		
		# Press 'P' to progress simulation manually
		if event.keycode == KEY_P:
			_progress_simulation()
		
		# Press 'N' to create a new random agent
		if event.keycode == KEY_N:
			_create_random_agent()
		
		# Press 'A' to test player action (attack) - OLD METHOD, keeping for reference
		if event.keycode == KEY_A:
			if agent_nodes.has("Klaus") and agent_nodes.has("Maria"):
				# Test attack action
				enqueue_player_action(
					"Klaus",  # player
					"attack",  # action type
					"Maria",  # target agent
					"",  # target location
					50.0, 50.0,  # player x, y
					"Klaus attacks Maria",  # description
					"",  # speak text
					0.7,  # intensity
					"knife"  # item
				)
			else:
				print("Klaus or Maria not found")

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
