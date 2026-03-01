@echo off
echo USB Monitoring Service - Windows Installation
echo ============================================

REM Check for admin privileges
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo Error: This script requires administrator privileges
    echo Please run as administrator
    pause
    exit /b 1
)

echo Installing Python dependencies...
cd client
pip install -r requirements.txt

echo Creating Windows Service using NSSM...
echo.
echo Please download NSSM from https://nssm.cc/download
echo Extract nssm.exe to this directory or add to PATH
echo.
pause

REM Install service using NSSM (Non-Sucking Service Manager)
nssm install USBMonitor "%CD%\venv\Scripts\python.exe" "%CD%\usb_monitor_client.py"
nssm set USBMonitor AppDirectory "%CD%"
nssm set USBMonitor DisplayName "USB Monitoring Client"
nssm set USBMonitor Description "Monitors USB drive events and sends to central server"
nssm set USBMonitor Start SERVICE_AUTO_START

echo Service installed successfully!
echo Use 'nssm start USBMonitor' to start the service
echo Use 'nssm stop USBMonitor' to stop the service
echo Use 'nssm remove USBMonitor confirm' to uninstall

pause