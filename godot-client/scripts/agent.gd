extends Node2D

@onready var sprite = $Sprite2D
@onready var label = $Label

var agent_name = ""
var current_activity = ""
var current_location = ""

# For smooth movement
var target_position = Vector2.ZERO
var move_speed = 200.0  # pixels per second
var has_initial_position = false

# ── Sprite sheet configuration ──────────────────────────────────────
# Each character in the sprite pack is 16x16 pixels per frame,
# arranged as 6 frames across (walk cycle) × 4 rows (directions).
# Class sheets (soldier, bard, etc.) contain 4 color variants each,
# laid out in a 2×2 grid of 6×4 blocks.
const FRAME_SIZE := 16
const FRAMES_PER_DIRECTION := 6    # columns per character variant
const DIRECTIONS_PER_VARIANT := 4  # rows per character variant

# Sprite scale — 16px is tiny, so we scale up for visibility.
const SPRITE_SCALE := 3.0

# Direction mapping: row index within a character's 4-row block.
enum Dir { DOWN = 0, LEFT = 1, RIGHT = 2, UP = 3 }

# Map activity keywords → sprite sheet paths.
# Hostile agents get soldier sprites, friendlier ones get generic/bard, etc.
# You can expand this or reassign however you like.
var sprite_sheets := {
	"soldier":   "res://assets/sprites/agents/03-soldier.png",
	"generic":   "res://assets/sprites/agents/01-generic.png",
	"bard":      "res://assets/sprites/agents/02-bard.png",
	"scout":     "res://assets/sprites/agents/04-scout.png",
	"devout":    "res://assets/sprites/agents/05-devout.png",
	"conjurer":  "res://assets/sprites/agents/06-conjurer.png",
}

# Ordered list of sheet keys used when auto-assigning by name hash.
var sheet_keys := ["generic", "bard", "soldier", "scout", "devout", "conjurer"]

# Per-agent state
var _sheet_key := "generic"
var _variant_index := 0   # which color variant (0–3 for class sheets, 0–? for generic)
var _current_dir := Dir.DOWN
var _anim_frame := 0
var _anim_timer := 0.0
var _anim_speed := 0.18   # seconds per frame
var _is_animating := false
var _sheet_cols := 12      # total columns in loaded sheet
var _sheet_rows := 8       # total rows in loaded sheet
var _variants_per_row := 2 # how many character blocks per row of the sheet
var _previous_position := Vector2.ZERO

func _ready():
	# Set up sprite for region-based rendering from a sprite sheet.
	sprite.region_enabled = true
	sprite.scale = Vector2(SPRITE_SCALE, SPRITE_SCALE)
	# Pixel-perfect rendering (no blur on scale).
	sprite.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST

	label.position = Vector2(-40, -40)
	label.add_theme_font_size_override("font_size", 12)
	target_position = position
	_previous_position = position

	# Load a default sheet so the agent isn't invisible before first backend sync.
	_apply_sprite_sheet("generic")
	_update_region()

func _process(delta):
	# ── Smooth movement toward target ──
	if position.distance_to(target_position) > 5:
		var old_pos = position
		position = position.move_toward(target_position, move_speed * delta)
		_is_animating = true
		_update_direction_from_movement(position - old_pos)
	else:
		_is_animating = false

	# ── Walk animation ──
	if _is_animating:
		_anim_timer += delta
		if _anim_timer >= _anim_speed:
			_anim_timer = 0.0
			_anim_frame = (_anim_frame + 1) % FRAMES_PER_DIRECTION
			_update_region()
	else:
		# Idle: show frame 0
		if _anim_frame != 0:
			_anim_frame = 0
			_update_region()

func update_from_backend(data, location_map, preset_position: Vector2 = Vector2.ZERO):
	"""Update agent based on backend data."""
	agent_name = data.get("name", "unknown")
	current_activity = data.get("activity", data.get("action", data.get("currentAction", "idle")))
	current_location = data.get("location", "unknown")

	# ── Assign a sprite sheet + variant based on agent name ──
	# This gives each agent a deterministic, consistent appearance
	# derived from their name, so they always look the same across sessions.
	_assign_appearance_from_name(agent_name)

	# ── Resolve position ──
	if data.has("x") and data.has("y"):
		target_position = Vector2(float(data.get("x", 0.0)), float(data.get("y", 0.0)))
	elif preset_position != Vector2.ZERO:
		target_position = preset_position
	elif location_map.has(current_location):
		var location_data = location_map[current_location]
		if location_data is Dictionary:
			var center_x = location_data.get("centerX", 50.0)
			var center_y = location_data.get("centerY", 50.0)
			target_position = Vector2(center_x, center_y)
		else:
			target_position = location_data as Vector2

	_update_label()
	_update_activity_tint()

	# On first backend sync, spawn exactly at resolved coordinates (no fly-in).
	if not has_initial_position:
		position = target_position
		_previous_position = position
		has_initial_position = true

