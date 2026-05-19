# Anti-Cheat System (ACS) — Demo Setup Guide

This guide provides quick and easy instructions to set up, deploy, and run the Anti-Cheat System for your demo. The system uses pre-compiled JAR files along with handy scripts to easily supervise and configure everything.

---

## 🛡️ What Does This Application Do?

The Anti-Cheat System (ACS) is designed to maintain testing integrity by monitoring a student's machine for suspicious behavior. When the client runs on a student's PC, it tracks and reports the following activities to the Server:

| Feature | Description |
|---|---|
| **Targeted Monitoring Control** | Click **Start Monitoring** to begin tracking all activities. Click **Stop Monitoring** to finalize and save the session log. |
| **On-Demand Live Screen Feed** | Start a live video stream of any connected student's screen over the local network using FFmpeg + VLC. |
| **Active Window Tracking** | Detects if a student tabs out of the testing environment to browse the web or open unauthorized applications. |
| **USB & Phone Detection** | Detects and alerts the server when new USB devices, flash drives, or mobile phones are plugged in during the session. |
| **Keylogging & Keyword Flagging** | Logs keyboard input and automatically flags suspicious behavior when predefined "banned" keywords are typed. |
| **Remote Input Locking** | Remotely lock/unlock a student's keyboard and mouse from the server dashboard during an exam. |
| **Fault Tolerance & Auto-Recovery** | Robust auto-discovery and auto-recovery — if a session is interrupted or the connection drops, the system automatically reconnects. |

> 🛠️ **Status (In Development):** We are exclusively releasing **Debug Mode** scripts for this demo. Debug mode leaves a console terminal open so developers and proctors can see live application logs and catch errors.

---

## ⚙️ Prerequisites

| Requirement | Details |
|---|---|
| **Java** | JRE or JDK **26** must be installed on both server and client machines. |
| **FFmpeg** | Required on the **Client** side for screen capture streaming. |
| **VLC** | Required on the **Server** side for rendering live video streams. |

---

## 📂 Expected Directory Structure

> 💡 The **Server** and **Client** folders are completely independent of each other. You can set them up together on one machine for testing, or distribute the Client folder on its own to other machines.

### Server Folder
```text
server/
├── vlc/                       # VLC media player folder (Windows only)
│   ├── libvlc.dll
│   ├── libvlccore.dll
│   └── ...
├── server-app.jar             # Main Server JAR
├── start-server-debug.bat     # Windows launch script
└── start-server-debug.sh      # Linux launch script
```

### Client Folder
```text
client/
├── ffmpeg/
│   └── ffmpeg.exe             # FFmpeg executable (Windows only)
├── client-app.jar             # Main Client JAR
├── start-client-debug.bat     # Windows launch script
└── start-client-debug.sh      # Linux launch script
```

---

## 📦 Media Dependencies Setup

The system relies on **FFmpeg** (Client) for screen capture and **VLC** (Server) for stream playback.

### Windows

