@echo off
rem ============================================================
rem  拟股喵喵 - run
rem  用法: scripts\run.cmd
rem ============================================================
setlocal enabledelayedexpansion
chcp 65001 > nul
cd /d "%~dp0.."
set "ROOT=%CD%"
set "EXE=%ROOT%\build\engine\trade_sim.exe"
set "CLS=%ROOT%\build\frontend\classes"
set "JAR=%ROOT%\build\frontend\nigu-meow.jar"
rem 优先 javaw.exe（无控制台窗口）；找不到再退回 java.exe（会留一个黑窗）。
rem 用 java.exe 时 start 会给它分配新控制台，脚本退出后黑窗仍留在桌面上。
set "JAVA="
set "HAS_W="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" (
  set "JAVA=%JAVA_HOME%\bin\javaw.exe"
  set "HAS_W=1"
)
if not defined HAS_W if exist "A:\jdk-17.0.12\bin\javaw.exe" (
  set "JAVA=A:\jdk-17.0.12\bin\javaw.exe"
  set "HAS_W=1"
)
if not defined HAS_W for %%j in (javaw.exe) do if not "%%~$PATH:j"=="" (
  set "JAVA=%%~$PATH:j"
  set "HAS_W=1"
)
if not defined HAS_W (
  if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
  if not defined JAVA if exist "A:\jdk-17.0.12\bin\java.exe" set "JAVA=A:\jdk-17.0.12\bin\java.exe"
  if not defined JAVA for %%j in (java.exe) do if not "%%~$PATH:j"=="" set "JAVA=%%~$PATH:j"
)
if not defined JAVA (
  echo [X] 未找到 javaw.exe / java.exe，请安装 JDK 17 并设置 JAVA_HOME
  pause
  exit /b 2
)

if not exist "!EXE!" (
  echo [X] 未找到引擎 !EXE!
  echo     请先运行: scripts\build.cmd
  pause
  exit /b 1
)

set "JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8"
set "CP=!CLS!"
if exist "!JAR!" set "CP=!JAR!;!CLS!"

echo Starting NiguMeow ...
echo   引擎: !EXE!
echo   纯模拟交易游戏，不涉及任何真实资金。
echo.
start "NiguMeow" /d "!ROOT!" "!JAVA!" -Dsun.java2d.uiScale=1.0 -cp "!CP!" tsim.Main
endlocal
exit /b 0