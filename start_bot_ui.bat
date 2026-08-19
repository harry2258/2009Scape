@echo off
echo ===================================================
echo Starting Landsraad Bot Control Panel...
echo ===================================================
echo The control panel will open in your default browser.
echo Leave this window open to keep the UI server running.
echo Press Ctrl+C to stop the server when you are done.
echo.

:: Always serve from the directory containing this .bat file
cd /d "%~dp0"
echo Serving from: %~dp0

:: Open the browser slightly after the server starts
timeout /t 1 /nobreak >nul
start "" http://localhost:8789/bot_ui.html

:: Start the dashboard server (static files + /api proxy to the game server) in the project directory
python bot_ui_server.py 8789
