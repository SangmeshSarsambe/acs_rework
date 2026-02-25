package com.acs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;

public class ProcessExample {

    public static void main(String[] args) {
        try {
            // ffmpeg/ffmpeg.exe must be in the same directory as where Java is run
            Path ffmpeg = Paths.get("ffmpeg", "ffmpeg.exe");

            // Debug: show where Java is running from
            System.out.println("Working directory: " + System.getProperty("user.dir"));
            System.out.println("Looking for FFmpeg at: " + ffmpeg.toAbsolutePath());

            // Check if ffmpeg exists
            if (!Files.exists(ffmpeg)) {
                System.err.println("FFmpeg not found!");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg.toString(),
                    "-version"
            );

            // Show ffmpeg output in current console
            pb.inheritIO();

            // Start process
            Process process = pb.start();

            // Wait for completion
            int exitCode = process.waitFor();
            System.out.println("FFmpeg exited with code: " + exitCode);

        } catch (IOException e) {
            System.err.println("Failed to start FFmpeg");
            e.printStackTrace();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Process interrupted");
        }
    }
}