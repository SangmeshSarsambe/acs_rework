"""
Main USB monitoring client application
"""
import os
import sys
import yaml
import logging
import signal
import platform
from pathlib import Path
from typing import Dict, Any

from logger_config import setup_logger
from api_client import APIClient
from event_detector import USBDetector

class USBMonitorClient:
    """Main USB monitoring client"""
    
    def __init__(self, config_path: str = "config.yaml"):
        """
        Initialize USB monitor client
        
        Args:
            config_path: Path to configuration file
        """
        # Load configuration
        self.config = self._load_config(config_path)
        
        # Setup logging
        self.logger = setup_logger(
            log_file=self.config['logging']['file'],
            log_level=self.config['logging']['level'],
            max_size=self.config['logging']['max_size'],
            backup_count=self.config['logging']['backup_count']
        )
        
        self.logger.info("="*60)
        self.logger.info("USB Monitoring Client Starting")
        self.logger.info("="*60)
        
        # Get device ID
        self.device_id = self._get_device_id()
        self.logger.info(f"Device ID: {self.device_id}")
        
        # Get username
        self.username = self._get_username()
        self.logger.info(f"Username: {self.username}")
        
        # Initialize API client
        self.api_client = APIClient(
            server_url=self.config['server']['url'],
            endpoint=self.config['server']['endpoint'],
            timeout=self.config['server']['timeout'],
            retry_attempts=self.config['server']['retry_attempts'],
            retry_delay=self.config['server']['retry_delay']
        )
        
        # Test server connection
        if self.api_client.test_connection():
            self.logger.info("Successfully connected to server")
        else:
            self.logger.warning("Could not connect to server. Events will be queued.")
        
        # Initialize USB detector
        self.usb_detector = USBDetector(
            device_id=self.device_id,
            username=self.username,
            event_callback=self._handle_usb_event,
            excluded_extensions=self.config['monitoring'].get('excluded_extensions', []),
            track_file_access=self.config['monitoring'].get('track_file_open', True)  # Add this line
        )
        
        # Setup signal handlers
        signal.signal(signal.SIGINT, self._signal_handler)
        signal.signal(signal.SIGTERM, self._signal_handler)
    
    def _load_config(self, config_path: str) -> Dict[str, Any]:
        """
        Load configuration from YAML file
        
        Args:
            config_path: Path to config file
            
        Returns:
            Configuration dictionary
        """
        try:
            with open(config_path, 'r') as f:
                config = yaml.safe_load(f)
            return config
        except Exception as e:
            print(f"Error loading configuration: {e}")
            sys.exit(1)
    
    def _get_device_id(self) -> str:
        """
        Get device identifier
        
        Returns:
            Device ID string
        """
        device_id = self.config['client']['device_id']
        
        if device_id == "auto":
            # Auto-detect device ID
            device_id = platform.node()  # Hostname
        
        return device_id
    
    def _get_username(self) -> str:
        """
        Get current system username
        
        Returns:
            Username string
        """
        try:
            return os.getlogin()
        except:
            return os.environ.get('USERNAME') or os.environ.get('USER') or 'unknown'
    
    def _handle_usb_event(self, event_data: Dict[str, Any]):
        """
        Handle USB event and send to server
        
        Args:
            event_data: Event data dictionary
        """
        self.logger.info(
            f"Event: {event_data['event_type']} - "
            f"{event_data['usb_drive']} - "
            f"{event_data.get('file_name', 'N/A')}"
        )
        
        # Send to server
        self.api_client.send_event(event_data)
    
    def _signal_handler(self, signum, frame):
        """Handle shutdown signals"""
        self.logger.info(f"Received signal {signum}, shutting down...")
        self.shutdown()
        sys.exit(0)
    
    def start(self):
        """Start USB monitoring"""
        self.logger.info("Starting USB monitoring...")
        
        poll_interval = self.config['client']['poll_interval']
        self.usb_detector.start_monitoring(poll_interval=poll_interval)
        
        self.logger.info(f"Monitoring active (poll interval: {poll_interval}s)")
        
        # Keep main thread alive
        try:
            while True:
                import time
                time.sleep(1)
        except KeyboardInterrupt:
            self.shutdown()
    
    def shutdown(self):
        """Shutdown client gracefully"""
        self.logger.info("Shutting down USB monitoring client...")
        
        # Stop USB detection
        self.usb_detector.stop_monitoring_service()
        
        # Stop API client
        self.api_client.shutdown()
        
        self.logger.info("Shutdown complete")


if __name__ == "__main__":
    # Get config path from command line or use default
    config_path = sys.argv[1] if len(sys.argv) > 1 else "config.yaml"
    
    # Create and start client
    client = USBMonitorClient(config_path=config_path)
    client.start()