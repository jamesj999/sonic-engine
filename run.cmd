@echo off
setlocal
REM This is a normal, non-certifying local launcher. Keep the distributable in
REM target\ so each worktree keeps its own Maven output.
pushd "%~dp0"
if errorlevel 1 goto :directory_failed

call mvn -Dmse=off -DskipTests package -q
if errorlevel 1 goto :package_failed

set "MANIFEST=%CD%\target\openggf-artifact.properties"
if not exist "%MANIFEST%" goto :metadata_missing

set "FINAL_NAME="
for /f "usebackq tokens=1,* delims==" %%A in ("%MANIFEST%") do if "%%A"=="finalName" set "FINAL_NAME=%%B"
if not defined FINAL_NAME goto :metadata_missing

set "JAR=%CD%\target\%FINAL_NAME%-jar-with-dependencies.jar"
if not exist "%JAR%" goto :jar_missing

java --add-exports java.base/java.lang=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d=ALL-UNNAMED -XX:+UseG1GC -XX:MaxGCPauseMillis=5 -jar "%JAR%"
set "STATUS=%ERRORLEVEL%"
popd
exit /b %STATUS%

:directory_failed
echo Unable to enter launcher directory: %~dp0 1>&2
exit /b 1

:package_failed
set "STATUS=%ERRORLEVEL%"
echo Maven package failed. 1>&2
popd
exit /b %STATUS%

:metadata_missing
echo Maven packaging did not produce a valid artifact manifest: %MANIFEST% 1>&2
popd
exit /b 1

:jar_missing
echo Expected packaged jar not found: %JAR% 1>&2
popd
exit /b 1
