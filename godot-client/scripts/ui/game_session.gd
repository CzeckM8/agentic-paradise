extends Node

# Player selections from Avatar screen
var player_name: String = "Player"
var selected_sprite_key: String = "scout"

# World selections from Create World screen
var world_name: String = "Paradise Prime"
var aggression_level: String = "medium"
var scenario_type: String = "everyday"

# Sprite key -> file path mapping
var sprite_paths := {
	"generic": "res://assets/sprites/agents/01-generic.png",
	"bard": "res://assets/sprites/agents/02-bard.png",
	"soldier": "res://assets/sprites/agents/03-soldier.png",
	"scout": "res://assets/sprites/agents/04-scout.png",
	"devout": "res://assets/sprites/agents/05-devout.png",
	"conjurer": "res://assets/sprites/agents/06-conjurer.png",
}

func get_player_sprite_path() -> String:
	return sprite_paths.get(selected_sprite_key, sprite_paths["scout"])
