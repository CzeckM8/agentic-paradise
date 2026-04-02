extends Control

var _active_category := "controls"

# Sidebar buttons — paths match updated .tscn (Sidebar > SidebarVBox)
@onready var controls_btn = $CenterContainer/PanelContainer/HBoxContainer/Sidebar/SidebarVBox/ControlsButton
@onready var graphics_btn = $CenterContainer/PanelContainer/HBoxContainer/Sidebar/SidebarVBox/GraphicsButton
@onready var audio_btn = $CenterContainer/PanelContainer/HBoxContainer/Sidebar/SidebarVBox/AudioButton
@onready var prefs_btn = $CenterContainer/PanelContainer/HBoxContainer/Sidebar/SidebarVBox/PreferencesButton
@onready var back_btn = $CenterContainer/PanelContainer/HBoxContainer/Sidebar/SidebarVBox/BackButton

# Content panels
@onready var controls_panel = $CenterContainer/PanelContainer/HBoxContainer/Content/ControlsPanel
@onready var graphics_panel = $CenterContainer/PanelContainer/HBoxContainer/Content/GraphicsPanel
@onready var audio_panel = $CenterContainer/PanelContainer/HBoxContainer/Content/AudioPanel
@onready var prefs_panel = $CenterContainer/PanelContainer/HBoxContainer/Content/PreferencesPanel

# Close button — now inside Content
@onready var close_btn = $CenterContainer/PanelContainer/HBoxContainer/Content/CloseButton

# Sidebar selected style
var _sidebar_selected: StyleBoxFlat
var _sidebar_normal: StyleBoxFlat
var _sidebar_buttons := []

func _ready():
	_sidebar_normal = StyleBoxFlat.new()
	_sidebar_normal.bg_color = Color(0.6, 0.6, 0.6, 0.035)
	_sidebar_normal.set_border_width_all(0)
	_sidebar_normal.set_corner_radius_all(8)
	_sidebar_normal.content_margin_left = 14.0
	_sidebar_normal.content_margin_top = 8.0
	_sidebar_normal.content_margin_right = 14.0
	_sidebar_normal.content_margin_bottom = 8.0

	_sidebar_selected = StyleBoxFlat.new()
	_sidebar_selected.bg_color = Color(0.02, 0.10, 0.14, 0.8)
	_sidebar_selected.set_border_width_all(2)
	_sidebar_selected.border_color = Color(1, 0.29, 0.84, 1)
	_sidebar_selected.set_corner_radius_all(8)
	_sidebar_selected.content_margin_left = 14.0
	_sidebar_selected.content_margin_top = 8.0
	_sidebar_selected.content_margin_right = 14.0
	_sidebar_selected.content_margin_bottom = 8.0

	_sidebar_buttons = [controls_btn, graphics_btn, audio_btn, prefs_btn]

	controls_btn.pressed.connect(_show_category.bind("controls"))
	graphics_btn.pressed.connect(_show_category.bind("graphics"))
	audio_btn.pressed.connect(_show_category.bind("audio"))
	prefs_btn.pressed.connect(_show_category.bind("preferences"))
	back_btn.pressed.connect(_on_back)
	close_btn.pressed.connect(_on_back)

	_show_category("controls")

func _show_category(category: String):
	_active_category = category

	controls_panel.visible = category == "controls"
	graphics_panel.visible = category == "graphics"
	audio_panel.visible = category == "audio"
	prefs_panel.visible = category == "preferences"

	var btn_map := {
		"controls": controls_btn,
		"graphics": graphics_btn,
		"audio": audio_btn,
		"preferences": prefs_btn,
	}
	for btn in _sidebar_buttons:
		if btn == btn_map.get(category):
			btn.add_theme_stylebox_override("normal", _sidebar_selected)
		else:
			btn.add_theme_stylebox_override("normal", _sidebar_normal)

func _on_back():
	get_tree().change_scene_to_file("res://scenes/ui/mainMenu.tscn")
