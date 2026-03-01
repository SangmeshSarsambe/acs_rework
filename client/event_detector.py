"""
USB event detection for Windows and Linux
"""
import os
import sys
import time
import platform
import logging
import psutil
from datetime import datetime
from typing import Dict, List, Optional, Callable
from threading import Thread, Event
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler

logger = logging.getLogger("USBMonitor.EventDetector")

# Platform-specific imports
if platform.system() == "Windows":
    try:
        import win32file
        import win32con
        import wmi
    except ImportError:
        logger.error("Windows-specific libraries not available. Install pywin32 and WMI.")
        sys.exit(1)
elif platform.system() == "Linux":
    try:
        import pyudev
    except ImportError:
        logger.error("Linux-specific library pyudev not available.")
        sys.exit(1)


class USBFileEventHandler(FileSystemEventHandler):
    """Watchdog handler for file system events on USB drives"""
    
    def __init__(self, usb_drive: str, callback: Callable, username: str, 
                 device_id: str, excluded_extensions: List[str]):
        """
        Initialize file event handler
        
        Args:
            usb_drive: USB drive path
            callback: Callback function for events
            username: System username
            device_id: Device identifier
            excluded_extensions: File extensions to ignore
        """
        self.usb_drive = usb_drive
        self.callback = callback
        self.username = username
        self.device_id = device_id
        self.excluded_extensions = excluded_extensions
    
    def _should_ignore(self, file_path: str) -> bool:
        """Check if file should be ignored"""
        # Ignore system files and folders
        filename = os.path.basename(file_path)
        if filename.startswith('.') or filename.startswith('~$'):
            return True
        
        for ext in self.excluded_extensions:
            if file_path.endswith(ext):
                return True
        return False
    
    def on_created(self, event):
        """Handle file creation event"""
        if event.is_directory or self._should_ignore(event.src_path):
            return
        
        # Get file size
        try:
            file_size = os.path.getsize(event.src_path)
            file_size_str = self._format_file_size(file_size)
        except:
            file_size_str = "Unknown"
        
        self.callback({
            'device_id': self.device_id,
            'timestamp': datetime.utcnow(),
            'usb_drive': self.usb_drive,
            'event_type': 'file_created',
            'file_path': event.src_path,
            'file_name': os.path.basename(event.src_path),
            'action': f'File created: {event.src_path} (Size: {file_size_str})',
            'username': self.username
        })
    
    def on_deleted(self, event):
        """Handle file deletion event"""
        if event.is_directory or self._should_ignore(event.src_path):
            return
        
        self.callback({
            'device_id': self.device_id,
            'timestamp': datetime.utcnow(),
            'usb_drive': self.usb_drive,
            'event_type': 'file_deleted',
            'file_path': event.src_path,
            'file_name': os.path.basename(event.src_path),
            'action': f'File deleted: {event.src_path}',
            'username': self.username
        })
    
    def on_modified(self, event):
        """Handle file modification event"""
        if event.is_directory or self._should_ignore(event.src_path):
            return
        
        # Get file size
        try:
            file_size = os.path.getsize(event.src_path)
            file_size_str = self._format_file_size(file_size)
        except:
            file_size_str = "Unknown"
        
        self.callback({
            'device_id': self.device_id,
            'timestamp': datetime.utcnow(),
            'usb_drive': self.usb_drive,
            'event_type': 'file_modified',
            'file_path': event.src_path,
            'file_name': os.path.basename(event.src_path),
            'action': f'File modified: {event.src_path} (Size: {file_size_str})',
            'username': self.username
        })
    
    def on_moved(self, event):
        """Handle file move/rename event"""
        if event.is_directory or self._should_ignore(event.src_path):
            return
        
        self.callback({
            'device_id': self.device_id,
            'timestamp': datetime.utcnow(),
            'usb_drive': self.usb_drive,
            'event_type': 'file_renamed',
            'file_path': event.src_path,
            'file_name': os.path.basename(event.dest_path),
            'action': f'File renamed: {event.src_path} → {event.dest_path}',
            'username': self.username
        })
    
    def _format_file_size(self, size_bytes: int) -> str:
        """Format file size in human-readable format"""
        for unit in ['B', 'KB', 'MB', 'GB']:
            if size_bytes < 1024.0:
                return f"{size_bytes:.2f} {unit}"
            size_bytes /= 1024.0
        return f"{size_bytes:.2f} TB"


