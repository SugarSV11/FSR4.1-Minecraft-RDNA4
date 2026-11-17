@echo off
rem Build the native Windows artifacts without CMake (MSVC Build Tools 2022).
rem   scripts\windows\build.cmd [OUT_DIR]
rem Needs: Vulkan-Headers (include\vulkan), FidelityFX SDK 2.4.0 (Kits\FidelityFX) and the D3D12/DXGI import libs from the Windows SDK.
rem Output: fsr4linux.dll (loaded by the mod), fsr4_bridge.exe (D3D12 helper), win_smoke.exe (native smoke test).
rem Override the three paths below with environment variables if yours differ.
setlocal
if "%VSDEVCMD%"=="" set "VSDEVCMD=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat"
if "%VK_HEADERS%"=="" set "VK_HEADERS=%USERPROFILE%\.cache\fsr4-windows\vk-headers\include"
if "%FFX_SDK_DIR%"=="" set "FFX_SDK_DIR=%USERPROFILE%\build\sdk\Kits\FidelityFX"
set "OUT=%~1"
if "%OUT%"=="" set "OUT=%~dp0..\..\build-win"
if not exist "%OUT%\obj" mkdir "%OUT%\obj"
call "%VSDEVCMD%" -arch=x64 >nul 2>&1
cd /d "%~dp0..\.."

rem ANR FG support only when the NGX interface headers are present locally (NVIDIA RTX SDK licence: never committed).
set "ANR_FLAGS=" & set "ANR_SRC="
if exist native\third_party\ngx\include\nvsdk_ngx_defs_dlssg.h set "ANR_FLAGS=/DFSR4_WITH_ANR /Inative\third_party\ngx\include" & set "ANR_SRC=native\src\anr_pass.cpp"
echo == fsr4linux.dll %ANR_FLAGS%
cl /nologo /std:c++20 /EHsc /O2 /LD /DVK_USE_PLATFORM_WIN32_KHR /DFSR4_NATIVE_WINDOWS /DNOMINMAX %ANR_FLAGS% /Inative\include /I"%VK_HEADERS%" ^
  native\src\fsr4_capi.cpp native\src\bridge_client.cpp native\src\gpu_bridge.cpp native\src\prep_pass.cpp native\src\fgx3_pass.cpp %ANR_SRC% ^
  /Fe:"%OUT%\fsr4linux.dll" /Fo:"%OUT%\obj\\" user32.lib winmm.lib || exit /b 1

echo == fsr4_bridge.exe
pushd native
cl /nologo /std:c++20 /EHsc /O2 /DFSR4_NATIVE_WINDOWS /DNOMINMAX /Iinclude /I"%FFX_SDK_DIR%\api\include" /I"%FFX_SDK_DIR%\upscalers\include" ^
  /I"%FFX_SDK_DIR%\framegeneration\include" /I"%FFX_SDK_DIR%\api\internal" backends\fsr4\bridge\fsr4_bridge.cpp ^
  /Fe:"%OUT%\fsr4_bridge.exe" /Fo:"%OUT%\obj\\" d3d12.lib dxgi.lib user32.lib winmm.lib || (popd & exit /b 1)
popd

echo == win_smoke.exe
cl /nologo /std:c++20 /EHsc /O2 /DVK_USE_PLATFORM_WIN32_KHR /DFSR4_NATIVE_WINDOWS /DNOMINMAX /Inative\include /I"%VK_HEADERS%" ^
  tests\win_smoke.cpp native\src\bridge_client.cpp native\src\gpu_bridge.cpp /Fe:"%OUT%\win_smoke.exe" /Fo:"%OUT%\obj\\" user32.lib winmm.lib || exit /b 1

echo.
echo Done. Copy into <minecraft>\config\fsr4mc-runtime: lib\fsr4linux.dll, bridge\fsr4_bridge.exe plus the official AMD DLLs
echo (amd_fidelityfx_loader_dx12.dll, amd_fidelityfx_upscaler_dx12.dll, optional amd_fidelityfx_framegeneration_dx12.dll) next to the helper.
endlocal
