@echo off
rem ============================================================
rem  TradeTower - double-click to play
rem  Pure simulation game. No real money involved.
rem  (This file is intentionally ASCII-only: cmd.exe reads .bat
rem   in the OEM codepage, so non-ASCII text here would be mangled.)
rem ============================================================
chcp 65001 > nul
cd /d "%~dp0"
call "%~dp0scripts\run.cmd" %*
exit /b %errorlevel%
