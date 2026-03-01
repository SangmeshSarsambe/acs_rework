#!/bin/bash

# USB Monitoring Service - Linux Installation

echo "USB Monitoring Service - Linux Installation"
echo "============================================"

# Check for root privileges
if [ "$EUID" -ne 0 ]; then
    echo "Error: This script must be run as root"
    echo "Please run: sudo $0"
    exit 1
fi

# Get the actual user (not root)
ACTUAL_USER=${SUDO_USER:-$USER}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT_DIR="$SCRIPT_DIR/client"

echo "Installing Python dependencies..."
cd "$CLIENT_DIR"
pip3 install -r requirements.txt

# Create systemd service file
SERVICE_FILE="/etc/systemd/system/usb-monitor.service"

echo "Creating systemd service..."
cat > "$SERVICE_FILE" << EOF
[Unit]
Description=USB Monitoring Client
After=network.target

[Service]
Type=simple
User=$ACTUAL_USER
WorkingDirectory=$CLIENT_DIR
ExecStart=/usr/bin/python3 $CLIENT_DIR/usb_monitor_client.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Reload systemd
echo "Reloading systemd..."
systemctl daemon-reload

# Enable service
echo "Enabling service..."
systemctl enable usb-monitor.service

echo ""
echo "Service installed successfully!"
echo ""
echo "Commands:"
echo "  Start:   sudo systemctl start usb-monitor"
echo "  Stop:    sudo systemctl stop usb-monitor"
echo "  Status:  sudo systemctl status usb-monitor"
echo "  Logs:    sudo journalctl -u usb-monitor -f"
echo ""