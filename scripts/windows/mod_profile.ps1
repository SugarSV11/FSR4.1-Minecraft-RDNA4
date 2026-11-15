# Switch the test client's mods\ folder between staged, reversible mod profiles.
# Each stage adds one group so a regression (crash, FPS drop, visual break) points at that group.
#   baseline : Aetherium 0.1.3 + Sodium 0.9.2 (the setup every earlier benchmark used)
#   s1       : Sodium -> 0.9.3-beta.5 only (required by MoreCulling; isolates the Sodium upgrade)
#   s2       : s1 + client render mods (ImmediatelyFast, BadOptimizations, EntityCulling, MoreCulling, Dynamic FPS)
#   s3       : s2 + memory / CPU / integrated-server mods (FerriteCore, Lithium, C2ME, ServerCore, Clumps, Alternate Current)
#   restore  : put back the jars saved by the last switch
# The previous jars are MOVED (never deleted) to run\mods_backup\<timestamp>\.
# fsr4linux itself is not a jar here: the Loom dev client loads it from the source sets. Fabric API comes from Gradle (0.162.0), never copy it in.
param([Parameter(Mandatory)][ValidateSet("baseline","s1","s2","s3","restore")][string]$Stage, [switch]$Extras)
$ErrorActionPreference = "Stop"
$run  = "C:\Users\Sugar\build\mcfsr4\fabric\run"
$mods = "$run\mods"
$src  = "C:\Users\Sugar\OneDrive\Desktop\SugarSV-Mods"
$dep  = "C:\Users\Sugar\OneDrive\Desktop\Mod_FSR4_Java_Windows\dependencias_terceros"
if (Get-CimInstance Win32_Process | ? { $_.CommandLine -match 'KnotClient|devlaunchinjector' }) { Write-Host "ABORT: a Minecraft test client is running; close it first"; exit 3 }

$aeth   = "$dep\aetherium-0.1.3-mc26.3-fabric.jar"
$sod092 = "$dep\sodium-fabric-0.9.2+mc26.3.jar"
$sod093 = "$src\sodium-fabric-0.9.3-beta.5+mc26.3.jar"
# MEASURED 2026-11-14: Aetherium 0.1.3 does not start on Sodium 0.9.3-beta.5 (its ShaderChunkRendererMixin targets the old ShaderChunkRenderer constructor).
# MoreCulling requires Sodium > 0.9.3-beta.1, so it is out until Aetherium supports the newer Sodium. s2/s3 therefore stay on Sodium 0.9.2; s1 only documents the failure.
# Dynamic FPS removed (measured 2026-11-14): its default "hovered" state caps at 60 FPS and every state forces its own VSync value (no "leave as is"),
# which fights the FG/VSync pacing; the game's own inactivityFpsLimit already covers the unfocused/minimized case.
$render = "ImmediatelyFast-Fabric-1.17.1+26.3.jar","BadOptimizations-2.4.1-26.3-fabric.jar","entityculling-fabric-1.12.1-mc26.3.jar"
$logic  = "ferritecore-9.0.0-fabric.jar","lithium-fabric-0.26.3+mc26.3.jar","c2me-fabric-mc26.3-0.4.2-alpha.0.119.jar","servercore-fabric-1.5.20+26.3.jar","Clumps-fabric-26.3-26.3.2.jar","alternate-current-mc26.3-1.9.0.jar"
# Gameplay / QoL, not performance: only with -Extras
$extra  = "almanac-fabric-26.3-1.26.11.1.jar","letmedespawn-fabric-26.3-1.26.11.1.jar","fastquit-3.1.5+mc26.2.jar","cloth-config-fabric-26.3.159.jar"

$bk = "$run\mods_backup"
if ($Stage -eq "restore") {
    $last = Get-ChildItem $bk -Directory | Sort-Object Name | Select-Object -Last 1
    if (-not $last) { Write-Host "no backup to restore"; exit 1 }
    Get-ChildItem $mods -Filter *.jar | Remove-Item   # these are copies from the profile sources
    Move-Item "$($last.FullName)\*.jar" $mods
    Remove-Item $last.FullName -Recurse
    Write-Host "restored from $($last.Name)"; Get-ChildItem $mods -Name; exit 0
}

$list = switch ($Stage) {
    "baseline" { @($aeth, $sod092) }
    "s1"       { @($aeth, $sod093) }
    "s2"       { @($aeth, $sod092) + ($render | % { "$src\$_" }) }
    "s3"       { @($aeth, $sod092) + (($render + $logic) | % { "$src\$_" }) }
}
if ($Extras) { $list += $extra | % { "$src\$_" } }
foreach ($f in $list) { if (-not (Test-Path $f)) { Write-Host "MISSING $f"; exit 2 } }

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
New-Item -ItemType Directory -Force "$bk\$stamp" | Out-Null
Get-ChildItem $mods -Filter *.jar | Move-Item -Destination "$bk\$stamp"
foreach ($f in $list) { Copy-Item $f $mods }
Set-Content "$run\mod_profile.txt" "$Stage$(if ($Extras) {'+extras'}) $stamp" -Encoding ascii
Write-Host "profile $Stage active (previous jars in mods_backup\$stamp):"; Get-ChildItem $mods -Name
