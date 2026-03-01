"""
Data models for USB monitoring events
"""
from pydantic import BaseModel, Field
from datetime import datetime
from typing import Optional

class USBEvent(BaseModel):
    """USB event data model"""
    
    device_id: str = Field(..., description="Unique identifier for the client device")
    timestamp: datetime = Field(default_factory=datetime.utcnow, description="Event timestamp")
    usb_drive: str = Field(..., description="USB drive identifier (e.g., E:, /dev/sdb1)")
    event_type: str = Field(..., description="Type of event (insert, remove, file_created, etc.)")
    file_name: Optional[str] = Field(None, description="File name if applicable")
    file_path: Optional[str] = Field(None, description="Full file path if applicable")
    action: Optional[str] = Field(None, description="Detailed action description")
    username: str = Field(..., description="Username of the system user")
    
    class Config:
        json_schema_extra = {
            "example": {
                "device_id": "DESKTOP-ABC123",
                "timestamp": "2024-02-08T10:30:00",
                "usb_drive": "E:",
                "event_type": "file_created",
                "file_name": "document.txt",
                "file_path": "E:\\Documents\\document.txt",
                "action": "File created on USB drive",
                "username": "john_doe"
            }
        }

class EventResponse(BaseModel):
    """Response model for event submission"""
    
    status: str
    message: str
    event_id: Optional[str] = None