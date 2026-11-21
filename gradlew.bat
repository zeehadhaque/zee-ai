@rem Gradle startup script for Windows CMD
@if "%DEBUG%"=="" @echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
if exist "%CLASSPATH%" (
  java -Xmx64m -Xms64m -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
) else (
  gradle %*
)
endlocal
