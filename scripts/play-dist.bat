@echo off
rem ============================================================
rem  NiguMeow - double-click to play
rem  Pure simulation game. No real money involved.
rem  Prefers the bundled JRE under runtime\jre.
rem ============================================================
chcp 65001 > nul
cd /d "%~dp0"

rem Use javaw.exe only -- it has no console window, so nothing is left behind
rem after this script exits. (java.exe would pop a black console that outlives us.)
set "JAVA="
if exist "%~dp0runtime\jre\bin\javaw.exe" set "JAVA=%~dp0runtime\jre\bin\javaw.exe"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVA=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVA if exist "A:\jdk-17.0.12\bin\javaw.exe" set "JAVA=A:\jdk-17.0.12\bin\javaw.exe"
if not defined JAVA for %%j in (javaw.exe) do if not "%%~$PATH:j"=="" set "JAVA=%%~$PATH:j"

if not defined JAVA (
  echo [X] javaw.exe not found. Please install JDK 17 (or keep runtime\jre in the package).
  pause
  exit /b 2
)

if not exist "%~dp0engine\trade_sim.exe" (
  echo [X] Missing engine\trade_sim.exe -- the package is incomplete.
  pause
  exit /b 1
)

set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8"
start "NiguMeow" /d "%~dp0" "%JAVA%" -cp "%~dp0frontend\nigu-meow.jar" tsim.Main
exit /b 0
