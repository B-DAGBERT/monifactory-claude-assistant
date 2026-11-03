@echo off
cd /d "%~dp0"
if not exist .venv\Scripts\pythonw.exe (
  echo Lance Installer.bat avant de demarrer l'assistant.
  pause
  exit /b 1
)
start "" .venv\Scripts\pythonw.exe assistant_mc.py
