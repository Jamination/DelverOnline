@echo off
rem Starts Delver with the Delver Online multiplayer mod (Windows).
rem The Steam installation is only read from; the modded game's options, saves and last server
rem live in DELVERONLINE_DATA (default %APPDATA%\DelverOnline). DELVERONLINE_JVM_OPTS adds JVM options.
rem DELVERONLINE_NO_STEAM=1 skips the Steam sign-in, so a second copy can run next to a Steam-started one.
setlocal
set "HERE=%~dp0"
if "%DELVERONLINE_JAR%"=="" set "DELVERONLINE_JAR=%HERE%delveronline.jar"
if not exist "%DELVERONLINE_JAR%" set "DELVERONLINE_JAR=%HERE%..\build\libs\delveronline.jar"
if "%DELVER_DIR%"=="" set "DELVER_DIR=C:\Program Files (x86)\Steam\steamapps\common\Delver"
if "%DELVERONLINE_DATA%"=="" set "DELVERONLINE_DATA=%APPDATA%\DelverOnline"

if not exist "%DELVER_DIR%\delver.jar" (
    echo Delver not found in "%DELVER_DIR%" - set DELVER_DIR
    exit /b 1
)

set "JAVA=java"
for %%J in ("jre-windows\win64\bin\javaw.exe" "jre-windows\win32\bin\javaw.exe" "jre\bin\javaw.exe" "jre-windows\bin\javaw.exe") do (
    if exist "%DELVER_DIR%\%%~J" set "JAVA=%DELVER_DIR%\%%~J"
)

if not exist "%DELVERONLINE_DATA%" mkdir "%DELVERONLINE_DATA%"
set "SKIP_STEAM="
if not "%DELVERONLINE_NO_STEAM%"=="" if not "%DELVERONLINE_NO_STEAM%"=="0" set "SKIP_STEAM=1"
if defined SKIP_STEAM del /q "%DELVERONLINE_DATA%\steam_appid.txt" >nul 2>nul
if not defined SKIP_STEAM if not exist "%DELVERONLINE_DATA%\steam_appid.txt" copy "%DELVER_DIR%\steam_appid.txt" "%DELVERONLINE_DATA%\" >nul 2>nul
cd /d "%DELVERONLINE_DATA%"
start "" "%JAVA%" %DELVERONLINE_JVM_OPTS% -javaagent:"%DELVERONLINE_JAR%" -cp "%DELVER_DIR%\delver.jar;%DELVERONLINE_JAR%" -Duser.country=US -Duser.language=en com.interrupt.dungeoneer.DesktopStarter %*
