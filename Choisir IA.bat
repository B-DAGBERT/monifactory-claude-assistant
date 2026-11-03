@echo off
cd /d "%~dp0"
if exist .venv\Scripts\python.exe (
  .venv\Scripts\python.exe choose_ai.py
) else (
  python choose_ai.py
)
pause