**1. FFmpeg (Client folder):**
1. Download from [gyan.dev/ffmpeg/builds](https://www.gyan.dev/ffmpeg/builds/) — get the **ffmpeg-git-full.7z** or **.zip** build.
2. Extract → navigate into the `bin` directory → copy **only `ffmpeg.exe`**.
3. In your Client folder, create a folder named `ffmpeg` and paste `ffmpeg.exe` inside it.

**2. VLC (Server folder):**
1. Download the VLC zip/portable version from [videolan.org](https://images.videolan.org/vlc/download-windows.html).
2. Extract and rename the folder to `vlc` → place it directly in the Server folder.
3. **Verify:** Open the `vlc` folder — you should see `libvlc.dll` and `libvlccore.dll` at the top level.

### Linux (Ubuntu)

```bash
sudo apt update
sudo apt install vlc ffmpeg -y
```

---

## 🚀 Running the System

Use the provided scripts to launch the application:
- **`.bat`** → Windows
- **`.sh`** → Linux (Ubuntu)

> ❗ **Windows Users — Do NOT double-click the `.jar` file directly.**
> Always use the `.bat` script. Some machines encounter an IPv6/IPv4 stack error when running the JAR directly. The `.bat` script is pre-configured to force Java to use IPv4.

> 🐧 **Linux Users:** Before running `.sh` scripts for the first time, make them executable:
> ```bash
> chmod +x *.sh
> ```

### Step 1 — Launch the Server (Admin / Proctor PC)

> 🚨 **CRITICAL:** Only **ONE** server should be running on your local network at a time. Multiple servers will cause clients to connect to the wrong one.

1. Navigate to your Server directory.
2. Run the server script:
   - **Windows:** Double-click `start-server-debug.bat`
   - **Linux:** `./start-server-debug.sh`
3. ⚠️ **Windows:** When the firewall pop-up appears, click **Allow access** (check both private and public networks). Without this, incoming video streams will be blocked.

### Step 2 — Set Up the Client (Student PCs)

1. Copy the entire Client folder to each student machine.
2. Run the client script:
   - **Windows:** Double-click `start-client-debug.bat`
   - **Linux:** `./start-client-debug.sh`
3. ⚠️ **Windows:** Allow firewall access if prompted.
4. Repeat for every student PC.

### Step 3 — Operating the Session

Once the server and clients are connected:

| Action | How |
|---|---|
| **Start tracking** | Click **▶ Start Monitoring** on the server toolbar. |
| **Lock all inputs** | Click **🔒 Lock All** on the toolbar (only available while monitoring). |
| **Unlock all inputs** | Click **🔓 Unlock All** on the toolbar. |
| **Per-client control** | Click a client card → use Lock/Unlock and Stream buttons in the detail dialog. |
| **Stop & save** | Click **⏹ Stop Monitoring** — all tracking stops, locks are released, logs are saved. |

---

## 🔒 Input Locking (Remote Keyboard & Mouse Lock)

The server operator can remotely lock a student's keyboard, mouse, and touchpad during an exam. This feature is **only available while monitoring is active**.

### How It Works
- **Lock All / Unlock All** buttons on the toolbar control all connected clients at once.
- **Per-client Lock / Unlock** buttons are in each client's detail dialog (click the card).
- Lock state is **persistent** — survives client restarts and server crashes within a session.
- **Stop Monitoring** automatically unlocks all clients.

### 🔑 Emergency Unlock (Backdoor Key)

If a student is locked and the server is unreachable, pressing **`Ctrl + Alt + Shift + U`** on the student's machine will immediately unlock all input. The server is notified when this is used.

### 🐧 Linux — Required Permission Setup

For the emergency unlock key to work on Linux, the client user must be in the **`input`** group:

```bash
# Add user to the input group (run once, requires sudo)
sudo usermod -aG input $(whoami)

# ⚠️ Log out and log back in for the change to take effect!

# Verify after re-login:
groups    # should include 'input'
```

> ⚠️ **If this is not done**, the emergency unlock key will be **disabled** on Linux. The console will print a warning. Students can still be unlocked from the server, or by switching to a TTY (`Ctrl+Alt+F2`) and killing the Java process.

### 🪟 Windows — Run as Administrator (Recommended)

Keyboard and mouse locking works without admin. However, to also **block touchpad gestures** (3/4-finger swipe for app-switching), the client must be **run as Administrator**.

### Safety Mechanisms

| Scenario | What Happens |
|---|---|
| **Server crashes / disconnects** | Client automatically releases the lock. Student is never permanently locked. |
| **Client process killed** (Task Manager / `kill`) | JVM shutdown hook re-enables all input devices. |
| **Student kicked** | Server unlocks the client before kicking. |
| **Stop Monitoring** | All clients automatically unlocked. |

---

## 📁 Logs & Session Management

All recorded activities are logged and saved inside a root **`logs/`** folder in the server directory.

### Log Structure
```text
logs/
└── session_21-04-2026_10-30-00/        # Timestamped session folder
    ├── hostname1_192.168.1.10/         # Per-client folder (hostname_IP)
    │   ├── activity.txt                # Active window tracking log
    │   ├── keylog.txt                  # Keystroke log
    │   ├── usb.txt                     # USB device events
    │   ├── lock_state.properties       # Last known lock state
    │   └── session.txt                 # Merged session timeline
    ├── hostname2_192.168.1.11/
    │   └── ...
    └── session.txt                     # Server-level session log
```

### How It Works
1. **Start Monitoring** creates a new timestamped session folder.
2. During the session, all client activity is written to per-client subfolders.
3. **Stop Monitoring** finalizes the session — merges all logs into `session.txt` files.
4. If a client disconnects and reconnects, logs continue appending to the same session folder.

> 💡 Navigate into a client's IP folder and check the `.txt` files for a complete, timestamped timeline of their activity.
