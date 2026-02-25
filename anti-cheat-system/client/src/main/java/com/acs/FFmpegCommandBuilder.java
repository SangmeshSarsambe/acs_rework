package com.acs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class FFmpegCommandBuilder {

    private static Path resolveFfmpegExe() {
        String os = System.getProperty("os.name").toLowerCase();
        String exeName = os.contains("win") ? "ffmpeg.exe" : "ffmpeg";

        Path ffmpeg = Paths.get("ffmpeg", exeName).toAbsolutePath();

        System.out.println("[FFmpeg] Working dir : " + System.getProperty("user.dir"));
        System.out.println("[FFmpeg] Using       : " + ffmpeg);

        if (!Files.exists(ffmpeg)) {
            throw new RuntimeException("FFmpeg not found at: " + ffmpeg);
        }

        return ffmpeg;
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
                if (info.contains("nvidia")) return "nvidia";
                if (info.contains("amd") || info.contains("radeon")) return "amd";
                if (info.contains("intel")) return "intel";
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
            Path ffmpegExe = resolveFfmpegExe();

            String udpUrl = "udp://" + serverIp + ":" + streamPort
                    + "?pkt_size=1316&buffer_size=65536&overrun_nonfatal=1";

            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegExe.toString());

            // --- Input (OS specific) ---
            if (os.contains("win")) {
                cmd.add("-f"); cmd.add("gdigrab");
                cmd.add("-framerate"); cmd.add("60");
                cmd.add("-i"); cmd.add("desktop");
            } else {
                cmd.add("-f"); cmd.add("x11grab");
                cmd.add("-framerate"); cmd.add("60");
                cmd.add("-i"); cmd.add(":0.0+0,0");
            }

            // --- Encoder (GPU + OS specific) ---
            switch (gpu) {
                case "nvidia" -> {
                    cmd.add("-c:v"); cmd.add("h264_nvenc");
                    cmd.add("-preset"); cmd.add("p1");
                    cmd.add("-rc"); cmd.add("cbr");
                    cmd.add("-b:v"); cmd.add("5M");
                    cmd.add("-maxrate"); cmd.add("5M");
                    cmd.add("-bufsize"); cmd.add("5M");
                    cmd.add("-g"); cmd.add("30");
                    cmd.add("-bf"); cmd.add("0");
                    cmd.add("-delay"); cmd.add("0");
                }
                case "amd" -> {
                    if (os.contains("win")) {
                        // Windows AMD → AMF
                        cmd.add("-c:v"); cmd.add("h264_amf");
                        cmd.add("-b:v"); cmd.add("5M");
                        cmd.add("-maxrate"); cmd.add("5M");
                        cmd.add("-bufsize"); cmd.add("5M");
                        cmd.add("-g"); cmd.add("30");
                        cmd.add("-bf"); cmd.add("0");
                    } else {
                        // Linux AMD → VAAPI
                        cmd.add("-vaapi_device"); cmd.add("/dev/dri/renderD128");
                        cmd.add("-vf"); cmd.add("format=nv12,hwupload");
                        cmd.add("-c:v"); cmd.add("h264_vaapi");
                        cmd.add("-b:v"); cmd.add("5M");
                        cmd.add("-maxrate"); cmd.add("5M");
                        cmd.add("-bufsize"); cmd.add("5M");
                        cmd.add("-g"); cmd.add("30");
                        cmd.add("-bf"); cmd.add("0");
                    }
                }
                case "intel" -> {
                    cmd.add("-c:v"); cmd.add("h264_qsv");
                    cmd.add("-preset"); cmd.add("veryfast");
                    cmd.add("-async_depth"); cmd.add("1");
                    cmd.add("-b:v"); cmd.add("5M");
                    cmd.add("-maxrate"); cmd.add("5M");
                    cmd.add("-bufsize"); cmd.add("5M");
                    cmd.add("-g"); cmd.add("30");
                    cmd.add("-bf"); cmd.add("0");
                }
                default -> {
                    // Software encoding fallback (no dedicated/integrated GPU detected)
                    cmd.add("-c:v"); cmd.add("libx264");
                    cmd.add("-preset"); cmd.add("ultrafast");
                    cmd.add("-tune"); cmd.add("zerolatency");
                    cmd.add("-b:v"); cmd.add("5M");
                    cmd.add("-maxrate"); cmd.add("5M");
                    cmd.add("-bufsize"); cmd.add("5M");
                    cmd.add("-g"); cmd.add("30");
                    cmd.add("-bf"); cmd.add("0");
                }
            }

            // --- Common output flags ---
            // Note: -pix_fmt yuv420p is skipped for VAAPI as it manages pixel format internally
            if (!(gpu.equals("amd") && !os.contains("win"))) {
                cmd.add("-pix_fmt"); cmd.add("yuv420p");
            }
            cmd.add("-fflags"); cmd.add("nobuffer");
            cmd.add("-flags"); cmd.add("low_delay");
            cmd.add("-f"); cmd.add("mpegts");
            cmd.add(udpUrl);

            System.out.println("[FFmpeg] GPU detected : " + gpu);
            System.out.println("[FFmpeg] Command      : " + cmd);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(ffmpegExe.getParent().getParent().toFile()); // app root
            pb.inheritIO();

            return pb.start();

        } catch (Exception e) {
            System.err.println("❌ FFmpeg start failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

}
