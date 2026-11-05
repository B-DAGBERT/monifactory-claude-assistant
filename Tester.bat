@echo off
setlocal
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
  echo Lance d'abord Installer.bat.
  pause
  exit /b 1
)
set "suite=%~1"
if defined suite goto :run
echo 1. Basique
echo 2. Complexe
echo 3. Tous
set /p "choix=Choix [3] : "
set "suite=tous"
if "%choix%"=="1" set "suite=basique"
if "%choix%"=="2" set "suite=complexe"
:run
.venv\Scripts\python.exe -m monifactory_assistant.commands.scenarios %suite%
set "resultat=%errorlevel%"
pause
exit /b %resultat%
