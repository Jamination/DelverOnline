@echo off
rem Starts a Delver Online dedicated server (Windows). Type 'help' in the console for commands.
rem Options: --port <udp port> --name <name> --max-players <1-8>  (see --help)
setlocal
set "HERE=%~dp0"
if "%DELVERONLINE_JAR%"=="" set "DELVERONLINE_JAR=%HERE%delveronline.jar"
if not exist "%DELVERONLINE_JAR%" set "DELVERONLINE_JAR=%HERE%..\build\libs\delveronline.jar"
if "%DELVER_DIR%"=="" set "DELVER_DIR=C:\Program Files (x86)\Steam\steamapps\common\Delver"
if "%DELVERONLINE_SERVER_DATA%"=="" set "DELVERONLINE_SERVER_DATA=%APPDATA%\DelverOnline\server"

if not exist "%DELVER_DIR%\delver.jar" (
    echo Delver not found in "%DELVER_DIR%" - set DELVER_DIR
    exit /b 1
)

set "JAVA=java"
for %%J in ("jre-windows\win64\bin\java.exe" "jre-windows\win32\bin\java.exe" "jre\bin\java.exe" "jre-windows\bin\java.exe") do (
    if exist "%DELVER_DIR%\%%~J" set "JAVA=%DELVER_DIR%\%%~J"
)

if not exist "%DELVERONLINE_SERVER_DATA%" mkdir "%DELVERONLINE_SERVER_DATA%"
cd /d "%DELVERONLINE_SERVER_DATA%"
"%JAVA%" -Xmx1g -javaagent:"%DELVERONLINE_JAR%" -cp "%DELVER_DIR%\delver.jar;%DELVERONLINE_JAR%" -Duser.country=US -Duser.language=en com.delveronline.server.ServerMain %*
