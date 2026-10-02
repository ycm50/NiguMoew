@echo off
rem ============================================================
rem  TradeTower 交易大亨 - 全量自测
rem  用法: scripts\selftest.cmd [quick]
rem ============================================================
setlocal enabledelayedexpansion
chcp 65001 > nul
cd /d "%~dp0.."
set "ROOT=%CD%"
set "BUILD=%ROOT%\build"
set "EV=%BUILD%\evidence"
set "FAILED=0"
if not exist "!EV!" mkdir "!EV!"

echo ########## TradeTower 自测 ##########
echo.
rem 记录被测引擎指纹，便于证据溯源（qa-verify 建议）
if exist "!BUILD!\engine\trade_sim.exe" (
  echo ---- engine fingerprint ----
  echo binary SHA256 ^(仅用于同轮内比对，重链接会漂移^):
  certutil -hashfile "!BUILD!\engine\trade_sim.exe" SHA256 | findstr /r /v /c:"hashfile" /c:"CertUtil" /c:"SHA256" /c:"^$"
  echo source fingerprint ^(代码定版，重链接不变^):
  if exist "!ROOT!\scripts\srcfingerprint.ps1" (
    rem 每次从源码现算，避免登记值过期（权威算法见该脚本）
    powershell -NoProfile -ExecutionPolicy Bypass -File "!ROOT!\scripts\srcfingerprint.ps1" 2>nul | findstr /r /c:"^SOURCE_FINGERPRINT="
  ) else (
    type "!EV!\engine_src_fingerprint.txt" | findstr /r /c:"^SOURCE FINGERPRINT"
  )
  echo.
)

rem ---- 0. 构建 ----------------
echo ---- [0/6] build ----
call "%ROOT%\scripts\build.cmd"
if errorlevel 1 ( echo [X] 构建失败，自测终止 & exit /b 1 )
echo.

rem ---- 1. jsoncheck 自测 ----------------
echo ---- [1/6] jsoncheck selftest ----
if exist "!EV!\run_jsoncheck_tests.cmd" (
  cmd /c "!EV!\run_jsoncheck_tests.cmd"
  if errorlevel 1 ( echo [X] jsoncheck selftest 失败 & set "FAILED=1" ) else ( echo [OK] jsoncheck selftest )
) else ( echo [!] 跳过：缺少 build\evidence\run_jsoncheck_tests.cmd )
echo.

rem ---- 2. C++ 单元冒烟 ----------------
echo ---- [2/6] engine smoke ----
set "GPP=A:\msys64\ucrt64\bin\g++.exe"
if not exist "!GPP!" set "GPP=g++"
if exist "!ROOT!\engine\tests\smoke.cpp" (
  set "SMOKEEXE=!BUILD!\tools\smoke.exe"
  set "SMOKELOG=!EV!\smoke.log"
  set "SMOKESRC=!ROOT!\engine\tests\smoke.cpp"
  set "SMOKEINC=!ROOT!\engine\src"
  !GPP! -std=c++17 -O2 -Wall -Wextra -I "!SMOKEINC!" -o "!SMOKEEXE!" "!SMOKESRC!" > "!SMOKELOG!" 2>&1
  if errorlevel 1 (
    echo [X] smoke 编译失败，详见 build\evidence\smoke.log
    set "FAILED=1"
  ) else (
    "!SMOKEEXE!" >> "!SMOKELOG!" 2>&1
    if errorlevel 1 ( echo [X] smoke 断失败，详见 build\evidence\smoke.log & set "FAILED=1" ) else ( echo [OK] smoke 全过 )
  )
) else ( echo [!] 跳过：engine\tests\smoke.cpp 不存在 )
echo.

rem ---- 3. 端到端驱动 ----------------
echo ---- [3/6] driver e2e ----
set "DQUICK="
if /i "%~1"=="quick" set "DQUICK=--quick"
if exist "!BUILD!\tools\driver.exe" (
  set "DRVLOG=!EV!\driver.log"
  set "DRVJSONL=!EV!\driver.jsonl"
  set "JSONCHK=!BUILD!\tools\jsoncheck.exe"
  "!BUILD!\tools\driver.exe" --log "!DRVLOG!" --jsonl "!DRVJSONL!" --jsoncheck "!JSONCHK!" !DQUICK!
  set "DRC=!errorlevel!"
  if not "!DRC!"=="0" ( echo [X] driver 失败^(exit !DRC!^)，详见 build\evidence\driver.log & set "FAILED=1" ) else ( echo [OK] driver 全过 )
) else ( echo [!] 跳过：缺少 build\tools\driver.exe )
echo.

