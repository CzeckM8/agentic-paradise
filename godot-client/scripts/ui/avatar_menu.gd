extends Control

const FRAME_SIZE := 16
const PREVIEW_SIZE := 96  # 16 * 6 = 96px preview

var sheet_order := ["generic", "bard", "soldier", "scout", "devout", "conjurer"]
var sheet_labels := {
	"generic": "Townfolk",
	"bard": "Bard",
	"soldier": "Soldier",
	"scout": "Scout",
	"devout": "Devout",
	"conjurer": "Conjurer",
}

var selected_key := "scout"
var _cell_panels := {}  # key -> PanelContainer

# Styles built in _ready
var _normal_style: StyleBoxFlat
var _selected_style: StyleBoxFlat

@onready var name_input = $CenterContainer/PanelContainer/VBoxContainer/NameInput
@onready var class_grid = $CenterContainer/PanelContainer/VBoxContainer/ClassGrid
@onready var back_btn = $CenterContainer/PanelContainer/VBoxContainer/ButtonsHBox/BackButton
@onready var next_btn = $CenterContainer/PanelContainer/VBoxContainer/ButtonsHBox/NextButton

func _ready():
	_normal_style = StyleBoxFlat.new()
	_normal_style.bg_color = Color(0, 0, 0, 0.4)
	_normal_style.set_border_width_all(2)
	_normal_style.border_color = Color(0.3, 1.0, 1.0, 0.3)
	_normal_style.set_corner_radius_all(10)
	_normal_style.content_margin_left = 10.0
	_normal_style.content_margin_top = 10.0
	_normal_style.content_margin_right = 10.0
	_normal_style.content_margin_bottom = 10.0

	_selected_style = StyleBoxFlat.new()
	_selected_style.bg_color = Color(0.02, 0.10, 0.14, 0.8)
	_selected_style.set_border_width_all(3)
	_selected_style.border_color = Color(1, 0.29, 0.84, 1)
	_selected_style.set_corner_radius_all(10)
	_selected_style.content_margin_left = 10.0
	_selected_style.content_margin_top = 10.0
	_selected_style.content_margin_right = 10.0
	_selected_style.content_margin_bottom = 10.0

	_build_sprite_grid()

	back_btn.pressed.connect(_on_back)
	next_btn.pressed.connect(_on_next)

	# Pre-fill from GameSession if returning to this screen
	if GameSession.player_name != "Player":
		name_input.text = GameSession.player_name
	selected_key = GameSession.selected_sprite_key
	_highlight(selected_key)

func _build_sprite_grid():
	class_grid.columns = 3
	class_grid.add_theme_constant_override("h_separation", 16)
	class_grid.add_theme_constant_override("v_separation", 16)

	for key in sheet_order:
		var path = GameSession.sprite_paths.get(key, "")
		var tex = load(path)
		if not tex:
			push_warning("Could not load sprite sheet: " + path)
			continue

		# Extract first frame as AtlasTexture
		var atlas = AtlasTexture.new()
		atlas.atlas = tex
		atlas.region = Rect2(0, 0, FRAME_SIZE, FRAME_SIZE)

		# Wrapper panel
		var panel = PanelContainer.new()
		panel.custom_minimum_size = Vector2(140, 140)
		panel.add_theme_stylebox_override("panel", _normal_style)
		panel.mouse_default_cursor_shape = Control.CURSOR_POINTING_HAND
		panel.gui_input.connect(_on_cell_input.bind(key))

		var vbox = VBoxContainer.new()
		vbox.alignment = BoxContainer.ALIGNMENT_CENTER
		vbox.add_theme_constant_override("separation", 8)
		panel.add_child(vbox)

		# Sprite preview
		var rect = TextureRect.new()
		rect.texture = atlas
		rect.custom_minimum_size = Vector2(PREVIEW_SIZE, PREVIEW_SIZE)
		rect.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		rect.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		rect.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
		rect.size_flags_horizontal = Control.SIZE_SHRINK_CENTER
		vbox.add_child(rect)

		# Label
		var label = Label.new()
		label.text = sheet_labels.get(key, key.capitalize())
		label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		label.add_theme_font_size_override("font_size", 16)
		label.add_theme_color_override("font_color", Color(0.875, 0.875, 0.875))
		vbox.add_child(label)

		class_grid.add_child(panel)
		_cell_panels[key] = panel

func _on_cell_input(event: InputEvent, key: String):
	if event is InputEventMouseButton and event.pressed:
		selected_key = key
		_highlight(key)

func _highlight(active_key: String):
	for k in _cell_panels:
		if k == active_key:
			_cell_panels[k].add_theme_stylebox_override("panel", _selected_style)
		else:
			_cell_panels[k].add_theme_stylebox_override("panel", _normal_style)

func _on_back():
	get_tree().change_scene_to_file("res://scenes/ui/mainMenu.tscn")

func _on_next():
	GameSession.player_name = name_input.text if name_input.text.strip_edges() != "" else "Player"
	GameSession.selected_sprite_key = selected_key
	get_tree().change_scene_to_file("res://scenes/ui/create.tscn")
