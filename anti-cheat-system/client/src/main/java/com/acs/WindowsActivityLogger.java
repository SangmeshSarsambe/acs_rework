package com.acs;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Windows-specific implementation of ActivityLogger
 */
public class WindowsActivityLogger implements ActivityLogger {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void startLogging() throws Exception {
        String lastOutput = "";
        
        while (true) {
            WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();

            char[] title = new char[512];
            User32.INSTANCE.GetWindowText(hwnd, title, 512);

            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);

            WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(
                    WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_READ,
                    false,
                    pid.getValue()
            );

            char[] exePath = new char[1024];
            Psapi.INSTANCE.GetModuleFileNameExW(process, null, exePath, 1024);

            Kernel32.INSTANCE.CloseHandle(process);

            String output = "Active App: " +
                    Native.toString(exePath) +
                    " | Title: " +
                    Native.toString(title);

            // Only print if different from last output
            if (!output.equals(lastOutput)) {
                String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
                System.out.println("[" + timestamp + "] " + output);
                lastOutput = output;
            }

            Thread.sleep(1000); // every second
        }
    }

    @Override
    public String getPlatformName() {
        return "Windows";
    }
}