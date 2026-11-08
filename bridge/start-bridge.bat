@echo off
chcp 65001 >nul
cd /d "%~dp0"
where claude >nul 2>nul || (echo Claude Code не найден. Установите его и войдите в аккаунт: https://claude.com/claude-code & pause & exit /b 1)
where python >nul 2>nul || (echo Python не найден. Установите с python.org & pause & exit /b 1)
python rw_bridge.py --host 0.0.0.0
pause
