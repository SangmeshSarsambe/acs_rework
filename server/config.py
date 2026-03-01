"""
Configuration management for USB monitoring server
"""
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    """Server configuration settings"""
    
    # Database Configuration
    DATABASE_PATH: str = "usb_monitoring.db"
    
    # Server Configuration
    SERVER_HOST: str = "0.0.0.0"
    SERVER_PORT: int = 8000
    
    # API Configuration
    API_TITLE: str = "USB Monitoring System"
    API_VERSION: str = "1.0.0"
    
    class Config:
        env_file = ".env"
        case_sensitive = True

settings = Settings()