class USBDetector:
    """Cross-platform USB detection and monitoring"""
    
    def __init__(self, device_id: str, username: str, event_callback: Callable,
                 excluded_extensions: List[str], track_file_access: bool = True):
        """
        Initialize USB detector
        
        Args:
            device_id: Device identifier
            username: System username
            event_callback: Callback for USB events
            excluded_extensions: File extensions to ignore
            track_file_access: Enable file open/close tracking
        """
        self.device_id = device_id
        self.username = username
        self.event_callback = event_callback
        self.excluded_extensions = excluded_extensions
        self.track_file_access = track_file_access
        self.platform = platform.system()
        
        # Track mounted USB drives
        self.mounted_drives: Dict[str, Observer] = {}
        
        # File access monitor (only on Windows)
        self.file_access_monitor = None
        if self.platform == "Windows" and track_file_access:
            try:
                from file_access_monitor import FileAccessMonitor
                self.file_access_monitor = FileAccessMonitor(
                    device_id=device_id,
                    username=username,
                    event_callback=event_callback
                )
                logger.info("File access monitoring enabled")
            except Exception as e:
                logger.warning(f"Could not enable file access monitoring: {e}")
        
        # Monitoring thread
        self.monitor_thread = None
        self.stop_monitoring = Event()
        
        logger.info(f"USB Detector initialized for {self.platform}")
    
    def get_username(self) -> str:
        """Get current system username"""
        try:
            return os.getlogin()
        except:
            return os.environ.get('USERNAME') or os.environ.get('USER') or 'unknown'
    
    def _is_usb_drive_windows(self, drive: str) -> bool:
        """
        Check if drive is a USB drive on Windows
        
        Args:
            drive: Drive letter (e.g., 'E:')
            
        Returns:
            True if USB drive, False otherwise
        """
        try:
            drive_type = win32file.GetDriveType(drive)
            if drive_type == win32con.DRIVE_REMOVABLE:
                return True
            
            # Alternative check using WMI
            c = wmi.WMI()
            for disk in c.Win32_DiskDrive():
                if 'USB' in disk.InterfaceType or 'USB' in disk.PNPDeviceID:
                    for partition in disk.associators("Win32_DiskDriveToDiskPartition"):
                        for logical_disk in partition.associators("Win32_LogicalDiskToPartition"):
                            if logical_disk.DeviceID == drive:
                                return True
        except Exception as e:
            logger.debug(f"Error checking USB drive {drive}: {e}")
        
        return False
    
    def _is_usb_drive_linux(self, mount_point: str) -> bool:
        """
        Check if mount point is a USB drive on Linux
        
        Args:
            mount_point: Mount point path
            
        Returns:
            True if USB drive, False otherwise
        """
        try:
            context = pyudev.Context()
            for device in context.list_devices(subsystem='block', DEVTYPE='partition'):
                if device.get('ID_BUS') == 'usb':
                    # Check if this device is mounted at the mount point
                    for partition in psutil.disk_partitions():
                        if partition.device == device.device_node and partition.mountpoint == mount_point:
                            return True
        except Exception as e:
            logger.debug(f"Error checking USB drive {mount_point}: {e}")
        
        return False
    
    def get_usb_drives(self) -> List[str]:
        """
        Get list of currently mounted USB drives
        
        Returns:
            List of USB drive paths
        """
        usb_drives = []
        
        if self.platform == "Windows":
            # Get all drives
            drives = [f"{chr(d)}:\\" for d in range(65, 91) if os.path.exists(f"{chr(d)}:\\")]
            
            for drive in drives:
                if self._is_usb_drive_windows(drive.rstrip('\\')):
                    usb_drives.append(drive)
        
        elif self.platform == "Linux":
            # Get all mount points
            partitions = psutil.disk_partitions()
            
            for partition in partitions:
                # Check if it's a removable drive
                if self._is_usb_drive_linux(partition.mountpoint):
                    usb_drives.append(partition.mountpoint)
        
        return usb_drives
    
    def start_file_monitoring(self, drive_path: str):
        """
        Start file system monitoring for a USB drive
        
        Args:
            drive_path: Path to USB drive
        """
        try:
            # Create event handler
            event_handler = USBFileEventHandler(
                usb_drive=drive_path,
                callback=self.event_callback,
                username=self.username,
                device_id=self.device_id,
                excluded_extensions=self.excluded_extensions
            )
            
            # Create and start observer
            observer = Observer()
            observer.schedule(event_handler, drive_path, recursive=True)
            observer.start()
            
            # Store observer
            self.mounted_drives[drive_path] = observer
            
            logger.info(f"Started file monitoring for {drive_path}")
            
            # Start file access monitoring if enabled
            if self.file_access_monitor:
                self.file_access_monitor.start_monitoring(drive_path)
        
        except Exception as e:
            logger.error(f"Failed to start file monitoring for {drive_path}: {e}")
    
    def stop_file_monitoring(self, drive_path: str):
        """
        Stop file system monitoring for a USB drive
        
        Args:
            drive_path: Path to USB drive
        """
        if drive_path in self.mounted_drives:
            try:
                observer = self.mounted_drives[drive_path]
                observer.stop()
                observer.join(timeout=5)
                del self.mounted_drives[drive_path]
                
                logger.info(f"Stopped file monitoring for {drive_path}")
                
                # Stop file access monitoring if enabled
                if self.file_access_monitor:
                    self.file_access_monitor.stop_monitoring(drive_path)
            
            except Exception as e:
                logger.error(f"Failed to stop file monitoring for {drive_path}: {e}")
    
    def _monitoring_loop(self, poll_interval: int):
        """
        Main monitoring loop
        
        Args:
            poll_interval: Seconds between USB checks
        """
        logger.info("USB monitoring loop started")
        previous_drives = set()
        
        while not self.stop_monitoring.is_set():
            try:
                # Get current USB drives
                current_drives = set(self.get_usb_drives())
                
                # Detect inserted drives
                inserted = current_drives - previous_drives
                for drive in inserted:
                    logger.info(f"USB drive inserted: {drive}")
                    
                    # Get drive info
                    drive_info = self._get_drive_info(drive)
                    
                    # Send insertion event
                    self.event_callback({
                        'device_id': self.device_id,
                        'timestamp': datetime.utcnow(),
                        'usb_drive': drive,
                        'event_type': 'usb_inserted',
                        'file_name': None,
                        'action': f'USB drive inserted: {drive} {drive_info}',
                        'username': self.username
                    })
                    
                    # Start file monitoring
                    self.start_file_monitoring(drive)
                
                # Detect removed drives
                removed = previous_drives - current_drives
                for drive in removed:
                    logger.info(f"USB drive removed: {drive}")
                    
                    # Stop file monitoring
                    self.stop_file_monitoring(drive)
                    
                    # Send removal event
                    self.event_callback({
                        'device_id': self.device_id,
                        'timestamp': datetime.utcnow(),
                        'usb_drive': drive,
                        'event_type': 'usb_removed',
                        'file_name': None,
                        'action': f'USB drive removed: {drive}',
                        'username': self.username
                    })
                
                previous_drives = current_drives
                
                # Sleep before next check
                time.sleep(poll_interval)
            
            except Exception as e:
                logger.error(f"Error in monitoring loop: {e}")
                time.sleep(poll_interval)
        
        logger.info("USB monitoring loop stopped")
    
    def _get_drive_info(self, drive_path: str) -> str:
        """Get drive information (label, size, etc.)"""
        try:
            if self.platform == "Windows":
                import win32api
                volume_info = win32api.GetVolumeInformation(drive_path)
                label = volume_info[0] or "No Label"
                
                # Get drive size
                usage = psutil.disk_usage(drive_path)
                total_size = usage.total / (1024**3)  # Convert to GB
                
                return f"[{label}, {total_size:.2f} GB]"
            else:
                usage = psutil.disk_usage(drive_path)
                total_size = usage.total / (1024**3)
                return f"[{total_size:.2f} GB]"
        except:
            return ""
    
    def start_monitoring(self, poll_interval: int = 2):
        """
        Start USB monitoring
        
        Args:
            poll_interval: Seconds between USB checks
        """
        if self.monitor_thread and self.monitor_thread.is_alive():
            logger.warning("Monitoring already started")
            return
        
        self.stop_monitoring.clear()
        self.monitor_thread = Thread(
            target=self._monitoring_loop,
            args=(poll_interval,),
            daemon=True
        )
        self.monitor_thread.start()
        
        logger.info("USB monitoring started")
    
    def stop_monitoring_service(self):
        """Stop USB monitoring"""
        logger.info("Stopping USB monitoring...")
        
        # Signal monitoring thread to stop
        self.stop_monitoring.set()
        
        # Stop all file observers
        for drive in list(self.mounted_drives.keys()):
            self.stop_file_monitoring(drive)
        
        # Stop file access monitor
        if self.file_access_monitor:
            self.file_access_monitor.stop_all()
        
        # Wait for monitoring thread
        if self.monitor_thread:
            self.monitor_thread.join(timeout=10)
        
        logger.info("USB monitoring stopped")