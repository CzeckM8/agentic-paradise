extends Control

@onready var resume_btn = $"CenterContainer/PanelContainer/VBoxContainer/resume button"
@onready var save_btn = $"CenterContainer/PanelContainer/VBoxContainer/save button"
@onready var load_btn = $"CenterContainer/PanelContainer/VBoxContainer/load button"
@onready var settings_btn = $"CenterContainer/PanelContainer/VBoxContainer/settings button"
@onready var exit_btn = $"CenterContainer/PanelContainer/VBoxContainer/exit button"

func _ready():
	resume_btn.pressed.connect(_on_resume)
	save_btn.pressed.connect(_on_save)
	load_btn.pressed.connect(_on_load)
	settings_btn.pressed.connect(_on_settings)
	exit_btn.pressed.connect(_on_exit)

func _on_resume():
	get_tree().paused = false
	get_tree().change_scene_to_file("res://scenes/main.tscn")

func _on_save():
	print("Save pressed — not yet implemented")

func _on_load():
	print("Load pressed — not yet implemented")

func _on_settings():
	get_tree().change_scene_to_file("res://scenes/ui/settings.tscn")

func _on_exit():
	get_tree().paused = false
	get_tree().change_scene_to_file("res://scenes/ui/mainMenu.tscn")
