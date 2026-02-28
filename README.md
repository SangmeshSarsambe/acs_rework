# Setup Guide

## FFmpeg

### Windows
1. Download the FFmpeg Windows build from https://ffmpeg.org/download.html
2. Extract the zip and locate `ffmpeg.exe` inside the `bin/` folder
3. Place `ffmpeg.exe` inside a folder named `ffmpeg/` next to the application JAR:
```
your-app/
├── myapp.jar
└── ffmpeg/
    └── ffmpeg.exe
```

### Linux
Install FFmpeg directly via apt:
```bash
sudo apt install ffmpeg
```

> **Note:** Static FFmpeg builds are currently not supported on Ubuntu 24.04 LTS. This is because a core system library required by FFmpeg for hardware-accelerated encoding (e.g. NVIDIA NVENC, AMD VAAPI, Intel QSV) ships at a lower version in Ubuntu 24.04 than what a static build requires. Using the apt package ensures the correct library versions are present on your system.

---

## VLC

### Windows
1. Download the **VLC portable** build from https://www.videolan.org/vlc/download-windows.html
2. Extract the zip — you will get a folder containing `vlc.exe` and supporting files
3. Place the **contents** of that folder inside a folder named `vlc/` next to the application JAR:
```
your-app/
├── myapp.jar
├── ffmpeg/
│   └── ffmpeg.exe
└── vlc/
    ├── vlc.exe
    └── (other vlc files...)
```

### Linux
Install VLC directly via apt:
```bash
sudo apt install vlc
```
