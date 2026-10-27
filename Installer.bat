@echo off
rem Installe les modules Python necessaires (a faire une seule fois).
python -m pip install -r "%~dp0requirements.txt"
pause
