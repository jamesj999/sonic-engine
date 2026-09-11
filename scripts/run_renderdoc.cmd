@echo off
setlocal
cd /d "%~dp0.."

set "RENDERDOC_DIR=C:\Program Files\RenderDoc"
set "RENDERDOC_CMD="
set "JAVA_EXE="

for /f "delims=" %%f in ('where renderdoccmd.exe 2^>nul') do (
    if not defined RENDERDOC_CMD set "RENDERDOC_CMD=%%f"
)

if not defined RENDERDOC_CMD (
    if exist "%RENDERDOC_DIR%\renderdoccmd.exe" (
        set "RENDERDOC_CMD=%RENDERDOC_DIR%\renderdoccmd.exe"
        set "PATH=%RENDERDOC_DIR%;%PATH%"
    )
)

if not defined RENDERDOC_CMD (
    echo RenderDoc command-line launcher not found.
    echo Expected renderdoccmd.exe on PATH or at "%RENDERDOC_DIR%\renderdoccmd.exe".
    pause
    exit /b 1
)

for /f "delims=" %%f in ('where java.exe 2^>nul') do (
    if not defined JAVA_EXE set "JAVA_EXE=%%f"
)

if not defined JAVA_EXE (
    echo java.exe not found on PATH.
    pause
    exit /b 1
)

call mvn -Dmse=off -DskipTests package -q
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

set "JAR="
for /f "delims=" %%f in ('dir /b target\*-jar-with-dependencies.jar 2^>nul') do set "JAR=target\%%f"

if not defined JAR (
    echo No jar file found
    pause
    exit /b 1
)

if not exist target\renderdoc mkdir target\renderdoc

echo Launching OpenGGF under RenderDoc...
echo Capture hotkey: PrtSc
echo Captures: %CD%\target\renderdoc

"%RENDERDOC_CMD%" capture ^
    --working-dir "%CD%" ^
    --capture-file "%CD%\target\renderdoc\openggf" ^
    --opt-hook-children ^
    -w ^
    "%JAVA_EXE%" ^
    --add-exports java.base/java.lang=ALL-UNNAMED ^
    --add-exports java.desktop/sun.awt=ALL-UNNAMED ^
    --add-exports java.desktop/sun.java2d=ALL-UNNAMED ^
    -XX:+UseG1GC ^
    -XX:MaxGCPauseMillis=5 ^
    -jar "%JAR%"

endlocal
