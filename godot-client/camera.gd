extends Camera2D

## Pivot inside the player scene (matches Sprite2D center offset in player.gd).
const VIEW_PIVOT := Vector2(16.0, 16.0)

var zoom_speed := 0.1
var pan_speed := 500.0

var min_pos := Vector2(0.0, 0.0)
var max_pos := Vector2(1800.0, 1200.0)
var manual_pan_offset := Vector2.ZERO
var manual_pan_decay := 3.0

var backend_connector: Node = null

func _find_backend_connector() -> Node:
	var n: Node = self
	while n != null:
		var bc := n.get_node_or_null("BackendConnector")
		if bc != null:
			return bc
		n = n.get_parent()
	return null

func _ready() -> void:
	zoom = Vector2(0.8, 0.8)
	enabled = true
	backend_connector = _find_backend_connector()
	if has_method("make_current"):
		make_current()
	set_process(true)

func _sanitize_bounds() -> void:
	if min_pos.x > max_pos.x:
		var t := min_pos.x
		min_pos.x = max_pos.x
		max_pos.x = t
	if min_pos.y > max_pos.y:
		var t2 := min_pos.y
		min_pos.y = max_pos.y
		max_pos.y = t2

func _refresh_bounds_from_backend() -> void:
	if backend_connector == null or not backend_connector.has_method("get_world_bounds"):
		return
	var b: Dictionary = backend_connector.get_world_bounds()
	min_pos = Vector2(float(b.get("minX", 0.0)), float(b.get("minY", 0.0)))
	max_pos = Vector2(float(b.get("maxX", 1800.0)), float(b.get("maxY", 1200.0)))

func _is_attached_to_player() -> bool:
	var p := get_parent()
	return p != null and str(p.name) == "Player"

func _process(delta: float) -> void:
	if not _is_attached_to_player():
		return

	_refresh_bounds_from_backend()
	_sanitize_bounds()

	var direction := Vector2.ZERO
	if Input.is_action_pressed("ui_right"):
		direction.x += 1.0
	if Input.is_action_pressed("ui_left"):
		direction.x -= 1.0
	if Input.is_action_pressed("ui_up"):
		direction.y -= 1.0
	if Input.is_action_pressed("ui_down"):
		direction.y += 1.0

	if direction != Vector2.ZERO:
		manual_pan_offset += direction.normalized() * pan_speed * delta / zoom.x
	else:
		manual_pan_offset = manual_pan_offset.lerp(Vector2.ZERO, manual_pan_decay * delta)

	position = VIEW_PIVOT + manual_pan_offset

	var gp := global_position
	var cx := clampf(gp.x, min_pos.x, max_pos.x)
	var cy := clampf(gp.y, min_pos.y, max_pos.y)
	if cx != gp.x or cy != gp.y:
		global_position = Vector2(cx, cy)

func _input(event: InputEvent) -> void:
	if event is InputEventMouseButton:
		if backend_connector != null and backend_connector.has_method("is_dialogue_open") and backend_connector.is_dialogue_open():
			return
		if event.button_index == MOUSE_BUTTON_WHEEL_UP:
			zoom += Vector2(zoom_speed, zoom_speed)
		elif event.button_index == MOUSE_BUTTON_WHEEL_DOWN:
			zoom -= Vector2(zoom_speed, zoom_speed)

	zoom.x = clampf(zoom.x, 0.3, 2.0)
	zoom.y = clampf(zoom.y, 0.3, 2.0)
