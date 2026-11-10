@echo off
chcp 65001 >nul 2>&1
setlocal
cd /d "%~dp0"
title GcmodAi 助手启动器

echo ============================================================
echo   GcmodAi Windows 助手   启动器
echo ============================================================
echo.

set "EXE=%~dp0GcmodAi-Agent.exe"
if not exist "%EXE%" goto no_exe

echo [1/3] 解除"来自网络"标记（这一步用来绕过 SmartScreen 拦截）...
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Unblock-File -LiteralPath '%EXE%' -ErrorAction Stop; exit 0 } catch { exit 1 }"
if errorlevel 1 (echo       跳过：解除失败，可忽略) else (echo       完成)
echo.

echo [2/3] 尝试放行防火墙端口 8787（无管理员权限时自动跳过）...
netsh advfirewall firewall add rule name="GcmodAi Agent" dir=in action=allow protocol=TCP localport=8787 >nul 2>&1
if errorlevel 1 (
  echo       未放行：不弹管理员框，避免卡住。
  echo       如果手机连不上，请用管理员身份执行下面这条：
  echo       netsh advfirewall firewall add rule name="GcmodAi Agent" dir=in action=allow protocol=TCP localport=8787
) else (
  echo       已放行
)
echo.

echo [3/3] 启动助手...
start "" "%EXE%"
echo.
echo 已启动。助手窗口里会显示"地址"和"口令"，填进手机 App：
echo   设置 - 工作区与建站 - Windows 电脑 - 右下角 +
echo.
echo 本窗口 5 秒后自动关闭（关掉不影响助手运行）。
ping -n 6 127.0.0.1 >nul 2>&1
exit /b 0

:no_exe
echo [错误] 没有找到 GcmodAi-Agent.exe
echo.
echo 请检查：
echo   1. 压缩包是否已经"解压"（不要在压缩包里直接运行）
echo   2. 本文件和 GcmodAi-Agent.exe 是否在同一个文件夹
echo.
echo 当前文件夹：%~dp0
echo.
pause
exit /b 1
