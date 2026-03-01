"""
File access monitoring for tracking file open/close events
Windows implementation using Windows API
"""
import os
import sys
import time
import logging
import threading
from datetime import datetime
from typing import Dict, Callable, Set
from collections import defaultdict

if sys.platform == 'win32':
    import win32file
    import win32con
    import win32api
    import win32security
    import wmi

logger = logging.getLogger("USBMonitor.FileAccessMonitor")


class FileAccessMonitor:
    """Monitor file open/close events on USB drives"""
    
    def __init__(self, device_id: str, username: str, event_callback: Callable):
        """
        Initialize file access monitor
        
        Args:
            device_id: Device identifier
            username: System username
            event_callback: Callback for file access events
        """
        self.device_id = device_id
        self.username = username
        self.event_callback = event_callback
        
        # Track which drives are being monitored
        self.monitored_drives: Dict[str, threading.Thread] = {}
        self.stop_events: Dict[str, threading.Event] = {}
        
        # Track open files to detect close events
        self.open_files: Dict[str, Set[str]] = defaultdict(set)
        
        logger.info("File Access Monitor initialized")
    
    def _get_open_files_on_drive(self, drive_path: str) -> Set[str]:
        """
        Get list of currently open files on a drive using WMI
        
        Args:
            drive_path: Drive path (e.g., 'E:\\')
            
        Returns:
            Set of open file paths
        """
        open_files = set()
        
        try:
            c = wmi.WMI()
            
            # Query for processes with open file handles
            for process in c.Win32_Process():
                try:
                    # Get process ID
                    pid = process.ProcessId
                    
                    # Get process name for logging
                    process_name = process.Name
                    
                    # Try to enumerate handles (this is limited without admin rights)
                    # We'll use a different approach - check CIM_DataFile
                except:
                    continue
            
            # Alternative approach: Use CIM_DataFile to find files in use
            # This checks for files that are currently locked
            drive_letter = drive_path.rstrip('\\').replace(':', '')
            
            for file in c.CIM_DataFile(Drive=f"{drive_letter}:"):
                try:
                    file_path = file.Name
                    
                    # Try to open file exclusively - if fails, it's in use
                    try:
                        handle = win32file.CreateFile(
                            file_path,
                            win32con.GENERIC_READ,
                            0,  # No sharing - exclusive access
                            None,
                            win32con.OPEN_EXISTING,
                            win32con.FILE_ATTRIBUTE_NORMAL,
                            None
                        )
                        # If we got here, file is NOT open by another process
                        win32file.CloseHandle(handle)
                    except:
                        # File is open by another process
                        open_files.add(file_path)
                except:
                    continue
        
        except Exception as e:
            logger.debug(f"Error getting open files: {e}")
        
        return open_files
    
    def _monitor_drive_access(self, drive_path: str, stop_event: threading.Event):
    """
    Monitor file access events on a specific drive
    
    Args:
        drive_path: Drive path to monitor
        stop_event: Event to signal thread stop
    """
    logger.info(f"Started file access monitoring for {drive_path}")
    
    previous_open_files = set()
    check_interval = 2  # Check every 2 seconds
    
    while not stop_event.is_set():
        try:
            # Get currently open files
            current_open_files = self._get_open_files_on_drive(drive_path)
            
            # Detect newly opened files
            newly_opened = current_open_files - previous_open_files
            for file_path in newly_opened:
                logger.info(f"File opened: {file_path}")
                
                self.event_callback({
                    'device_id': self.device_id,
                    'timestamp': datetime.utcnow(),
                    'usb_drive': drive_path,
                    'event_type': 'file_opened',
                    'file_path': file_path,
                    'file_name': os.path.basename(file_path),
                    'file_path': file_path,  # CHANGED: Added this line
                    'action': f'File opened: {file_path}',
                    'username': self.username
                })
            
            # Detect closed files
            newly_closed = previous_open_files - current_open_files
            for file_path in newly_closed:
                logger.info(f"File closed: {file_path}")
                
                self.event_callback({
                    'device_id': self.device_id,
                    'timestamp': datetime.utcnow(),
                    'usb_drive': drive_path,
                    'event_type': 'file_closed',
                    'file_path': file_path,
                    'file_name': os.path.basename(file_path),
                    'file_path': file_path,  # CHANGED: Added this line
                    'action': f'File closed: {file_path}',
                    'username': self.username
                })
            
            previous_open_files = current_open_files
            
            # Wait before next check
            time.sleep(check_interval)
        
        except Exception as e:
            logger.error(f"Error in file access monitoring: {e}")
            time.sleep(check_interval)
    
    logger.info(f"Stopped file access monitoring for {drive_path}")
    
    def start_monitoring(self, drive_path: str):
        """
        Start monitoring file access on a drive
        
        Args:
            drive_path: Drive path to monitor
        """
        if drive_path in self.monitored_drives:
            logger.warning(f"Already monitoring {drive_path}")
            return
        
        try:
            # Create stop event
            stop_event = threading.Event()
            self.stop_events[drive_path] = stop_event
            
            # Create and start monitoring thread
            monitor_thread = threading.Thread(
                target=self._monitor_drive_access,
                args=(drive_path, stop_event),
                daemon=True
            )
            monitor_thread.start()
            
            self.monitored_drives[drive_path] = monitor_thread
            
            logger.info(f"Started file access monitoring for {drive_path}")
        
        except Exception as e:
            logger.error(f"Failed to start file access monitoring for {drive_path}: {e}")
    
    def stop_monitoring(self, drive_path: str):
        """
        Stop monitoring file access on a drive
        
        Args:
            drive_path: Drive path to stop monitoring
        """
        if drive_path not in self.monitored_drives:
            return
        
        try:
            # Signal thread to stop
            self.stop_events[drive_path].set()
            
            # Wait for thread to finish
            self.monitored_drives[drive_path].join(timeout=5)
            
            # Cleanup
            del self.monitored_drives[drive_path]
            del self.stop_events[drive_path]
            if drive_path in self.open_files:
                del self.open_files[drive_path]
            
            logger.info(f"Stopped file access monitoring for {drive_path}")
        
        except Exception as e:
            logger.error(f"Error stopping file access monitoring for {drive_path}: {e}")
    
    def stop_all(self):
        """Stop monitoring all drives"""
        for drive_path in list(self.monitored_drives.keys()):
            self.stop_monitoring(drive_path)