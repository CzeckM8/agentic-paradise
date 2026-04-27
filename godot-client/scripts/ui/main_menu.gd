extends Control

@onready var start_btn = $CenterContainer/PanelContainer/VBoxContainer/StartButton
@onready var load_btn = $CenterContainer/PanelContainer/VBoxContainer/LoadButton
@onready var settings_btn = $CenterContainer/PanelContainer/VBoxContainer/SettingsButton
@onready var exit_btn = $CenterContainer/PanelContainer/VBoxContainer/ExitButton
@onready var menu_box = $CenterContainer/PanelContainer/VBoxContainer

var reset_btn: Button = null
var reset_confirmation_panel: Panel = null
var status_label: Label = null
var load_panel: Panel = null
var load_status: Label = null
var save_slots: Dictionary = {}
var selected_slot_id = "slot-1"
var slot_buttons: Dictionary = {}
var backend_url = "http://localhost:8080"
var load_panel_height = 420.0
var load_panel_min_width = 560.0
var load_panel_max_width = 980.0

func _ready():
	start_btn.pressed.connect(_on_start)
	load_btn.pressed.connect(_on_load)
	settings_btn.pressed.connect(_on_settings)
	exit_btn.pressed.connect(_on_exit)
	_build_reset_button()
	_build_reset_confirmation_panel()
	_build_status_label()
	_build_load_panel()
	_refresh_world_menu_state()

func _make_panel_style() -> StyleBoxFlat:
	var style = StyleBoxFlat.new()
	style.content_margin_left = 26.0
	style.content_margin_top = 26.0
	style.content_margin_right = 26.0
	style.content_margin_bottom = 26.0
	style.bg_color = Color(0, 0, 0, 1)
	style.border_width_left = 4
	style.border_width_top = 4
	style.border_width_right = 4
	style.border_width_bottom = 4
	style.border_color = Color(0.16470589, 0.9098039, 0.9411765, 1)
	style.corner_radius_top_left = 26
	style.corner_radius_top_right = 26
	style.corner_radius_bottom_right = 26
	style.corner_radius_bottom_left = 26
	return style

func _make_button_style(bg_color: Color, border_color: Color = Color(0.3019608, 1, 1, 1)) -> StyleBoxFlat:
	var style = StyleBoxFlat.new()
	style.content_margin_left = 18.0
	style.content_margin_top = 10.0
	style.content_margin_right = 18.0
	style.content_margin_bottom = 10.0
	style.bg_color = bg_color
	style.border_width_left = 3
	style.border_width_top = 3
	style.border_width_right = 3
	style.border_width_bottom = 3
	style.border_color = border_color
	style.corner_radius_top_left = 12
	style.corner_radius_top_right = 12
	style.corner_radius_bottom_right = 12
	style.corner_radius_bottom_left = 12
	return style

func _style_menu_button(button: Button, font_size: int = 30, min_height: float = 70.0):
	button.custom_minimum_size = Vector2(1, min_height)
	button.add_theme_color_override("font_color", Color(0.3019608, 1, 1, 1))
	button.add_theme_color_override("font_disabled_color", Color(0.3019608, 1, 1, 0.35))
	button.add_theme_font_size_override("font_size", font_size)
	button.add_theme_stylebox_override("normal", _make_button_style(Color(0.6, 0.6, 0.6, 0.03529412)))
	button.add_theme_stylebox_override("pressed", _make_button_style(Color(0.019607844, 0.101960786, 0.14117648, 0.8)))
	button.add_theme_stylebox_override("hover", _make_button_style(Color(0.039215688, 0.18039216, 0.21960784, 0.6509804), Color(0.54901963, 1, 1, 1)))
	button.add_theme_stylebox_override("focus", _make_button_style(Color(0.039215688, 0.18039216, 0.21960784, 0.6509804), Color(0.54901963, 1, 1, 1)))

func _style_body_label(label: Label, font_size: int = 16):
	label.add_theme_color_override("font_color", Color(0.3019608, 1, 1, 0.9))
	label.add_theme_font_size_override("font_size", font_size)

func _style_panel_title(label: Label, font_size: int = 30):
	label.add_theme_color_override("font_color", Color(1, 0.24705882, 0.1882353, 1))
	label.add_theme_color_override("font_outline_color", Color(0.91764706, 0, 0.7607843, 1))
	label.add_theme_constant_override("outline_size", 2)
	label.add_theme_font_size_override("font_size", font_size)

func _set_centered_panel_size(panel: Control, width: float, height: float):
	var half_width = width * 0.5
	var half_height = height * 0.5
	panel.offset_left = -half_width
	panel.offset_top = -half_height
	panel.offset_right = half_width
	panel.offset_bottom = half_height

func _build_reset_button():
	reset_btn = Button.new()
	reset_btn.text = "Reset World"
	_style_menu_button(reset_btn)
	reset_btn.pressed.connect(_on_reset_world)
	menu_box.add_child(reset_btn)
	menu_box.move_child(reset_btn, menu_box.get_children().find(settings_btn))

