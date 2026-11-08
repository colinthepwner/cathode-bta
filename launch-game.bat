@echo off
cd /d "%~dp0"
call gradlew.bat runClient
if errorlevel 1 pause
