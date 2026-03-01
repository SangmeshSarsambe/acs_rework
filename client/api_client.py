"""
API client for communicating with the USB monitoring server
"""
import requests
import json
import time
import logging
from typing import Dict, Any, Optional
from datetime import datetime
from queue import Queue
from threading import Thread, Event

logger = logging.getLogger("USBMonitor.APIClient")

class APIClient:
    """Handles communication with the monitoring server"""
    
    def __init__(self, server_url: str, endpoint: str, timeout: int, 
                 retry_attempts: int, retry_delay: int):
        """
        Initialize API client
        
        Args:
            server_url: Base URL of the server
            endpoint: API endpoint path
            timeout: Request timeout in seconds
            retry_attempts: Number of retry attempts on failure
            retry_delay: Delay between retries in seconds
        """
        self.server_url = server_url.rstrip('/')
        self.endpoint = endpoint
        self.timeout = timeout
        self.retry_attempts = retry_attempts
        self.retry_delay = retry_delay
        
        # Queue for failed events
        self.failed_events_queue = Queue()
        self.retry_thread = None
        self.stop_retry_thread = Event()
        
        # Start retry thread
        self._start_retry_thread()
    
    def _start_retry_thread(self):
        """Start background thread to retry failed events"""
        self.retry_thread = Thread(target=self._retry_worker, daemon=True)
        self.retry_thread.start()
        logger.info("Retry thread started")
    
    def _retry_worker(self):
        """Background worker to retry failed events"""
        while not self.stop_retry_thread.is_set():
            try:
                if not self.failed_events_queue.empty():
                    event_data = self.failed_events_queue.get()
                    logger.info(f"Retrying failed event: {event_data.get('event_type')}")
                    
                    success = self._send_event_internal(event_data, retry=False)
                    
                    if not success:
                        # Put back in queue if still failing
                        self.failed_events_queue.put(event_data)
                        time.sleep(self.retry_delay)
                
                time.sleep(1)  # Small delay to prevent tight loop
            
            except Exception as e:
                logger.error(f"Error in retry worker: {e}")
                time.sleep(5)
    
    def _send_event_internal(self, event_data: Dict[str, Any], retry: bool = True) -> bool:
        """
        Internal method to send event to server
        
        Args:
            event_data: Event data dictionary
            retry: Whether to queue for retry on failure
            
        Returns:
            True if successful, False otherwise
        """
        url = f"{self.server_url}{self.endpoint}"
        
        for attempt in range(self.retry_attempts):
            try:
                # Ensure timestamp is ISO format string
                if isinstance(event_data.get('timestamp'), datetime):
                    event_data['timestamp'] = event_data['timestamp'].isoformat()
                
                response = requests.post(
                    url,
                    json=event_data,
                    timeout=self.timeout,
                    headers={'Content-Type': 'application/json'}
                )
                
                if response.status_code in [200, 201]:
                    logger.info(
                        f"Event sent successfully: {event_data.get('event_type')} "
                        f"- {event_data.get('usb_drive')}"
                    )
                    return True
                else:
                    logger.warning(
                        f"Server returned status {response.status_code}: "
                        f"{response.text}"
                    )
            
            except requests.exceptions.ConnectionError:
                logger.warning(
                    f"Connection failed (attempt {attempt + 1}/{self.retry_attempts}). "
                    f"Server may be offline."
                )
            
            except requests.exceptions.Timeout:
                logger.warning(
                    f"Request timeout (attempt {attempt + 1}/{self.retry_attempts})"
                )
            
            except Exception as e:
                logger.error(f"Unexpected error sending event: {e}")
            
            # Wait before retry
            if attempt < self.retry_attempts - 1:
                time.sleep(self.retry_delay)
        
        # All attempts failed
        if retry:
            logger.warning("All attempts failed. Queueing event for later retry.")
            self.failed_events_queue.put(event_data)
        
        return False
    
    def send_event(self, event_data: Dict[str, Any]):
        """
        Send event to server (non-blocking)
        
        Args:
            event_data: Event data dictionary
        """
        # Send in separate thread to avoid blocking
        Thread(
            target=self._send_event_internal,
            args=(event_data.copy(),),
            daemon=True
        ).start()
    
    def test_connection(self) -> bool:
        """
        Test connection to server
        
        Returns:
            True if server is reachable, False otherwise
        """
        try:
            response = requests.get(
                f"{self.server_url}/health",
                timeout=self.timeout
            )
            if response.status_code == 200:
                logger.info("Server connection test successful")
                return True
        except Exception as e:
            logger.warning(f"Server connection test failed: {e}")
        
        return False
    
    def shutdown(self):
        """Shutdown API client and retry thread"""
        logger.info("Shutting down API client...")
        self.stop_retry_thread.set()
        if self.retry_thread:
            self.retry_thread.join(timeout=5)
        logger.info("API client shutdown complete")