func _build_reset_confirmation_panel():
	reset_confirmation_panel = Panel.new()
	reset_confirmation_panel.name = "ResetConfirmationPanel"
	reset_confirmation_panel.visible = false
	reset_confirmation_panel.anchor_left = 0.5
	reset_confirmation_panel.anchor_top = 0.5
	reset_confirmation_panel.anchor_right = 0.5
	reset_confirmation_panel.anchor_bottom = 0.5
	reset_confirmation_panel.offset_left = -250
	reset_confirmation_panel.offset_top = -145
	reset_confirmation_panel.offset_right = 250
	reset_confirmation_panel.offset_bottom = 145
	reset_confirmation_panel.add_theme_stylebox_override("panel", _make_panel_style())
	add_child(reset_confirmation_panel)

	var margin = MarginContainer.new()
	margin.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	margin.add_theme_constant_override("margin_left", 22)
	margin.add_theme_constant_override("margin_right", 22)
	margin.add_theme_constant_override("margin_top", 22)
	margin.add_theme_constant_override("margin_bottom", 22)
	reset_confirmation_panel.add_child(margin)

	var vbox = VBoxContainer.new()
	vbox.add_theme_constant_override("separation", 16)
	margin.add_child(vbox)

	var title = Label.new()
	title.text = "Reset World"
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_style_panel_title(title, 32)
	vbox.add_child(title)

	var message = Label.new()
	message.text = "Are you sure?"
	message.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_style_body_label(message, 24)
	vbox.add_child(message)

	var row = HBoxContainer.new()
	row.add_theme_constant_override("separation", 10)
	vbox.add_child(row)

	var confirm_btn = Button.new()
	confirm_btn.text = "Yes"
	confirm_btn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_style_menu_button(confirm_btn, 24, 56)
	confirm_btn.pressed.connect(_confirm_reset_world)
	row.add_child(confirm_btn)

	var cancel_btn = Button.new()
	cancel_btn.text = "Cancel"
	cancel_btn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_style_menu_button(cancel_btn, 24, 56)
	cancel_btn.pressed.connect(func(): reset_confirmation_panel.visible = false)
	row.add_child(cancel_btn)

func _build_status_label():
	status_label = Label.new()
	status_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	status_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_style_body_label(status_label, 16)
	menu_box.add_child(status_label)
	menu_box.move_child(status_label, menu_box.get_child_count() - 2)

func _build_load_panel():
	load_panel = Panel.new()
	load_panel.visible = false
	load_panel.anchor_left = 0.5
	load_panel.anchor_top = 0.5
	load_panel.anchor_right = 0.5
	load_panel.anchor_bottom = 0.5
	_set_centered_panel_size(load_panel, load_panel_min_width, load_panel_height)
	load_panel.add_theme_stylebox_override("panel", _make_panel_style())
	add_child(load_panel)

	var margin = MarginContainer.new()
	margin.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	margin.add_theme_constant_override("margin_left", 18)
	margin.add_theme_constant_override("margin_right", 18)
	margin.add_theme_constant_override("margin_top", 18)
	margin.add_theme_constant_override("margin_bottom", 18)
	load_panel.add_child(margin)

	var vbox = VBoxContainer.new()
	vbox.add_theme_constant_override("separation", 10)
	margin.add_child(vbox)

	var title = Label.new()
	title.text = "Load World"
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_style_panel_title(title, 30)
	vbox.add_child(title)

	for slot_id in ["slot-1", "slot-2", "slot-3"]:
		var button = Button.new()
		button.text = slot_id
		_style_menu_button(button, 20, 68)
		button.pressed.connect(_select_slot.bind(slot_id))
		vbox.add_child(button)
		slot_buttons[slot_id] = button

	var row = HBoxContainer.new()
	row.add_theme_constant_override("separation", 8)
	vbox.add_child(row)

	var load_selected_btn = Button.new()
	load_selected_btn.text = "Load Selected"
	load_selected_btn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_style_menu_button(load_selected_btn, 20, 52)
	load_selected_btn.pressed.connect(_load_selected_slot)
	row.add_child(load_selected_btn)

	var close_btn = Button.new()
	close_btn.text = "Close"
	close_btn.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_style_menu_button(close_btn, 20, 52)
	close_btn.pressed.connect(func(): load_panel.visible = false)
	row.add_child(close_btn)

	load_status = Label.new()
	load_status.text = "Select a save slot."
	load_status.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_style_body_label(load_status, 16)
	vbox.add_child(load_status)

func _refresh_world_menu_state():
	save_slots = _read_save_slots()
	var has_world = false
	for slot in save_slots.values():
		if not bool(slot.get("empty", true)):
			has_world = true
			break
	start_btn.disabled = has_world
	load_btn.disabled = not has_world
	reset_btn.visible = has_world
	if has_world:
		start_btn.text = "Start Simulation"
		status_label.text = "A saved world exists. Load a save or reset the world before creating a new one."
	else:
		start_btn.text = "Start Simulation"
		status_label.text = "No saved world found. Start a new simulation."
	_render_load_slots()

