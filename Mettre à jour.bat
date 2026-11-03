@echo off
cd /d "%~dp0"
if exist .venv\Scripts\python.exe (
  .venv\Scripts\python.exe update_assistant.py
) else (
  python update_assistant.py
)
if errorlevel 1 echo Mise a jour interrompue. Lis l'erreur ci-dessus.
pause
