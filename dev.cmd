@echo off
REM Fast dev launcher for rapid iteration.
REM
REM Incrementally compiles only changed sources, then runs the engine straight
REM from target\classes via the pom's dev-run profile (exec:exec supplies the
REM runtime classpath, so no jar is built). This skips the ~40s fat-jar assembly
REM / mod-SDK / verify work that run.cmd performs on every launch -- typical
REM relaunch is ~8s + engine start.
REM
REM Use run.cmd instead when you need the distributable
REM OpenGGF-*-jar-with-dependencies.jar (releases, `java -jar`, native image).
REM
REM The -o flag runs Maven offline for speed. If it ever fails because a plugin
REM or dependency isn't cached yet, run `run.cmd` once (or drop -o here) to
REM populate the local ~/.m2 cache, then this launcher works offline again.

setlocal
call mvn -q -o -Dmse=off -Pdev-run compile exec:exec
endlocal
