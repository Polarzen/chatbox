@echo off
setlocal
set "CHATBOX_UNINSTALL=%~dp0Uninstall-ChatBox.ps1"
rem Leave the installation directory before Windows Installer removes its files.
cd /d "%SystemRoot%"
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%CHATBOX_UNINSTALL%"
set "CHATBOX_UNINSTALL_EXIT=%ERRORLEVEL%"
if not "%CHATBOX_UNINSTALL_EXIT%"=="0" if not "%CHATBOX_UNINSTALL_EXIT%"=="2" pause
exit /b %CHATBOX_UNINSTALL_EXIT%
