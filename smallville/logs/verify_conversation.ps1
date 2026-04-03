$base='http://localhost:8080'

function Try-Post($uri,$body){
  try {
    Invoke-RestMethod -Uri $uri -Method POST -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 8) | Out-Null
    return 'ok'
  } catch {
    return 'err'
  }
}

# Ensure minimal world exists for deterministic verification.
$locRes = Try-Post "$base/locations" @{name='home';type='residential';minX=0;minY=0;maxX=320;maxY=320}
$playerRes = Try-Post "$base/player" @{name='Player';location='home';activity='idle';memories=@('verification run')}
$agentRes = Try-Post "$base/agents" @{name='Verifier NPC';location='home';activity='idle';memories=@('verification run')}
Write-Output ("VERIFY:SETUP location={0} player={1} npc={2}" -f $locRes,$playerRes,$agentRes)

$state=Invoke-RestMethod -Uri "$base/state" -Method GET
$player=$state.agents | Where-Object { $_.name -eq 'Player' } | Select-Object -First 1
$npc=$state.agents | Where-Object { $_.name -eq 'Verifier NPC' } | Select-Object -First 1
if(-not $player -or -not $npc){ Write-Output 'VERIFY:FAIL setup-missing-entities'; exit 1 }

$dist=[Math]::Sqrt([Math]::Pow(($npc.x - $player.x),2)+[Math]::Pow(($npc.y - $player.y),2))
Write-Output ("VERIFY:DIST initial={0:N1}" -f $dist)

for($i=1; $i -le 12 -and $dist -gt 96; $i++){
  $move=@{playerId='Player';actionType='move';targetAgent='';targetLocation='home';playerX=[double]$npc.x;playerY=[double]$npc.y;actionDescription='Moving toward Verifier NPC'}
  Invoke-RestMethod -Uri "$base/player/actions" -Method POST -ContentType 'application/json' -Body ($move|ConvertTo-Json -Depth 6) | Out-Null
  Invoke-RestMethod -Uri "$base/turn" -Method POST -ContentType 'application/json' -Body (@{awarenessRadius=20;forceDayStart=$false}|ConvertTo-Json -Depth 6) | Out-Null
  $state=Invoke-RestMethod -Uri "$base/state" -Method GET
  $player=$state.agents | Where-Object { $_.name -eq 'Player' } | Select-Object -First 1
  $npc=$state.agents | Where-Object { $_.name -eq 'Verifier NPC' } | Select-Object -First 1
  $dist=[Math]::Sqrt([Math]::Pow(($npc.x - $player.x),2)+[Math]::Pow(($npc.y - $player.y),2))
}
Write-Output ("VERIFY:DIST post-move={0:N1}" -f $dist)

$msg='Hey there, this is a verification ping.'
$speak=@{playerId='Player';actionType='speak';targetAgent='Verifier NPC';targetLocation='';playerX=[double]$player.x;playerY=[double]$player.y;actionDescription='Speaking with Verifier NPC';speakText=$msg}
Invoke-RestMethod -Uri "$base/player/actions" -Method POST -ContentType 'application/json' -Body ($speak|ConvertTo-Json -Depth 6) | Out-Null
$turn=Invoke-RestMethod -Uri "$base/turn" -Method POST -ContentType 'application/json' -Body (@{awarenessRadius=20;forceDayStart=$false;pinnedAgents=@('Verifier NPC')}|ConvertTo-Json -Depth 6)

Write-Output ("VERIFY:SPEAK success={0}" -f $turn.actionResult.success)
Write-Output ("VERIFY:REPLY speaker={0} text={1}" -f $turn.actionResult.agentReplySpeaker,$turn.actionResult.agentReplyText)

$beforeState=Invoke-RestMethod -Uri "$base/state" -Method GET
$before=@($beforeState.conversations).Count
1..4 | ForEach-Object {
  Invoke-RestMethod -Uri "$base/turn" -Method POST -ContentType 'application/json' -Body (@{awarenessRadius=20;forceDayStart=$false}|ConvertTo-Json -Depth 6) | Out-Null
}
$afterState=Invoke-RestMethod -Uri "$base/state" -Method GET
$after=@($afterState.conversations).Count
Write-Output ("VERIFY:CONVO-COUNT before={0} after={1}" -f $before,$after)
if($after -gt 0){
  $last=@($afterState.conversations)[-1]
  Write-Output ("VERIFY:LAST name={0} msg={1}" -f $last.name,$last.message)
}
