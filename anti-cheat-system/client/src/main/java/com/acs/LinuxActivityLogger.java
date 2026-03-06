package com.acs;

import com.sun.jna.*;
import com.sun.jna.platform.unix.X11;
import com.sun.jna.platform.unix.X11.Display;
import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.Atom;
import com.sun.jna.platform.unix.X11.AtomByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.NativeLongByReference;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Linux-specific implementation of ActivityLogger
 */
public class LinuxActivityLogger implements IActivityLogger {

    public interface X11Extended extends X11 {
        X11Extended INSTANCE = Native.load("X11", X11Extended.class);
        
        int XGetWindowProperty(Display display, Window w, Atom property, 
                              long long_offset, long long_length, boolean delete, 
                              Atom req_type, AtomByReference actual_type_return,
                              IntByReference actual_format_return, 
                              NativeLongByReference nitems_return,
                              NativeLongByReference bytes_after_return, 
                              PointerByReference prop_return);
        
        int XFetchName(Display display, Window w, PointerByReference window_name_return);
        
        interface XErrorHandler extends Callback {
            int callback(Display display, Pointer errorEvent);
        }
        
        XErrorHandler XSetErrorHandler(XErrorHandler handler);
    }

    private static volatile boolean errorOccurred = false;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void startLogging() throws Exception {
        X11Extended x11 = X11Extended.INSTANCE;
        Display display = x11.XOpenDisplay(null);
        
        if (display == null) {
            throw new Exception("Cannot open X11 display");
        }

        X11Extended.XErrorHandler errorHandler = (d, e) -> {
            errorOccurred = true;
            return 0;
        };
        x11.XSetErrorHandler(errorHandler);

        Atom netActiveWindow = x11.XInternAtom(display, "_NET_ACTIVE_WINDOW", false);
        Atom netWmName = x11.XInternAtom(display, "_NET_WM_NAME", false);
        Atom netWmPid = x11.XInternAtom(display, "_NET_WM_PID", false);
        Atom utf8String = x11.XInternAtom(display, "UTF8_STRING", false);
        Window root = x11.XDefaultRootWindow(display);

        String lastOutput = "";

        while (true) {
            try {
                errorOccurred = false;
                
                Window activeWindow = getActiveWindow(x11, display, root, netActiveWindow);
                
                if (activeWindow != null && !errorOccurred && activeWindow.longValue() != 0) {
                    String title = getWindowTitle(x11, display, activeWindow, netWmName, utf8String);
                    
                    if (errorOccurred) {
                        Thread.sleep(1000);
                        continue;
                    }
                    
                    int pid = getWindowPid(x11, display, activeWindow, netWmPid);
                    
                    if (errorOccurred) {
                        Thread.sleep(1000);
                        continue;
                    }
                    
                    String exePath = getExePath(pid);
                    String openFile = getOpenFile(pid, title, exePath);
                    
                    String output = "Active App: " + exePath + 
                                  " | Title: " + title + 
                                  " | PID: " + pid +
                                  (openFile != null ? " | File: " + openFile : "");
                    
                    if (!output.equals(lastOutput)) {
                        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
                        System.out.println("[" + timestamp + "] " + output);
                        lastOutput = output;
                    }
                }
                
                Thread.sleep(1000);
                
            } catch (Exception e) {
                // Silently continue
            }
        }
    }

    @Override
    public String getPlatformName() {
        return "Linux";
    }

    private static String getOpenFile(int pid, String title, String exePath) {
        if (pid <= 0) return null;
        
        String fileFromTitle = extractFileFromTitle(title, exePath);
        if (fileFromTitle != null) {
            return fileFromTitle;
        }
        
        return getMostRelevantOpenFile(pid, exePath);
    }