rem ---- 4. 契约验证 ----------------
echo ---- [4/6] contract tests ----
set "QARAN=0"
if exist "!EV!\qa.log" del /q "!EV!\qa.log"
if exist "!BUILD!\qa\qa_contract.exe" (
  "!BUILD!\qa\qa_contract.exe" > "!EV!\qa.log" 2>&1
  if errorlevel 1 ( echo [X] qa_contract 失败 & set "FAILED=1" ) else ( echo [OK] qa_contract )
  set "QARAN=1"
)
if exist "!BUILD!\qa\qa_business.exe" (
  "!BUILD!\qa\qa_business.exe" >> "!EV!\qa.log" 2>&1
  set "QARC=!errorlevel!"
  if not "!QARC!"=="0" ( echo [!] qa_business 已执行但非全绿^(exit !QARC!^)，详见 build\evidence\qa.log & set "FAILED=1" ) else ( echo [OK] qa_business )
  set "QARAN=1"
)
if "!QARAN!"=="0" echo [!] 跳过：QA 可执行文件尚未构建
echo.

rem ---- 4b. 前端入口/端到端（java-ui 提供，Lead 纳入） ----------------
echo ---- [4b/6] frontend gates ----
set "JAVAX=A:\jdk-17.0.12\bin\java.exe"
if not exist "!JAVAX!" set "JAVAX=java"
set "FECP=!BUILD!\frontend\trade-tower.jar;!BUILD!\frontend\classes;!BUILD!\selftest-classes"
if exist "!BUILD!\selftest-classes\tsim\tests\EntryPointTest.class" (
  "!JAVAX!" -cp "!FECP!" tsim.tests.EntryPointTest > "!EV!\frontend.log" 2>&1
  if errorlevel 1 ( echo [X] EntryPointTest 失败^(见 build\evidence\frontend.log^) & set "FAILED=1" ) else ( echo [OK] EntryPointTest ^(入口拉起引擎^) )
) else ( echo [!] 跳过：EntryPointTest 未编译 )
if exist "!BUILD!\selftest-classes\tsim\tests\SelfTest.class" (
  "!JAVAX!" -cp "!FECP!" tsim.tests.SelfTest "!BUILD!\engine\trade_sim.exe" >> "!EV!\frontend.log" 2>&1
  if errorlevel 1 ( echo [X] 前端 SelfTest 失败 & set "FAILED=1" ) else ( echo [OK] 前端 SelfTest ^(真实引擎^) )
) else ( echo [!] 跳过：SelfTest 未编译 )
if exist "!BUILD!\selftest-classes\tsim\tests\RealEngineAcceptance.class" (
  "!JAVAX!" -cp "!FECP!" tsim.tests.RealEngineAcceptance >> "!EV!\frontend.log" 2>&1
  if errorlevel 1 ( echo [X] RealEngineAcceptance 失败 & set "FAILED=1" ) else ( echo [OK] RealEngineAcceptance )
) else ( echo [!] 跳过：RealEngineAcceptance 未编译 )
echo.

rem ---- 5. 引擎无参自检 ----------------
echo ---- [5/6] engine --selftest ----
if exist "!BUILD!\engine\trade_sim.exe" (
  "!BUILD!\engine\trade_sim.exe" --selftest > "!EV!\engine_selftest.log" 2>&1
  type "!EV!\engine_selftest.log"
  if errorlevel 1 ( echo [X] 引擎内置自检失败^(详见 build\evidence\engine_selftest.log^) & set "FAILED=1" ) else ( echo [OK] 引擎内置自检 )
) else ( echo [X] 引擎不存在 & set "FAILED=1" )
echo.

rem ---- 汇总 ----------------
echo ---- [6/6] summary ----
if "!FAILED!"=="0" (
  echo ########## 全部自测通过 ##########
  endlocal & exit /b 0
) else (
  echo ########## 自测失败，请查看 build\evidence\ 下的日志 ##########
  endlocal & exit /b 1
)