func _read_save_slots() -> Dictionary:
	var result = {}
	for slot_id in ["slot-1", "slot-2", "slot-3"]:
		var metadata_path = _save_root_path().path_join(slot_id).path_join("metadata.json")
		var slot = {"slotId": slot_id, "displayName": _slot_display_name(slot_id), "empty": true}
		if FileAccess.file_exists(metadata_path):
			var file = FileAccess.open(metadata_path, FileAccess.READ)
			if file != null:
				var json = JSON.new()
				if json.parse(file.get_as_text()) == OK and json.data is Dictionary:
					slot = json.data
					slot["empty"] = false
				file.close()
		result[slot_id] = slot
	return result

func _save_root_path() -> String:
	return ProjectSettings.globalize_path("res://../smallville/saves")

func _slot_display_name(slot_id: String) -> String:
	match slot_id:
		"slot-1":
			return "Slot 1"
		"slot-2":
			return "Slot 2"
		"slot-3":
			return "Slot 3"
		_:
			return slot_id

func _render_load_slots():
	var longest_text = ""
	for slot_id in slot_buttons.keys():
		var button = slot_buttons[slot_id]
		var slot = save_slots.get(slot_id, {})
		var selected_prefix = "> " if selected_slot_id == slot_id else ""
		if bool(slot.get("empty", true)):
			button.text = selected_prefix + _slot_display_name(slot_id) + "\nEmpty"
			button.disabled = true
		else:
			var location = str(slot.get("playerLocation", "Unknown"))
			var time = str(slot.get("simulationTime", "--:--"))
			var saved_at = str(slot.get("savedAt", ""))
			button.text = selected_prefix + str(slot.get("displayName", _slot_display_name(slot_id))) + "\n" + location + " | " + time + " | " + saved_at
			button.disabled = false
		if button.text.length() > longest_text.length():
			longest_text = button.text
	_resize_load_panel_for_slot_text(longest_text)

func _resize_load_panel_for_slot_text(longest_text: String):
	if load_panel == null:
		return
	var font = load_panel.get_theme_default_font()
	var font_size = 20
	var longest_line = ""
	for line in longest_text.split("\n"):
		if line.length() > longest_line.length():
			longest_line = line
	var text_width = font.get_string_size(longest_line, HORIZONTAL_ALIGNMENT_LEFT, -1, font_size).x if font != null else longest_line.length() * 12.0
	var desired_width = clamp(text_width + 140.0, load_panel_min_width, load_panel_max_width)
	_set_centered_panel_size(load_panel, desired_width, load_panel_height)

func _select_slot(slot_id: String):
	selected_slot_id = slot_id
	_render_load_slots()
	load_status.text = "Selected " + _slot_display_name(slot_id) + "."

func _on_start():
	GameSession.pending_load_slot = ""
	get_tree().change_scene_to_file("res://scenes/ui/avatar.tscn")

func _on_load():
	_refresh_world_menu_state()
	if load_btn.disabled:
		status_label.text = "No saved world found."
		return
	for slot_id in ["slot-1", "slot-2", "slot-3"]:
		if not bool(save_slots.get(slot_id, {}).get("empty", true)):
			selected_slot_id = slot_id
			break
	_render_load_slots()
	load_panel.visible = true

func _load_selected_slot():
	var slot = save_slots.get(selected_slot_id, {})
	if bool(slot.get("empty", true)):
		load_status.text = "That slot is empty."
		return
	var player_name = str(slot.get("playerName", "")).strip_edges()
	if player_name != "":
		GameSession.player_name = player_name
	GameSession.pending_load_slot = selected_slot_id
	get_tree().change_scene_to_file("res://main.tscn")

func _on_reset_world():
	if reset_confirmation_panel != null:
		reset_confirmation_panel.visible = true
		return

	await _confirm_reset_world()

func _confirm_reset_world():
	if reset_confirmation_panel != null:
		reset_confirmation_panel.visible = false
	_delete_save_directory(_save_root_path())
	await _post_backend_reset_if_available()
	GameSession.pending_load_slot = ""
	_refresh_world_menu_state()

func _post_backend_reset_if_available():
	var http = HTTPRequest.new()
	http.timeout = 2.0
	add_child(http)
	var err = http.request(backend_url + "/world/reset", ["Content-Type: application/json"], HTTPClient.METHOD_POST, "{}")
	if err != OK:
		http.queue_free()
		return
	await http.request_completed
	http.queue_free()

func _delete_save_directory(path: String):
	var dir = DirAccess.open(path)
	if dir == null:
		return
	dir.list_dir_begin()
	var entry = dir.get_next()
	while entry != "":
		if entry != "." and entry != "..":
			var child_path = path.path_join(entry)
			if dir.current_is_dir():
				_delete_save_directory(child_path)
				DirAccess.remove_absolute(child_path)
			else:
				DirAccess.remove_absolute(child_path)
		entry = dir.get_next()
	dir.list_dir_end()

func _on_settings():
	get_tree().change_scene_to_file("res://scenes/ui/settings.tscn")

func _on_exit():
	get_tree().quit()