    private static String extractFileFromTitle(String title, String exePath) {
        if (title == null || title.isEmpty()) return null;
        
        if (title.contains("(~/") && title.contains(")")) {
            int start = title.indexOf("(~/");
            int end = title.indexOf(")", start);
            if (end > start) {
                String relativePath = title.substring(start + 1, end);
                String filename = title.substring(0, start).trim();
                
                String homeDir = System.getProperty("user.home");
                String fullPath = relativePath.replace("~", homeDir) + "/" + filename;
                
                File f = new File(fullPath);
                if (f.exists() && f.isFile()) {
                    return fullPath;
                }
            }
        }
        
        if (!title.contains("/") && !title.contains(" - ")) {
            String filename = title.trim();
            if (isMediaOrDocumentFile(filename)) {
                String homeDir = System.getProperty("user.home");
                String[] searchPaths = {
                    homeDir + "/Pictures/" + filename,
                    homeDir + "/Downloads/" + filename,
                    homeDir + "/Documents/" + filename,
                    homeDir + "/Desktop/" + filename
                };
                
                for (String path : searchPaths) {
                    File f = new File(path);
                    if (f.exists() && f.isFile()) {
                        return path;
                    }
                }
            }
        }
        
        String[] parts = title.split(" - ");
        if (parts.length > 0) {
            String potentialPath = parts[0].trim();
            
            if (potentialPath.startsWith("/")) {
                File f = new File(potentialPath);
                if (f.exists() && f.isFile()) {
                    return potentialPath;
                }
            }
        }
        
        return null;
    }

    private static String getMostRelevantOpenFile(int pid, String exePath) {
        try {
            Path fdDir = Paths.get("/proc/" + pid + "/fd");
            if (!Files.exists(fdDir)) return null;
            
            String mostRelevantFile = null;
            long mostRecentAccessTime = 0;
            
            try (Stream<Path> paths = Files.list(fdDir)) {
                for (Path fdPath : paths.toArray(Path[]::new)) {
                    try {
                        Path realPath = fdPath.toRealPath();
                        String filePath = realPath.toString();
                        
                        if (filePath.startsWith("/dev/") || 
                            filePath.startsWith("/proc/") ||
                            filePath.startsWith("/sys/") ||
                            filePath.contains("pipe:") ||
                            filePath.contains("socket:") ||
                            filePath.contains("anon_inode:") ||
                            filePath.contains("/.cache/") ||
                            filePath.contains("/.local/share/gvfs-metadata/") ||
                            filePath.contains("/.local/state/") ||
                            filePath.contains("/.config/") ||
                            filePath.contains("/tmp/") ||
                            filePath.endsWith(".log") ||
                            filePath.contains("/snap/")) {
                            continue;
                        }
                        
                        File f = new File(filePath);
                        if (!f.exists() || !f.isFile()) continue;
                        
                        if (filePath.endsWith(".so") || f.canExecute()) continue;
                        
                        if (filePath.startsWith("/home/")) {
                            if (isMediaOrDocumentFile(filePath)) {
                                long accessTime = Files.getLastModifiedTime(Paths.get(filePath)).toMillis();
                                if (accessTime > mostRecentAccessTime) {
                                    mostRelevantFile = filePath;
                                    mostRecentAccessTime = accessTime;
                                }
                            }
                        }
                        
                    } catch (Exception e) {
                        // Skip this fd
                    }
                }
            }
            
            return mostRelevantFile;
            
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isMediaOrDocumentFile(String path) {
        String lower = path.toLowerCase();
        
        if (lower.endsWith(".txt") || lower.endsWith(".md") || 
            lower.endsWith(".pdf") || lower.endsWith(".doc") || 
            lower.endsWith(".docx") || lower.endsWith(".odt") ||
            lower.endsWith(".rtf")) return true;
        
        if (lower.endsWith(".java") || lower.endsWith(".py") ||
            lower.endsWith(".js") || lower.endsWith(".ts") ||
            lower.endsWith(".html") || lower.endsWith(".css") ||
            lower.endsWith(".json") || lower.endsWith(".xml") ||
            lower.endsWith(".c") || lower.endsWith(".cpp") ||
            lower.endsWith(".h") || lower.endsWith(".hpp")) return true;
        
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || 
            lower.endsWith(".png") || lower.endsWith(".gif") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp") ||
            lower.endsWith(".svg") || lower.endsWith(".ico")) return true;
        
        if (lower.endsWith(".mp4") || lower.endsWith(".avi") ||
            lower.endsWith(".mkv") || lower.endsWith(".mov") ||
            lower.endsWith(".wmv") || lower.endsWith(".flv")) return true;
        
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") ||
            lower.endsWith(".flac") || lower.endsWith(".ogg") ||
            lower.endsWith(".m4a") || lower.endsWith(".aac")) return true;
        
        if (lower.endsWith(".csv") || lower.endsWith(".xlsx") ||
            lower.endsWith(".xls") || lower.endsWith(".ods")) return true;
        
        return false;
    }