# ── Sprite sheet helpers ────────────────────────────────────────────

func _assign_appearance_from_name(aname: String):
	"""Deterministically pick a sheet + color variant from agent name."""
	var h = aname.hash()
	# Pick which sprite sheet
	var key_index = abs(h) % sheet_keys.size()
	var key = sheet_keys[key_index]

	if key != _sheet_key:
		_apply_sprite_sheet(key)

	# Pick which color variant within that sheet
	var total_variants = _get_total_variants()
	_variant_index = abs(h >> 8) % total_variants
	_update_region()

func _apply_sprite_sheet(key: String):
	"""Load a sprite sheet texture by key."""
	_sheet_key = key
	var path = sprite_sheets.get(key, sprite_sheets["generic"])
	var tex = load(path)
	if tex:
		sprite.texture = tex
		_sheet_cols = int(tex.get_width()) / FRAME_SIZE
		_sheet_rows = int(tex.get_height()) / FRAME_SIZE
		_variants_per_row = _sheet_cols / FRAMES_PER_DIRECTION
	else:
		push_warning("Failed to load sprite sheet: " + path)

func _get_total_variants() -> int:
	"""Return how many distinct character variants are in the current sheet."""
	var chars_wide = _sheet_cols / FRAMES_PER_DIRECTION
	var chars_tall = _sheet_rows / DIRECTIONS_PER_VARIANT
	return chars_wide * chars_tall

func _update_region():
	"""Set the Sprite2D region rect to show the correct frame."""
	# Which character block (grid of 6×4) does this variant occupy?
	var chars_wide = _sheet_cols / FRAMES_PER_DIRECTION
	var block_col = _variant_index % chars_wide   # 0 or 1 (for class sheets)
	var block_row = _variant_index / chars_wide    # 0 or 1

	# Pixel offset to the top-left of this character's block
	var block_x = block_col * FRAMES_PER_DIRECTION * FRAME_SIZE
	var block_y = block_row * DIRECTIONS_PER_VARIANT * FRAME_SIZE

	# Within the block: column = animation frame, row = direction
	var px_x = block_x + _anim_frame * FRAME_SIZE
	var px_y = block_y + _current_dir * FRAME_SIZE

	sprite.region_rect = Rect2(px_x, px_y, FRAME_SIZE, FRAME_SIZE)

func _update_direction_from_movement(delta_vec: Vector2):
	"""Set facing direction based on movement vector."""
	if delta_vec.length_squared() < 0.01:
		return
	# Pick dominant axis
	if abs(delta_vec.x) > abs(delta_vec.y):
		_current_dir = Dir.RIGHT if delta_vec.x > 0 else Dir.LEFT
	else:
		_current_dir = Dir.DOWN if delta_vec.y > 0 else Dir.UP
	_update_region()

func _update_activity_tint():
	"""Apply a subtle color tint based on activity keywords.
	   Unlike the old placeholder, we use softer tints so the sprite
	   art remains visible underneath."""
	var activity_lower = current_activity.to_lower()

	if "fight" in activity_lower or "attack" in activity_lower or "combat" in activity_lower:
		sprite.modulate = Color(1.0, 0.6, 0.6)  # soft red
	elif "talk" in activity_lower or "conversation" in activity_lower or "speaking" in activity_lower:
		sprite.modulate = Color(1.0, 1.0, 0.7)  # soft yellow
	elif "walk" in activity_lower or "moving" in activity_lower or "going" in activity_lower:
		sprite.modulate = Color.WHITE            # natural colors while walking
	elif "idle" in activity_lower or "waiting" in activity_lower or "standing" in activity_lower:
		sprite.modulate = Color(0.85, 0.85, 0.85)  # slightly dimmed
	else:
		sprite.modulate = Color.WHITE

func _update_label():
	"""Update the text label above the agent."""
	var display_text = agent_name
	var activity_display = current_activity
	if activity_display.length() > 30:
		activity_display = activity_display.substr(0, 27) + "..."
	label.text = "%s\n%s\n@ %s" % [agent_name, activity_display, current_location]

func get_agent_info():
	"""Return agent info for inspection/debugging."""
	return {
		"name": agent_name,
		"activity": current_activity,
		"location": current_location,
		"position": position,
		"sprite_sheet": _sheet_key,
		"variant": _variant_index
	}
