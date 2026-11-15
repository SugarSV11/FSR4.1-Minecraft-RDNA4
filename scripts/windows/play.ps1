# Play the test client by hand with the chosen setup: mod profile s3, Better Voxels (pack defaults) on Aetherium renderScale 65,
# FSR 4.1 QUALITY + AMD FG x2 + automatic pacing + VSync, all enabled from startup. No test script runs (FSR4_SCRIPT is cleared).
# In game: F10 FSR on/off, F7 FG on/off, mod menu for the rest. When the game closes, the previous mods are put back.
param([int]$Scale = 65, [switch]$NoFg, [string]$Gen = "AUTO")
$ErrorActionPreference = "Continue"
$root = "C:\Users\Sugar\build\mcfsr4"; $run = "$root\fabric\run"; $w = "$root\scripts\windows"
if (Get-CimInstance Win32_Process | ? { $_.CommandLine -match 'KnotClient|devlaunchinjector' }) { Write-Host "ABORT: a Minecraft test client is already running"; exit 3 }
& powershell -ExecutionPolicy Bypass -File "$w\mod_profile.ps1" -Stage s3 | Select-Object -First 1
$fg = if ($NoFg) { "false" } else { "true" }
Set-Content "$run\config\fsr4linux.json" "{`n  `"enabled`": true,`n  `"backend`": `"FSR4_1_1`",`n  `"quality`": `"QUALITY`",`n  `"sharpness`": 0.5,`n  `"debugOverlay`": false,`n  `"fpsCounter`": true,`n  `"frameGeneration`": $fg,`n  `"fgPacing`": false,`n  `"fgPacingAuto`": true,`n  `"fgMultiplier`": 2,`n  `"fgGenerator`": `"$Gen`"`n}`n" -Encoding ascii
Set-Content "$run\config\aetherium.json" ('{"shaders":{"pack":"BetterVoxels_v1.8.zip","enabled":true,"renderScale":' + $Scale + '},"optimisations":{"mem.state_cache_dedup":false,"mem.palette_lock":false,"mem.component_patch":false}}') -Encoding ascii
Remove-Item "$run\shaderpacks\BetterVoxels_v1.8.zip.txt" -ErrorAction SilentlyContinue   # pack defaults = the "high" quality the benchmarks used
$opt = "$run\options.txt"; $t = Get-Content $opt
function SetOpt($k, $v) { $script:t = if ($script:t -match "^$k`:") { $script:t -replace "^$k`:.*", "$k`:$v" } else { $script:t + "$k`:$v" } }
SetOpt "enableVsync" "true"; SetOpt "preferredGraphicsBackend" '"vulkan"'; SetOpt "inactivityFpsLimit" '"minimized"'
$t | Set-Content $opt -Encoding ascii
Remove-Item Env:FSR4_SCRIPT, Env:FSR4_PACE_K, Env:MOTION_GEN -ErrorAction SilentlyContinue
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:FSR4_RUNTIME_DIR = "$run\config\fsr4linux-runtime"
Set-Location "$root\fabric"
& .\gradlew.bat runClient --console=plain "--args=--width 1920 --height 1080" *> "C:\Users\Sugar\build\play_session.log"
& powershell -ExecutionPolicy Bypass -File "$w\mod_profile.ps1" -Stage restore | Select-Object -First 1
"game closed; log: C:\Users\Sugar\build\play_session.log"
