@echo off
setlocal
set "vswhere=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
set "vsdir="
for /f "usebackq delims=" %%i in (`"%vswhere%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "vsdir=%%i"
if not defined vsdir (
    echo Visual Studio x64 C++ Build Tools and a Windows SDK are required. 1>&2
    exit /b 1
)
call "%vsdir%\VC\Auxiliary\Build\vcvars64.bat" || exit /b 1
cl /nologo /LD /MT /O2 /EHsc /std:c++20 /utf-8 "%~dp0reply-toast.cpp" /link /OUT:chatbox_toast.dll windowsapp.lib
if errorlevel 1 exit /b %errorlevel%
cl /nologo /MT /O2 /EHsc /std:c++20 /utf-8 "%~dp0heart-rate.cpp" /link /OUT:chatbox_heart.exe windowsapp.lib
exit /b %errorlevel%
