package dev.breeze.devtest;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stand-in for the Breeze launcher's side of the recorder's sound, for the
 * self-test only: the build machines start the game without the launcher and
 * have no sound device. It answers through the same files the launcher uses
 * (recordings/.audio/): status.json with a "null" microphone, and on stop a
 * game track (a 440 Hz tone) and a microphone track (a 220 Hz tone) of the
 * recording's length, with the moment the game asked to start as their first
 * sample. So the game's half (asking, waiting, joining both tracks to the
 * video in step) is tested for real; the launcher's WASAPI capture is not,
 * and real sound stays UNVERIFIED until a Windows player records.
 */
final class FakeLauncherAudio {

    private static volatile boolean running;
    static volatile String lastId;

    private FakeLauncherAudio() {}

    static void start(Path gameDir) {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> loop(gameDir), "Breeze test launcher audio");
        t.setDaemon(true);
        t.start();
    }

    static void stop() {
        running = false;
    }

    private static void loop(Path gameDir) {
        Path dir = gameDir.resolve("recordings").resolve(".audio");
        long startedAt = 0;
        String id = null;
        int n = 0;
        while (running) {
            try {
                Files.createDirectories(dir);
                if (n++ % 4 == 0) {
                    Files.writeString(dir.resolve("status.json"), "{\"version\":1,\"updated\":" + System.currentTimeMillis()
                            + ",\"game\":true,\"mic\":true,\"reason\":\"\",\"devices\":[{\"id\":\"null-mic\",\"name\":\"Null microphone (test)\"}]}", StandardCharsets.UTF_8);
                }
                Path start = dir.resolve("start.json");
                if (Files.isRegularFile(start)) {
                    String s = Files.readString(start, StandardCharsets.UTF_8);
                    Files.deleteIfExists(start);
                    id = s.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
                    startedAt = System.currentTimeMillis();
                    lastId = id;
                    Files.writeString(dir.resolve(id + ".json"), "{\"id\":\"" + id + "\",\"done\":false}", StandardCharsets.UTF_8);
                }
                Path stop = dir.resolve("stop.json");
                if (Files.isRegularFile(stop)) {
                    Files.deleteIfExists(stop);
                    if (id != null) {
                        double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
                        tone(dir.resolve(id + ".game.wav"), 440, seconds);
                        tone(dir.resolve(id + ".mic.wav"), 220, seconds);
                        Files.writeString(dir.resolve(id + ".json"), "{\"id\":\"" + id + "\",\"done\":true,"
                                + "\"game\":{\"file\":\"" + id + ".game.wav\",\"startEpochMs\":" + startedAt + "},"
                                + "\"mic\":{\"file\":\"" + id + ".mic.wav\",\"startEpochMs\":" + startedAt + "},\"error\":\"\"}", StandardCharsets.UTF_8);
                        id = null;
                    }
                }
                Thread.sleep(250);
            } catch (Exception e) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /** A 48 kHz 16-bit stereo WAV of a quiet tone. */
    private static void tone(Path f, double hz, double seconds) throws Exception {
        int rate = 48_000, frames = (int) Math.max(1, seconds * rate);
        ByteArrayOutputStream data = new ByteArrayOutputStream(frames * 4);
        for (int i = 0; i < frames; i++) {
            short v = (short) (Math.sin(2 * Math.PI * hz * i / rate) * 3000);
            data.write(v & 0xFF);
            data.write((v >> 8) & 0xFF);
            data.write(v & 0xFF);
            data.write((v >> 8) & 0xFF);
        }
        byte[] pcm = data.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
        DataOutputStream o = new DataOutputStream(out);
        o.writeBytes("RIFF");
        o.writeInt(Integer.reverseBytes(36 + pcm.length));
        o.writeBytes("WAVEfmt ");
        o.writeInt(Integer.reverseBytes(16));
        o.writeShort(Short.reverseBytes((short) 1));
        o.writeShort(Short.reverseBytes((short) 2));
        o.writeInt(Integer.reverseBytes(rate));
        o.writeInt(Integer.reverseBytes(rate * 4));
        o.writeShort(Short.reverseBytes((short) 4));
        o.writeShort(Short.reverseBytes((short) 16));
        o.writeBytes("data");
        o.writeInt(Integer.reverseBytes(pcm.length));
        o.write(pcm);
        Files.write(f, out.toByteArray());
    }
}
