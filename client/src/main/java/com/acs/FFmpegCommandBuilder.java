package com.acs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class FFmpegCommandBuilder {

    /**
     * Checks that FFmpeg is available on this machine.
     * Call once at startup to fail fast with a popup instead of
     * discovering the problem mid-exam when streaming is requested.
     */
    public static void checkFfmpegAvailable() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            // Check JAR-relative first, then working directory
            Path baseDir = null;
            try {
                baseDir = Paths.get(
                        FFmpegCommandBuilder.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI())
                        .getParent();
            } catch (Exception ignored) {}

            // Try JAR-relative, then working dir
            Path ffmpegDir = null;
            Path ffmpegExe = null;

            if (baseDir != null) {
                Path dir = baseDir.resolve("ffmpeg").toAbsolutePath();
                if (Files.isDirectory(dir)) {
                    ffmpegDir = dir;
                    Path exe = dir.resolve("ffmpeg.exe");
                    if (Files.exists(exe)) ffmpegExe = exe;
                }
            }

            if (ffmpegDir == null || ffmpegExe == null) {
                // Fallback: check working directory
                Path dir = Paths.get("ffmpeg").toAbsolutePath();
                if (Files.isDirectory(dir)) {
                    ffmpegDir = dir;
                    Path exe = dir.resolve("ffmpeg.exe");
                    if (Files.exists(exe)) ffmpegExe = exe;
                }
            }

            if (ffmpegDir == null) {
                ErrorDialog.fatalAndExit("FFmpeg Folder Missing",
                        "The 'ffmpeg/' folder was not found next to the JAR.\n"
                      + "Please place the ffmpeg/ folder in the same directory as the client JAR.");
            } else if (ffmpegExe == null) {
                ErrorDialog.fatalAndExit("FFmpeg Executable Missing",
                        "The 'ffmpeg/' folder exists, but 'ffmpeg.exe' was not found inside it.\n"
                      + "Make sure ffmpeg.exe is placed inside the ffmpeg/ folder.");
            }
        } else {
            // Linux / Mac — ffmpeg should be installed system-wide
            try {
                Process p = new ProcessBuilder("which", "ffmpeg")
                        .redirectErrorStream(true).start();
                int code = p.waitFor();
                if (code != 0) {
                    ErrorDialog.fatalAndExit("FFmpeg Not Found",
                            "'ffmpeg' is not installed or not in PATH.\n"
                          + "Install it with: sudo apt install ffmpeg");
                }
            } catch (Exception e) {
                ErrorDialog.fatalAndExit("FFmpeg Not Found",
                        "Could not verify FFmpeg installation.\n" + e.getMessage());
            }
        }
        System.out.println("[FFmpeg] ✔ FFmpeg is available");
    }

    private static String resolveFfmpegExe() {
        // 1. JAR-relative (production / pendrive)
        try {
            Path jarDir = Paths.get(
                    FFmpegCommandBuilder.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .getParent();

            Path ffmpeg = jarDir.resolve("ffmpeg").resolve("ffmpeg.exe").toAbsolutePath();
            System.out.println("[FFmpeg] Trying JAR-relative: " + ffmpeg);
            if (Files.exists(ffmpeg))
                return ffmpeg.toString();
        } catch (Exception ignored) {
        }

        // 2. Working directory (dev / IDE)
        Path ffmpeg = Paths.get("ffmpeg", "ffmpeg.exe").toAbsolutePath();
        System.out.println("[FFmpeg] Trying working dir: " + ffmpeg);
        if (Files.exists(ffmpeg))
            return ffmpeg.toString();

        ErrorDialog.fatalAndExit("FFmpeg Not Found",
                "FFmpeg executable was not found.\nPlace the ffmpeg/ folder next to the JAR.");
        throw new RuntimeException("FFmpeg not found."); // unreachable, keeps compiler happy
    }

    private static String detectGpuVendor(String os) {
        try {
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("wmic", "path", "win32_VideoController", "get", "name");
            } else {
                pb = new ProcessBuilder("bash", "-c", "lspci | grep -i vga");
            }

            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                StringBuilder sb = new StringBuilder();
                while ((line = br.readLine()) != null) {
                    sb.append(line.toLowerCase());
                }
                p.waitFor();

                String info = sb.toString();
                if (info.contains("nvidia"))
                    return "nvidia";
                if (info.contains("amd") || info.contains("radeon"))
                    return "amd";
                if (info.contains("intel"))
                    return "intel";
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "default";
    }

    public static Process startStream(String serverIp, int streamPort) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String gpu = detectGpuVendor(os);
            String ffmpegCmd = os.contains("win") ? resolveFfmpegExe() : "ffmpeg";

            String udpUrl = "udp://" + serverIp + ":" + streamPort
                    + "?pkt_size=1316&buffer_size=65536&overrun_nonfatal=1";

            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegCmd);

            // --- Input (OS specific) ---
            if (os.contains("win")) {
                cmd.add("-f");
                cmd.add("gdigrab");
                cmd.add("-framerate");
                cmd.add("60");
                cmd.add("-i");
                cmd.add("desktop");
            } else {
                cmd.add("-f");
                cmd.add("x11grab");
                cmd.add("-framerate");
                cmd.add("60");
                cmd.add("-i");
                cmd.add(":0.0+0,0");
            }

            // --- Encoder (GPU + OS specific) ---
            switch (gpu) {
                case "nvidia" -> {
                    cmd.add("-c:v");
                    cmd.add("h264_nvenc");
                    cmd.add("-preset");
                    cmd.add("p1");
                    cmd.add("-rc");
                    cmd.add("cbr");
                    cmd.add("-b:v");
                    cmd.add("5M");
                    cmd.add("-maxrate");
                    cmd.add("5M");
                    cmd.add("-bufsize");
                    cmd.add("5M");
                    cmd.add("-g");
                    cmd.add("30");
                    cmd.add("-bf");
                    cmd.add("0");
                    cmd.add("-delay");
                    cmd.add("0");
                }
                case "amd" -> {
                    if (os.contains("win")) {
                        // Windows AMD → AMF
                        cmd.add("-c:v");
                        cmd.add("h264_amf");
                        cmd.add("-b:v");
                        cmd.add("5M");
                        cmd.add("-maxrate");
                        cmd.add("5M");
                        cmd.add("-bufsize");
                        cmd.add("5M");
                        cmd.add("-g");
                        cmd.add("30");
                        cmd.add("-bf");
                        cmd.add("0");
                    } else {
                        // Linux AMD → VAAPI
                        cmd.add("-vaapi_device");
                        cmd.add("/dev/dri/renderD128");
                        cmd.add("-vf");
                        cmd.add("format=nv12,hwupload");
                        cmd.add("-c:v");
                        cmd.add("h264_vaapi");
                        cmd.add("-b:v");
                        cmd.add("5M");
                        cmd.add("-maxrate");
                        cmd.add("5M");
                        cmd.add("-bufsize");
                        cmd.add("5M");
                        cmd.add("-g");
                        cmd.add("30");
                        cmd.add("-bf");
                        cmd.add("0");
                    }
                }
                case "intel" -> {
                    if (os.contains("win")) {
                        cmd.add("-c:v");
                        cmd.add("h264_qsv");
                        cmd.add("-preset");
                        cmd.add("veryfast");
                        cmd.add("-async_depth");
                        cmd.add("1");
                        cmd.add("-b:v");
                        cmd.add("5M");
                        cmd.add("-maxrate");
                        cmd.add("5M");
                        cmd.add("-bufsize");
                        cmd.add("5M");
                        cmd.add("-g");
                        cmd.add("30");
                        cmd.add("-bf");
                        cmd.add("0");
                    } else {
                        cmd.add("-vaapi_device");
                        cmd.add("/dev/dri/renderD128");
                        cmd.add("-vf");
                        cmd.add("format=nv12,hwupload");
                        cmd.add("-c:v");
                        cmd.add("h264_vaapi");
                        // CQP mode: used because some Intel iGPUs (older gen) only support CQP via
                        // VAAPI
                        // Newer Intel GPUs support CBR/VBR — if so, replace below with:
                        // cmd.add("-b:v"); cmd.add("5M");
                        // cmd.add("-maxrate"); cmd.add("5M");
                        // cmd.add("-bufsize"); cmd.add("5M");
                        cmd.add("-rc_mode");
                        cmd.add("CQP");
                        cmd.add("-qp");
                        cmd.add("22"); // 0=best quality, 51=worst; 18-28 is a good range
                        cmd.add("-g");
                        cmd.add("30");
                        cmd.add("-bf");
                        cmd.add("0");
                    }
                }
                default -> {
                    // Software encoding fallback (no dedicated/integrated GPU detected)
                    cmd.add("-c:v");
                    cmd.add("libx264");
                    cmd.add("-preset");
                    cmd.add("ultrafast");
                    cmd.add("-tune");
                    cmd.add("zerolatency");
                    cmd.add("-b:v");
                    cmd.add("5M");
                    cmd.add("-maxrate");
                    cmd.add("5M");
                    cmd.add("-bufsize");
                    cmd.add("5M");
                    cmd.add("-g");
                    cmd.add("30");
                    cmd.add("-bf");
                    cmd.add("0");
                }
            }

            // --- Common output flags ---
            // Note: -pix_fmt yuv420p is skipped for VAAPI as it manages pixel format
            // internally
            if (!(!os.contains("win") && (gpu.equals("amd") || gpu.equals("intel")))) {
                cmd.add("-pix_fmt");
                cmd.add("yuv420p");
            }
            cmd.add("-fflags");
            cmd.add("nobuffer");
            cmd.add("-flags");
            cmd.add("low_delay");
            cmd.add("-f");
            cmd.add("mpegts");
            cmd.add(udpUrl);

            System.out.println("[FFmpeg] GPU detected : " + gpu);
            System.out.println("[FFmpeg] Command      : " + cmd);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (os.contains("win")) {
                pb.directory(Paths.get(ffmpegCmd).getParent().getParent().toFile());
            }

            if (SimpleClient.DEBUG) {
                pb.inheritIO();
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            }

            return pb.start();

        } catch (Exception e) {
            System.err.println("❌ FFmpeg start failed: " + e.getMessage());
            e.printStackTrace();
            ErrorDialog.warn("FFmpeg Error",
                    "Failed to start screen streaming.\n" + e.getMessage());
            return null;
        }
    }
}