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
        'file_name': os.path.basename(event.src_path),
        'file_path': event.src_path,  # CHANGED: Added this line
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
        'file_name': os.path.basename(event.src_path),
        'file_path': event.src_path,  # CHANGED: Added this line
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
        'file_name': os.path.basename(event.src_path),
        'file_path': event.src_path,  # CHANGED: Added this line
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
        'file_name': os.path.basename(event.dest_path),
        'file_path': event.dest_path,  # CHANGED: Added this line
        'action': f'File renamed: {event.src_path} → {event.dest_path}',
        'username': self.username
    })