extends Control

@onready var start_btn = $CenterContainer/PanelContainer/VBoxContainer/StartButton
@onready var load_btn = $CenterContainer/PanelContainer/VBoxContainer/LoadButton
@onready var settings_btn = $CenterContainer/PanelContainer/VBoxContainer/SettingsButton
@onready var exit_btn = $CenterContainer/PanelContainer/VBoxContainer/ExitButton

func _ready():
	start_btn.pressed.connect(_on_start)
	load_btn.pressed.connect(_on_load)
	settings_btn.pressed.connect(_on_settings)
	exit_btn.pressed.connect(_on_exit)

func _on_start():
	get_tree().change_scene_to_file("res://scenes/ui/avatar.tscn")

func _on_load():
	print("Load Simulation pressed — no saved worlds yet")

func _on_settings():
	get_tree().change_scene_to_file("res://scenes/ui/settings.tscn")

func _on_exit():
	get_tree().quit()
