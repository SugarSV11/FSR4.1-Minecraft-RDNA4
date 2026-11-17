param([switch]$Shader, [switch]$Vsync, [int]$Mult = 2, [string]$Profile = "", [switch]$Pacing, [switch]$AutoPace, [string]$Sharp = "0.5", [string]$Gen = "AUTO", [string]$PaceK = "", [int]$Scale = 100, [string]$Pre = "", [int]$Wait = 160, [string]$Tag = "", [string]$Mode = "native", [string]$Quality = "QUALITY", [int]$W = 2560, [int]$H = 1440, [int]$Rd = 16)
$ErrorActionPreference = "Continue"
$root = "C:\Users\Sugar\build\mcfsr4"; $run = "$root\fabric\run"
# Never run two games at once: they share the run dir (options.txt, config) and the GPU, and the numbers are meaningless.
if (Get-CimInstance Win32_Process | ? { $_.CommandLine -match 'KnotClient|devlaunchinjector' }) { Write-Host "ABORT: another Minecraft test client is already running"; exit 3 }
$en = "false"
if ($PaceK) { $env:FSR4_PACE_K = $PaceK } else { Remove-Item Env:FSR4_PACE_K -ErrorAction SilentlyContinue }
$pace = if ($Pacing) { "true" } else { "false" }
$auto = if ($AutoPace) { "true" } else { "false" }   # default off so runs stay comparable with the earlier ones
$cfg = "{`n  `"enabled`": $en,`n  `"backend`": `"FSR4_1_1`",`n  `"quality`": `"$Quality`",`n  `"sharpness`": $Sharp,`n  `"debugOverlay`": false,`n  `"fpsCounter`": false,`n  `"frameGeneration`": false,`n  `"fgPacing`": $pace,`n  `"fgPacingAuto`": $auto,`n  `"fgMultiplier`": $Mult,`n  `"fgGenerator`": `"$Gen`"`n}`n"
Set-Content "$run\config\fsr4mc.json" $cfg -Encoding ascii
$ae = if ($Shader) { '{"shaders":{"pack":"BetterVoxels_v1.8.zip","enabled":true,"renderScale":' + $Scale + '}' } else { '{"shaders":{"enabled":false}' }
# One owner per optimisation: with FerriteCore loaded it owns block-state cache dedup, the PalettedContainer threading-detector removal
# and the data-component patch map, so Aetherium's copies of the same three are switched off (mod_profile.ps1 stage s3).
if (Test-Path "$run\mods\ferritecore-*.jar") { $ae += ',"optimisations":{"mem.state_cache_dedup":false,"mem.palette_lock":false,"mem.component_patch":false}' }
$ae += '}'
Set-Content "$run\config\aetherium.json" $ae -Encoding ascii
# Shader pack quality profile: "" = pack defaults (no options file), otherwise one of the pack's profiles (A_POTATO, B_LOW, C_MEDIUM, D_HIGH, E_HIGHPLUS, ...)
$optFile = "$run\shaderpacks\BetterVoxels_v1.8.zip.txt"
Remove-Item $optFile -ErrorAction SilentlyContinue
if ($Shader -and $Profile) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead("$run\shaderpacks\BetterVoxels_v1.8.zip"); $sr = New-Object IO.StreamReader($zip.GetEntry("shaders/shaders.properties").Open()); $txt = $sr.ReadToEnd(); $sr.Close(); $zip.Dispose()
    $line = ($txt -split "`n" | ? { $_ -match "^\s*profile\.$Profile\s*=" } | select -First 1)
    if ($line) {
        $rest = $line -replace "^\s*profile\.$Profile\s*=\s*", ""
        $out = foreach ($tok in ($rest.Trim() -split '\s+')) { if ($tok -match '^!(.+)$') { "$($Matches[1])=false" } elseif ($tok -match '^(.+?)=(.+)$') { $tok } elseif ($tok) { "$tok=true" } }
        Set-Content $optFile $out -Encoding ascii
    } else { Write-Host "profile $Profile not found" }
}
$opt = "$run\options.txt"
$t = Get-Content $opt
function SetOpt($k, $v) { $script:t = if ($script:t -match "^$k`:") { $script:t -replace "^$k`:.*", "$k`:$v" } else { $script:t + "$k`:$v" } }
SetOpt "enableVsync" $(if ($Vsync) { "true" } else { "false" }); SetOpt "maxFps" "260"; SetOpt "renderDistance" "$Rd"; SetOpt "preferredGraphicsBackend" '"vulkan"'; SetOpt "onboardAccessibility" "false"; SetOpt "inactivityFpsLimit" '"minimized"'
$t | Set-Content $opt -Encoding ascii
Remove-Item "$run\fsr4-test.log", "$run\bench.csv" -ErrorAction SilentlyContinue
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:FSR4_RUNTIME_DIR = "$run\config\fsr4mc-runtime"
$tog = switch ($Mode) { "fsr" { "key toggle; " } "fg" { "key fg; " } "fsr_fg" { "key toggle; wait 100; key fg; " } default { "" } }
$env:FSR4_SCRIPT = "wait 40; cmd /tp @s 300 140 300; wait 80; fly; look 30 20; wait $Wait; $Pre $tog wait 150; state; shot scene_$Mode$Tag; wait 10; bench r1 200; bench r2 200; bench r3 200; log state-end; state; quit"
Set-Location "$root\fabric"
& .\gradlew.bat runClient --console=plain "--args=--width $W --height $H" *> "C:\Users\Sugar\build\bench_$Mode$Tag.log"
Copy-Item "$run\fsr4-test.log" "C:\Users\Sugar\build\bench_$Mode$Tag.testlog" -Force -ErrorAction SilentlyContinue
Copy-Item "$run\bench.csv" "C:\Users\Sugar\build\bench_$Mode$Tag.csv" -Force -ErrorAction SilentlyContinue
"done $Mode"





