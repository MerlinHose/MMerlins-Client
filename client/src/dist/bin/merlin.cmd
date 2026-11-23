@echo off
rem Minecraft Merlin Client - Start der Testdistribution (docs/61 Abschnitt 8). Benoetigt Java 25 (JAVA_HOME oder java im PATH)
rem und eine lokale Minecraft-26.3-Installation (.minecraft\versions\26.3\26.3.jar); die Distribution enthaelt keine Mojang-Dateien.
setlocal
set "APP_HOME=%~dp0.."
set "JAVA_EXE=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
set "JV_FILE=%TEMP%\merlin-java-version-%RANDOM%.txt"
"%JAVA_EXE%" -version 2> "%JV_FILE%"
if errorlevel 1 (
  del "%JV_FILE%" >nul 2>&1
  echo Minecraft Merlin Client kann nicht starten: Java wurde nicht gefunden.
  echo Bitte Java 25 installieren und JAVA_HOME setzen oder java in den PATH aufnehmen.
  exit /b 2
)
set "JV="
for /f "usebackq tokens=3" %%v in (`findstr /i "version" "%JV_FILE%"`) do if not defined JV set "JV=%%~v"
del "%JV_FILE%" >nul 2>&1
set "JMAJOR=0"
for /f "delims=." %%m in ("%JV%") do set "JMAJOR=%%m"
if "%JMAJOR%"=="1" set "JMAJOR=8"
if %JMAJOR% LSS 25 (
  echo Minecraft Merlin Client kann nicht starten: Java %JV% gefunden, benoetigt wird Java 25 oder neuer.
  exit /b 2
)
"%JAVA_EXE%" -Xmx768m -XX:MaxDirectMemorySize=256m --enable-native-access=ALL-UNNAMED -cp "%APP_HOME%\lib\*" dev.mcresearch.protocolclient.gl.MerlinLauncher %*
exit /b %errorlevel%