    private static Window getActiveWindow(X11Extended x11, Display display, Window root, Atom netActiveWindow) {
        try {
            AtomByReference actualType = new AtomByReference();
            IntByReference actualFormat = new IntByReference();
            NativeLongByReference nItems = new NativeLongByReference();
            NativeLongByReference bytesAfter = new NativeLongByReference();
            PointerByReference prop = new PointerByReference();

            x11.XGetWindowProperty(display, root, netActiveWindow, 0, 1, false,
                    X11.XA_WINDOW, actualType, actualFormat, nItems, bytesAfter, prop);

            if (errorOccurred || prop.getValue() == null) {
                return null;
            }

            Window window = new Window(prop.getValue().getLong(0));
            x11.XFree(prop.getValue());
            
            if (window.longValue() == 0) {
                return null;
            }
            
            return window;
        } catch (Exception e) {
            return null;
        }
    }

    private static String getWindowTitle(X11Extended x11, Display display, Window window, 
                                         Atom netWmName, Atom utf8String) {
        try {
            AtomByReference actualType = new AtomByReference();
            IntByReference actualFormat = new IntByReference();
            NativeLongByReference nItems = new NativeLongByReference();
            NativeLongByReference bytesAfter = new NativeLongByReference();
            PointerByReference prop = new PointerByReference();

            x11.XGetWindowProperty(display, window, netWmName, 0, 1024, false,
                    utf8String, actualType, actualFormat, nItems, bytesAfter, prop);

            if (errorOccurred) {
                return "";
            }

            if (prop.getValue() != null && nItems.getValue().intValue() > 0) {
                try {
                    String name = prop.getValue().getString(0, "UTF-8");
                    x11.XFree(prop.getValue());
                    return name;
                } catch (Exception e) {
                    if (prop.getValue() != null) {
                        x11.XFree(prop.getValue());
                    }
                }
            }

            PointerByReference nameRef = new PointerByReference();
            x11.XFetchName(display, window, nameRef);
            
            if (errorOccurred || nameRef.getValue() == null) {
                return "";
            }
            
            String name = nameRef.getValue().getString(0);
            x11.XFree(nameRef.getValue());
            return name;
        } catch (Exception e) {
            return "";
        }
    }

    private static int getWindowPid(X11Extended x11, Display display, Window window, Atom netWmPid) {
        try {
            AtomByReference actualType = new AtomByReference();
            IntByReference actualFormat = new IntByReference();
            NativeLongByReference nItems = new NativeLongByReference();
            NativeLongByReference bytesAfter = new NativeLongByReference();
            PointerByReference prop = new PointerByReference();

            x11.XGetWindowProperty(display, window, netWmPid, 0, 1, false,
                    X11.XA_CARDINAL, actualType, actualFormat, nItems, bytesAfter, prop);

            if (errorOccurred || prop.getValue() == null) {
                return -1;
            }

            int pid = prop.getValue().getInt(0);
            x11.XFree(prop.getValue());
            return pid;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String getExePath(int pid) {
        if (pid <= 0) return "Unknown";
        
        try {
            String exeLink = "/proc/" + pid + "/exe";
            return new java.io.File(exeLink).getCanonicalPath();
        } catch (Exception e) {
            try (BufferedReader reader = new BufferedReader(
                    new FileReader("/proc/" + pid + "/cmdline"))) {
                String cmdline = reader.readLine();
                if (cmdline != null) {
                    return cmdline.split("\0")[0];
                }
            } catch (Exception ex) {
                // ignore
            }
        }
        return "Unknown";
    }
}