@echo off
rem Starts GameLodge with its own Java runtime (no console window stays open).
start "" "%~dp0runtime\bin\javaw.exe" -Xmx1g -XX:+UseZGC -cp "%~dp0lib\*" io.github.thecodingdad.gamelodge.desktop.MainKt
