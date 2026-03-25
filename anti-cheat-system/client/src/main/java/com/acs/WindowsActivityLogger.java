package com.acs;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Windows-specific implementation of IActivityLogger.
 * Detects active window changes and reports them via the onActivity callback.
 */
public class WindowsActivityLogger implements IActivityLogger {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd-MM-yyyy_HH-mm-ss HH:mm:ss");

    private volatile boolean running = false;

    @Override
    public void startLogging(Consumer<String> onActivity) throws Exception {
        running = true;
        String lastOutput = "";

        while (running) {
            try {
                WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();

                char[] title = new char[512];
                User32.INSTANCE.GetWindowText(hwnd, title, 512);

                IntByReference pid = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);

                WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(
                        WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_READ,
                        false, pid.getValue());

                char[] exePath = new char[1024];
                Psapi.INSTANCE.GetModuleFileNameExW(process, null, exePath, 1024);
                Kernel32.INSTANCE.CloseHandle(process);

                String output = "Active App: " + Native.toString(exePath)
                        + " | Title: " + Native.toString(title);

                // Only fire callback if something changed
                if (!output.equals(lastOutput)) {
                    String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
                    onActivity.accept("[" + timestamp + "] " + output);
                    lastOutput = output;
                }

            } catch (Exception e) {
                // Silently continue — window may have closed mid-read
            }

            Thread.sleep(1000);
        }
    }

    @Override
    public void stopLogging() {
        running = false;
    }

    @Override
    public String getPlatformName() {
        return "Windows";
    }
}
