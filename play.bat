@echo off
rem ============================================================
rem  TradeTower - double-click to play
rem  Pure simulation game. No real money involved.
rem ============================================================
chcp 65001 > nul
cd /d "%~dp0"
call "%~dp0scripts\run.cmd" %*
exit /b %errorlevel%
