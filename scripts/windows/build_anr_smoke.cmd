@echo off
rem Build tests\anr_smoke.exe (standalone ANR FG check). Needs the NGX headers in native\third_party\ngx\include (local only).
setlocal
if "%VSDEVCMD%"=="" set "VSDEVCMD=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat"
if "%VK_HEADERS%"=="" set "VK_HEADERS=%USERPROFILE%\OneDrive\Desktop\Mod_FSR4_Java_Windows\librerias_compilacion\vk-headers\include"
set "OUT=%~dp0..\..\build-win"
if not exist "%OUT%\obj" mkdir "%OUT%\obj"
call "%VSDEVCMD%" -arch=x64 >nul 2>&1
cd /d "%~dp0..\.."
cl /nologo /std:c++20 /EHsc /O2 /W3 /DNOMINMAX /Inative\third_party\ngx\include /I"%VK_HEADERS%" tests\anr_smoke.cpp /Fe:"%OUT%\anr_smoke.exe" /Fo:"%OUT%\obj\\" user32.lib || exit /b 1
echo built %OUT%\anr_smoke.exe
endlocal
