@echo off
rem ============================================================
rem  拟股喵喵 - build
rem  用法: scripts\build.cmd [debug]
rem ============================================================
setlocal enabledelayedexpansion
chcp 65001 > nul
cd /d "%~dp0.."
set "ROOT=%CD%"
set "BUILD=%ROOT%\build"
set "SRC=%ROOT%\engine\src"
set "FSRC=%ROOT%\frontend\src"
set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not exist "!JAVAC!" set "JAVAC=A:\jdk-17.0.12\bin\javac.exe"
if not exist "!JAVAC!" for %%J in (javac.exe) do if not "%%~$PATH:J"=="" set "JAVAC=%%~$PATH:J"

set "CFLAGS=-std=c++17 -O2 -Wall -Wextra"
if /i "%~1"=="debug" set "CFLAGS=-std=c++17 -O0 -g -Wall -Wextra"

echo.
echo === [1/5] 环境 =====
if not exist "!JAVAC!" ( echo [X] 找不到 javac，请设置 JAVA_HOME 或安装 JDK17 & exit /b 2 )
where g++ > nul 2>&1 || ( echo [X] PATH 里找不到 g++ 与 javac，请安装 MSYS2/MinGW 并把 bin 加进 PATH & exit /b 2 )
for /f "delims=" %%v in ('g++ --version 2^>^&1 ^| findstr /b /c:"g++"') do echo     %%v

echo.
echo === [2/5] 构建 C++ 引擎 =====
if not exist "!BUILD!\engine" mkdir "!BUILD!\engine"
set "CPPS="
for %%f in ("!SRC!\*.cpp") do set "CPPS=!CPPS! "%%~f""
if "!CPPS!"=="" ( echo [X] engine\src 下没有 .cpp 入口文件 & exit /b 1 )
rem 增量判断：若 exe 比所有源码都新，则跳过重链接（避免刷新 PE 时间戳、破坏回归证据）
set "NEED_LINK=0"
set "EXE_TS="
for %%e in ("!BUILD!\engine\trade_sim.exe") do set "EXE_TS=%%~te"
if "!EXE_TS!"=="" (
  set "NEED_LINK=1"
) else (
  for %%f in ("!SRC!\*.hpp" "!SRC!\*.cpp") do if "%%~tf" GTR "!EXE_TS!" set "NEED_LINK=1"
)
if "!NEED_LINK!"=="0" (
  echo [=] 引擎已是最新^(源码未变^)，跳过重链接以保持二进制指纹稳定
  goto engine_done
)
echo     g++ !CFLAGS! -o build\engine\trade_sim.exe ...
rem 先删旧产物：引擎若曾被运行，残留句柄会让链接器写不进去
del /q "!BUILD!\engine\trade_sim.exe" > nul 2>&1
set /a LINK_TRY=0
:link_retry
g++ !CFLAGS! -o "!BUILD!\engine\trade_sim.exe" !CPPS!
if not errorlevel 1 goto link_ok
rem 链接可能因引擎正在运行而被占用：结束残留实例后重试一次
set /a LINK_TRY+=1
if !LINK_TRY! GTR 1 ( echo [X] 引擎编译失败 & exit /b 1 )
echo [!] 输出文件被占用，结束残留的 trade_sim.exe 后重试...
taskkill /f /im trade_sim.exe > nul 2>&1
ping -n 2 127.0.0.1 > nul
del /q "!BUILD!\engine\trade_sim.exe" > nul 2>&1
goto link_retry
:link_ok
echo [OK] build\engine\trade_sim.exe
:engine_done

echo.
echo === [3/5] 构建 Java 前端 =====
if not exist "!BUILD!\frontend\classes" mkdir "!BUILD!\frontend\classes"
set "JAVA="
for /f "delims=" %%f in ('dir /b /s "!FSRC!\*.java" 2^>nul') do set "JAVA=!JAVA! "%%~f""
if "!JAVA!"=="" ( echo [X] frontend\src 下没有 .java 文件 & exit /b 1 )
echo     javac -encoding UTF-8 -Xlint:all -d build\frontend\classes ...
set JAVA_TOOL_OPTIONS=-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8
"!JAVAC!" -encoding UTF-8 -Xlint:all -d "!BUILD!\frontend\classes" !JAVA!
set "JCERR=!errorlevel!"
if not "!JCERR!"=="0" ( echo [X] 前端编译失败 ^(exit !JCERR!^) & exit /b 1 )
echo [OK] build\frontend\classes

echo.
echo === [4/5] 打包 nigu-meow.jar =====
set "JAR=%JAVA_HOME%\bin\jar.exe"
if not exist "!JAR!" set "JAR=A:\jdk-17.0.12\bin\jar.exe"
if exist ""!JAR!"" (
  pushd "!BUILD!\frontend\classes"
  "!JAR!" --create --file "!BUILD!\frontend\nigu-meow.jar" --main-class tsim.Main .
  set "JRERR=!errorlevel!"
  popd
  if "!JRERR!"=="0" ( echo [OK] build\frontend\nigu-meow.jar ) else ( echo [!] jar 打包失败，可忽略^(仍可用 class 目录启动^) )
)

echo.
echo === [4b/5] 编译前端测试类 =====
if exist "!ROOT!\frontend\test" (
  if not exist "!BUILD!\selftest-classes" mkdir "!BUILD!\selftest-classes"
  set "JTEST="
  for /f "delims=" %%f in ('dir /b /s "!ROOT!\frontend\test\*.java" 2^>nul') do set "JTEST=!JTEST! "%%~f""
  if not "!JTEST!"=="" (
    "!JAVAC!" -encoding UTF-8 -Xlint:all -cp "!BUILD!\frontend\classes" -d "!BUILD!\selftest-classes" !JTEST! > nul 2>&1
    if errorlevel 1 (
      echo [!] 前端测试类编译失败^(tests 仅用于自测，不影响游戏运行^)
    ) else (
      echo [OK] build\selftest-classes
    )
  ) else (
    echo [!] frontend\test 下没有 .java
  )
) else (
  echo [!] 跳过：frontend\test 不存在
)

echo.
echo === [5/5] 构建工具 =====
if not exist "!BUILD!\tools" mkdir "!BUILD!\tools"
if exist "!ROOT!\tools\jsoncheck\jsoncheck.cpp" (
  del /q "!BUILD!\tools\jsoncheck.exe" > nul 2>&1
  g++ !CFLAGS! -o "!BUILD!\tools\jsoncheck.exe" "!ROOT!\tools\jsoncheck\jsoncheck.cpp"
  if errorlevel 1 ( echo [!] jsoncheck 编译失败 ) else ( echo [OK] build\tools\jsoncheck.exe )
)
if exist "!ROOT!\tools\driver\driver.cpp" (
  del /q "!BUILD!\tools\driver.exe" > nul 2>&1
  rem driver 用 Windows CNG 算 SHA256，需链接 bcrypt
  g++ !CFLAGS! -o "!BUILD!\tools\driver.exe" "!ROOT!\tools\driver\driver.cpp" -lbcrypt
  if errorlevel 1 ( echo [!] driver 编译失败 ) else ( echo [OK] build\tools\driver.exe )
)

echo.
echo ============================================================
echo  构建完成。运行: scripts\run.cmd
echo ============================================================
endlocal
exit /b 0