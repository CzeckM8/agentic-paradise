extends Control

var aggression_level := "medium"
var scenario_type := "everyday"

var _aggression_buttons := []
var _scenario_buttons := []
var _selected_style: StyleBoxFlat

@onready var name_input = $"CenterContainer/PanelContainer/VBoxContainer/world name label"
@onready var low_btn = $"CenterContainer/PanelContainer/VBoxContainer/HBoxContainer/low button"
@onready var medium_btn = $"CenterContainer/PanelContainer/VBoxContainer/HBoxContainer/medium button"
@onready var high_btn = $"CenterContainer/PanelContainer/VBoxContainer/HBoxContainer/high button"
@onready var everyday_btn = $"CenterContainer/PanelContainer/VBoxContainer/HBoxContainer2/Everyday button"
@onready var stress_btn = $"CenterContainer/PanelContainer/VBoxContainer/HBoxContainer2/Stress button"
@onready var generate_btn = $"CenterContainer/PanelContainer/VBoxContainer/generate button"
@onready var back_btn = $"CenterContainer/PanelContainer/VBoxContainer/back button"

func _ready():
	_selected_style = StyleBoxFlat.new()
	_selected_style.bg_color = Color(0.02, 0.10, 0.14, 0.8)
	_selected_style.border_color = Color(1, 0.29, 0.84, 1)
	_selected_style.set_border_width_all(3)
	_selected_style.set_corner_radius_all(12)
	_selected_style.content_margin_left = 18.0
	_selected_style.content_margin_top = 10.0
	_selected_style.content_margin_right = 18.0
	_selected_style.content_margin_bottom = 10.0

	_aggression_buttons = [low_btn, medium_btn, high_btn]
	_scenario_buttons = [everyday_btn, stress_btn]

	low_btn.pressed.connect(_on_aggression.bind("low", low_btn))
	medium_btn.pressed.connect(_on_aggression.bind("medium", medium_btn))
	high_btn.pressed.connect(_on_aggression.bind("high", high_btn))
	everyday_btn.pressed.connect(_on_scenario.bind("everyday", everyday_btn))
	stress_btn.pressed.connect(_on_scenario.bind("stress", stress_btn))
	generate_btn.pressed.connect(_on_generate)
	back_btn.pressed.connect(_on_back)

	# Pre-fill from GameSession
	name_input.text = GameSession.world_name

	# Set defaults
	_select_button(medium_btn, _aggression_buttons)
	_select_button(everyday_btn, _scenario_buttons)

func _on_aggression(level: String, btn: Button):
	aggression_level = level
	_select_button(btn, _aggression_buttons)

func _on_scenario(scenario: String, btn: Button):
	scenario_type = scenario
	_select_button(btn, _scenario_buttons)

func _select_button(active: Button, group: Array):
	for btn in group:
		if btn == active:
			btn.add_theme_stylebox_override("normal", _selected_style)
		else:
			btn.remove_theme_stylebox_override("normal")

func _on_generate():
	GameSession.world_name = name_input.text if name_input.text.strip_edges() != "" else "Paradise Prime"
	GameSession.aggression_level = aggression_level
	GameSession.scenario_type = scenario_type
	print("Launching simulation: world=%s, aggression=%s, scenario=%s" % [
		GameSession.world_name, GameSession.aggression_level, GameSession.scenario_type])
	get_tree().change_scene_to_file("res://scenes/main.tscn")

func _on_back():
	get_tree().change_scene_to_file("res://scenes/ui/avatar.tscn")
