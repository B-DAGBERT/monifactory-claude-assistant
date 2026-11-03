@echo off
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
  echo Lance Installer.bat avant de demarrer l'assistant.
  pause
  exit /b 1
)
start "Assistant Monifactory" .venv\Scripts\python.exe assistant_mc.py
