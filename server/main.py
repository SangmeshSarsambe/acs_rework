"""
FastAPI server for USB monitoring system with SQLite
"""
from fastapi import FastAPI, HTTPException, status
from fastapi.responses import JSONResponse
from contextlib import asynccontextmanager
import logging
from typing import List, Optional

from models import USBEvent, EventResponse
from database import Database, get_database
from config import settings

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Global database instance
db_instance: Optional[Database] = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Lifespan context manager for startup and shutdown events
    """
    # Startup
    global db_instance
    logger.info("Starting USB Monitoring Server...")
    
    db_instance = Database(db_path=settings.DATABASE_PATH)
    
    try:
        await db_instance.connect()
        logger.info("Server startup complete")
    except Exception as e:
        logger.error(f"Failed to start server: {e}")
        raise
    
    yield
    
    # Shutdown
    logger.info("Shutting down USB Monitoring Server...")
    if db_instance:
        await db_instance.disconnect()
    logger.info("Server shutdown complete")

# Create FastAPI application
app = FastAPI(
    title=settings.API_TITLE,
    version=settings.API_VERSION,
    lifespan=lifespan
)

@app.get("/")
async def root():
    """Root endpoint"""
    return {
        "service": "USB Monitoring System",
        "version": settings.API_VERSION,
        "status": "running",
        "database": "SQLite"
    }

@app.post("/usb-event", response_model=EventResponse, status_code=status.HTTP_201_CREATED)
async def create_usb_event(event: USBEvent):
    """
    Receive and store USB monitoring event
    
    Args:
        event: USB event data
        
    Returns:
        Event creation response
    """
    try:
        # Convert event to dictionary
        event_dict = event.model_dump()
        
        # Insert into database
        event_id = await db_instance.insert_event(event_dict)
        
        logger.info(
            f"Received event from {event.device_id}: "
            f"{event.event_type} - {event.usb_drive}"
        )
        
        return EventResponse(
            status="success",
            message="Event recorded successfully",
            event_id=str(event_id)
        )
    
    except Exception as e:
        logger.error(f"Failed to process event: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to process event: {str(e)}"
        )

@app.get("/events", response_model=List[dict])
async def get_events(
    device_id: Optional[str] = None,
    limit: int = 100
):
    """
    Retrieve USB events
    
    Args:
        device_id: Filter by device ID (optional)
        limit: Maximum number of events to retrieve
        
    Returns:
        List of events
    """
    try:
        events = await db_instance.get_events(device_id=device_id, limit=limit)
        return events
    except Exception as e:
        logger.error(f"Failed to retrieve events: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to retrieve events: {str(e)}"
        )

@app.get("/statistics")
async def get_statistics():
    """
    Get monitoring statistics
    
    Returns:
        Statistics dictionary
    """
    try:
        stats = await db_instance.get_statistics()
        return stats
    except Exception as e:
        logger.error(f"Failed to get statistics: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to get statistics: {str(e)}"
        )

@app.delete("/events/cleanup")
async def cleanup_old_events(days: int = 30):
    """
    Delete events older than specified days
    
    Args:
        days: Number of days to keep (default: 30)
        
    Returns:
        Cleanup result
    """
    try:
        deleted_count = await db_instance.delete_old_events(days=days)
        return {
            "status": "success",
            "deleted_count": deleted_count,
            "message": f"Deleted events older than {days} days"
        }
    except Exception as e:
        logger.error(f"Failed to cleanup events: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to cleanup events: {str(e)}"
        )

@app.get("/health")
async def health_check():
    """Health check endpoint"""
    return {"status": "healthy", "database": "SQLite", "db_path": settings.DATABASE_PATH}

if __name__ == "__main__":
    import uvicorn
    
    uvicorn.run(
        "main:app",
        host=settings.SERVER_HOST,
        port=settings.SERVER_PORT,
        reload=False,
        log_level="info"
    )