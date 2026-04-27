extends Node

# Player selections from Avatar screen
var player_name: String = "Player"
var selected_sprite_key: String = "scout"

# World selections from Create World screen
var world_name: String = "Paradise Prime"
var aggression_level: String = "medium"
var scenario_type: String = "everyday"
var pending_load_slot: String = ""

# Sprite key -> file path mapping (assets/sprites on this branch)
var sprite_paths := {
	"generic": "res://assets/sprites/generic_male.png",
	"bard": "res://assets/sprites/bard_male.png",
	"soldier": "res://assets/sprites/soldier_male.png",
	"scout": "res://assets/sprites/scout_male.png",
	"devout": "res://assets/sprites/devout_male.png",
	"conjurer": "res://assets/sprites/conjurer_male.png",
}

func get_player_sprite_path() -> String:
	return sprite_paths.get(selected_sprite_key, sprite_paths["scout"])
