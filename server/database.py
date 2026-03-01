"""
Database operations using SQLite
"""
import aiosqlite
import json
import logging
from typing import Optional, List, Dict, Any
from datetime import datetime
from pathlib import Path

logger = logging.getLogger(__name__)

class Database:
    """SQLite database handler"""
    
    def __init__(self, db_path: str):
        """
        Initialize database connection
        
        Args:
            db_path: Path to SQLite database file
        """
        self.db_path = db_path
        self.db: Optional[aiosqlite.Connection] = None
    
    async def connect(self):
        """Establish database connection and create tables"""
        try:
            # Create database file if it doesn't exist
            Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
            
            # Connect to database
            self.db = await aiosqlite.connect(self.db_path)
            self.db.row_factory = aiosqlite.Row
            
            # Create tables
            await self._create_tables()
            
            logger.info(f"Connected to SQLite database at {self.db_path}")
        except Exception as e:
            logger.error(f"Failed to connect to database: {e}")
            raise
    
    async def _create_tables(self):
        """Create database tables if they don't exist"""
        await self.db.execute("""
            CREATE TABLE IF NOT EXISTS usb_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device_id TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                usb_drive TEXT NOT NULL,
                event_type TEXT NOT NULL,
                file_name TEXT,
                file_path TEXT,
                action TEXT,
                username TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)
        
        # Create indexes for better query performance
        await self.db.execute("""
            CREATE INDEX IF NOT EXISTS idx_device_id 
            ON usb_events(device_id)
        """)
        
        await self.db.execute("""
            CREATE INDEX IF NOT EXISTS idx_timestamp 
            ON usb_events(timestamp)
        """)
        
        await self.db.execute("""
            CREATE INDEX IF NOT EXISTS idx_event_type 
            ON usb_events(event_type)
        """)
        
        await self.db.commit()
        logger.info("Database tables created/verified")
    
    async def disconnect(self):
        """Close database connection"""
        if self.db:
            await self.db.close()
            logger.info("Disconnected from database")
    
    async def insert_event(self, event_data: Dict[str, Any]) -> int:
        """
        Insert USB event into database
        
        Args:
            event_data: Event data dictionary
            
        Returns:
            Inserted row ID
        """
        try:
            # Convert timestamp to ISO format string if it's a datetime object
            timestamp = event_data.get('timestamp')
            if isinstance(timestamp, datetime):
                timestamp = timestamp.isoformat()
            
            cursor = await self.db.execute("""
                INSERT INTO usb_events 
                (device_id, timestamp, usb_drive, event_type, file_name, file_path, action, username)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, (
                event_data.get('device_id'),
                timestamp,
                event_data.get('usb_drive'),
                event_data.get('event_type'),
                event_data.get('file_name'),
                event_data.get('file_path'),
                event_data.get('action'),
                event_data.get('username')
            ))
            
            await self.db.commit()
            
            logger.info(f"Event inserted with ID: {cursor.lastrowid}")
            return cursor.lastrowid
        
        except Exception as e:
            logger.error(f"Failed to insert event: {e}")
            raise
    
    async def get_events(
        self, 
        device_id: Optional[str] = None,
        limit: int = 100
    ) -> List[Dict[str, Any]]:
        """
        Retrieve USB events from database
        
        Args:
            device_id: Filter by device ID (optional)
            limit: Maximum number of events to retrieve
            
        Returns:
            List of events
        """
        try:
            if device_id:
                cursor = await self.db.execute("""
                    SELECT * FROM usb_events 
                    WHERE device_id = ?
                    ORDER BY timestamp DESC 
                    LIMIT ?
                """, (device_id, limit))
            else:
                cursor = await self.db.execute("""
                    SELECT * FROM usb_events 
                    ORDER BY timestamp DESC 
                    LIMIT ?
                """, (limit,))
            
            rows = await cursor.fetchall()
            
            # Convert rows to dictionaries
            events = []
            for row in rows:
                events.append({
                    "id": row["id"],
                    "device_id": row["device_id"],
                    "timestamp": row["timestamp"],
                    "usb_drive": row["usb_drive"],
                    "event_type": row["event_type"],
                    "file_name": row["file_name"],
                    "file_path": row["file_path"],
                    "action": row["action"],
                    "username": row["username"],
                    "created_at": row["created_at"]
                })
            
            return events
        
        except Exception as e:
            logger.error(f"Failed to retrieve events: {e}")
            raise
    
    async def get_statistics(self) -> Dict[str, Any]:
        """
        Get monitoring statistics
        
        Returns:
            Statistics dictionary
        """
        try:
            # Total events
            cursor = await self.db.execute("SELECT COUNT(*) as count FROM usb_events")
            row = await cursor.fetchone()
            total_events = row["count"]
            
            # Unique devices
            cursor = await self.db.execute("SELECT COUNT(DISTINCT device_id) as count FROM usb_events")
            row = await cursor.fetchone()
            unique_devices = row["count"]
            
            # Event type distribution
            cursor = await self.db.execute("""
                SELECT event_type, COUNT(*) as count 
                FROM usb_events 
                GROUP BY event_type
            """)
            rows = await cursor.fetchall()
            
            event_type_distribution = {row["event_type"]: row["count"] for row in rows}
            
            return {
                "total_events": total_events,
                "unique_devices": unique_devices,
                "event_type_distribution": event_type_distribution
            }
        
        except Exception as e:
            logger.error(f"Failed to get statistics: {e}")
            raise
    
    async def delete_old_events(self, days: int = 30) -> int:
        """
        Delete events older than specified days
        
        Args:
            days: Number of days to keep
            
        Returns:
            Number of deleted events
        """
        try:
            cursor = await self.db.execute("""
                DELETE FROM usb_events 
                WHERE created_at < datetime('now', '-' || ? || ' days')
            """, (days,))
            
            await self.db.commit()
            
            deleted_count = cursor.rowcount
            logger.info(f"Deleted {deleted_count} old events (older than {days} days)")
            
            return deleted_count
        
        except Exception as e:
            logger.error(f"Failed to delete old events: {e}")
            raise

# Global database instance
db: Optional[Database] = None

def get_database() -> Database:
    """Get database instance"""
    return db