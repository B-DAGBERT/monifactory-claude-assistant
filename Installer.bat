@echo off
setlocal
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
  python -m venv .venv
  if errorlevel 1 goto :error
)
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto :error
.venv\Scripts\python.exe codex_setup.py setup
if errorlevel 1 goto :error
echo Pret. Lance Lancer assistant.bat.
pause
exit /b 0
:error
echo Installation interrompue. Lis l'erreur ci-dessus.
pause
exit /b 1
