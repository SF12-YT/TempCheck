@echo off
setlocal
set GRADLE_VERSION=8.9
if not defined GRADLE_USER_HOME set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set DIST_ROOT=%GRADLE_USER_HOME%\templearn-bootstrap
set GRADLE_BIN=%DIST_ROOT%\gradle-%GRADLE_VERSION%\bin\gradle.bat
if exist "%GRADLE_BIN%" goto run_gradle
where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  for /f "tokens=2" %%V in ('gradle --version ^| findstr /R /C:"^Gradle [0-9]"') do set SYSTEM_GRADLE_VERSION=%%V
  if "%SYSTEM_GRADLE_VERSION%"=="%GRADLE_VERSION%" (
    gradle %*
    exit /b %ERRORLEVEL%
  )
)
if not exist "%DIST_ROOT%" mkdir "%DIST_ROOT%"
set ARCHIVE=%DIST_ROOT%\gradle-%GRADLE_VERSION%-bin.zip
echo Gradle %GRADLE_VERSION% is not installed; downloading it from services.gradle.org...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ARCHIVE%'"
if errorlevel 1 exit /b %ERRORLEVEL%
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ARCHIVE%' '%DIST_ROOT%'"
if errorlevel 1 exit /b %ERRORLEVEL%
:run_gradle
call "%GRADLE_BIN%" %*
exit /b %ERRORLEVEL%
