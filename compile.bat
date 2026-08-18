@echo off
rem ============================================================
rem  2009scape rebuild + relaunch helper
rem  - stops a running server (any java process on server.jar)
rem  - builds Server with JDK 17 (Kotlin 1.8 cannot parse newer
rem    JDK version strings, so JAVA_HOME is pinned here)
rem  - refreshes Server\server.jar and launches it in a new window
rem ============================================================
setlocal
cd /d "%~dp0"

set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "JAVAEXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVAEXE%" (
    echo [compile] JDK 17 not found at %JAVA_HOME% - adjust JAVA_HOME in compile.bat.
    exit /b 1
)

echo [compile] Stopping running server, if any...
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -match 'server\.jar' } | ForEach-Object { Write-Host ('[compile] killed PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force }"

cd Server

if not exist hasRan.txt (
    echo [compile] First build on this machine - running clean...
    cmd /c mvnw.cmd clean
    if errorlevel 1 exit /b 1
    copy NUL hasRan.txt >NUL
)

echo [compile] Building (tests skipped)...
call mvnw.cmd -q package -DskipTests
if errorlevel 1 (
    echo [compile] BUILD FAILED.
    exit /b 1
)

xcopy /Y target\*-with-dependencies.jar server.jar* >NUL
if errorlevel 1 (
    echo [compile] Failed to stage server.jar.
    exit /b 1
)

echo [compile] Launching server in a new window...
start "2009scape" cmd /k ""%JAVAEXE%" -jar server.jar"
echo [compile] Done.
