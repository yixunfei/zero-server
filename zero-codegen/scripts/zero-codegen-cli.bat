@echo off
setlocal
rem Resolve only the current reactor version. Explicit arguments support unattended checks.
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0InvokeCodegen.ps1" %*
exit /b %ERRORLEVEL%
