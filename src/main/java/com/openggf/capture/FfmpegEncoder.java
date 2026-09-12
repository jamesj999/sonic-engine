package com.openggf.capture;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;

/**
 * {@link CaptureEncoder} that streams raw RGBA frames to ffmpeg and produces a
 * lossless MKV. Two phases: (1) pipe RGBA -> ffmpeg FFV1 video, append PCM to a
 * temp s16le file; (2) mux video (copy) + FLAC audio into the final file.
 *
 * <p>Y-flip and integer upscale are done by ffmpeg ({@code -vf vflip,scale=...})
 * so the producer hot path is a raw byte write.
 */
public final class FfmpegEncoder implements CaptureEncoder {
    @FunctionalInterface
    interface ProcessLauncher {
        Process start(List<String> command) throws IOException;
    }

    interface LifecycleSeam {
        LifecycleSeam NONE = new LifecycleSeam() {};
        default void beforeMuxPublication(Process process) throws InterruptedException {}
        default void beforeSuccessPublication() throws InterruptedException {}
    }

    private static final long DEFAULT_PROCESS_TIMEOUT_MILLIS = 30_000;

    private final String ffmpeg;
    private final int scale;
    private final int sampleRate0;            // captured for phase 2
    private final ProcessLauncher launcher;
    private final long processTimeoutMillis;
    private final LifecycleSeam lifecycleSeam;
    private final Object lifecycleLock = new Object();
    private Path finalOut;
    private Path tempVideo;
    private Path tempAudio;
    private Process videoProc;
    private Process muxProc;
    private OutputStream videoStdin;
    private OutputStream audioOut;
    private Thread stderrDrain;
    private int sampleRate;
    private boolean terminal;
    private boolean aborted;
    /** Tail of ffmpeg's stderr, bounded so a chatty encoder cannot grow it. */
    private static final int DIAGNOSTICS_LIMIT = 4096;
    private final StringBuilder diagnostics = new StringBuilder();

    private CaptureCodecs.Codec videoCodec = CaptureCodecs.video("ffv1");
    private CaptureCodecs.Codec audioCodec = CaptureCodecs.audio("flac");
    private String pass1Override = FfmpegCommandTemplate.DEFAULT;
    private String pass2Override = FfmpegCommandTemplate.DEFAULT;

    /**
     * Replaces the argument list of either ffmpeg pass. {@code "default"} keeps
     * the engine's command; an empty second pass skips muxing entirely and
     * publishes the first pass's output, so the recording has no audio track.
     * An empty first pass is rejected: that is where frames are encoded.
     */
    public void setCommandOverrides(String pass1, String pass2) {
        if (FfmpegCommandTemplate.skipsPass(pass1)) {
            throw new IllegalArgumentException(
                    "the first ffmpeg pass encodes the submitted frames and cannot"
                            + " be skipped; use \"default\" for the engine's command");
        }
        this.pass1Override = pass1;
        this.pass2Override = pass2;
    }

    private boolean skipsMux() {
        return FfmpegCommandTemplate.skipsPass(pass2Override);
    }

    /**
     * Selects the encoders for subsequent recordings. Defaults are FFV1 and
     * FLAC, which reproduce the submitted frames and samples exactly.
     *
     * @throws IllegalArgumentException on an unknown codec name, so a typo in
     *         configuration fails before a recording starts
     */
    public void setCodecs(String video, String audio) {
        setCodecs(video, audio, 0, "");
    }

    /**
     * @param threads {@code -threads} for the encode pass; 0 lets ffmpeg choose
     * @param preset x264/x265 speed preset, blank for the encoder default
     * @see CaptureCodecs#video(String, int, String)
     */
    public void setCodecs(String video, String audio, int threads, String preset) {
        this.videoCodec = CaptureCodecs.video(video, threads, preset);
        this.audioCodec = CaptureCodecs.audio(audio);
    }

    public CaptureCodecs.Codec videoCodec() {
        return videoCodec;
    }

    public CaptureCodecs.Codec audioCodec() {
        return audioCodec;
    }

    /** @param ffmpeg path/name of the ffmpeg executable; @param scale integer upscale factor */
    public FfmpegEncoder(String ffmpeg, int scale) {
        this(ffmpeg, scale, command -> new ProcessBuilder(command).redirectErrorStream(false).start(),
                DEFAULT_PROCESS_TIMEOUT_MILLIS, LifecycleSeam.NONE);
    }

    FfmpegEncoder(String ffmpeg, int scale, ProcessLauncher launcher, long processTimeoutMillis) {
        this(ffmpeg, scale, launcher, processTimeoutMillis, LifecycleSeam.NONE);
    }

    FfmpegEncoder(String ffmpeg, int scale, ProcessLauncher launcher, long processTimeoutMillis,
                  LifecycleSeam lifecycleSeam) {
        this.ffmpeg = ffmpeg;
        this.scale = Math.max(1, scale);
        this.sampleRate0 = 0;
        this.launcher = launcher;
        this.processTimeoutMillis = Math.max(1, processTimeoutMillis);
        this.lifecycleSeam = lifecycleSeam;
    }

    // ---- pure helpers (unit-tested) ----

    static List<String> phase1Command(String ffmpeg, Path videoOut,
                                      int width, int height, int fps, int scale) {
        return phase1Command(ffmpeg, videoOut, width, height, fps, scale,
                CaptureCodecs.video("ffv1"));
    }

    static List<String> phase1Command(String ffmpeg, Path videoOut,
                                      int width, int height, int fps, int scale,
                                      CaptureCodecs.Codec video) {
        List<String> c = new ArrayList<>();
        c.add(ffmpeg);
        c.add("-y");
        c.add("-f"); c.add("rawvideo");
        c.add("-pix_fmt"); c.add("rgba");
        c.add("-s"); c.add(width + "x" + height);
        c.add("-r"); c.add(String.valueOf(fps));
        c.add("-i"); c.add("pipe:0");
        c.add("-vf"); c.add("vflip,scale=" + (width * scale) + ":" + (height * scale)
                + ":flags=neighbor");
        c.addAll(video.arguments());
        c.add("-an");
        c.add(videoOut.toAbsolutePath().toString());
        return c;
    }

    static List<String> phase2MuxCommand(String ffmpeg, Path videoMkv, Path audioRaw,
                                         int sampleRate, Path finalOut) {
        return phase2MuxCommand(ffmpeg, videoMkv, audioRaw, sampleRate, finalOut,
                CaptureCodecs.audio("flac"));
    }

    static List<String> phase2MuxCommand(String ffmpeg, Path videoMkv, Path audioRaw,
                                         int sampleRate, Path finalOut,
                                         CaptureCodecs.Codec audio) {
        List<String> c = new ArrayList<>();
        c.add(ffmpeg);
        c.add("-y");
        c.add("-i"); c.add(videoMkv.toAbsolutePath().toString());
        c.add("-f"); c.add("s16le");
        c.add("-ar"); c.add(String.valueOf(sampleRate));
        c.add("-ac"); c.add("2");
        c.add("-i"); c.add(audioRaw.toAbsolutePath().toString());
        c.add("-c:v"); c.add("copy");
        c.addAll(audio.arguments());
        c.add(finalOut.toAbsolutePath().toString());
        return c;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Search a PATH-like string for the ffmpeg executable. */
    static Optional<Path> findFfmpegOnPath(String pathEnv) {
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        String exe = isWindows() ? "ffmpeg.exe" : "ffmpeg";
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path candidate = Paths.get(dir, exe);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static Optional<Path> findFfmpeg() {
        return findFfmpegOnPath(System.getenv("PATH"));
    }

    // ---- CaptureEncoder lifecycle (exercised by the Task 4 smoke test) ----

    @Override
    public void open(Path output, int width, int height, int fps, int sampleRate)
            throws CaptureException {
        this.finalOut = output;
        this.sampleRate = sampleRate;
        try {
            Files.createDirectories(output.toAbsolutePath().getParent());
            // Beside the final file, not java.io.tmpdir. The phase-1 video is
            // lossless FFV1 and the raw audio is uncompressed, so together they
            // are the same order of magnitude as the finished recording -- on
            // Linux /tmp is routinely a RAM-backed tmpfs a fraction of the disk
            // size, where a long capture dies partway with ENOSPC. The output
            // directory is the one the user chose to receive a file this big.
            Path workingDir = output.toAbsolutePath().getParent();
            this.tempVideo = Files.createTempFile(workingDir, "capture-", ".video.mkv");
            this.tempAudio = Files.createTempFile(workingDir, "capture-", ".audio.raw");
            this.videoProc = launcher.start(FfmpegCommandTemplate.usesBuiltIn(pass1Override)
                    ? phase1Command(ffmpeg, tempVideo, width, height, fps, scale, videoCodec)
                    : prefixed(FfmpegCommandTemplate.expand(pass1Override, java.util.Map.of(
                            "width", Integer.toString(width),
                            "height", Integer.toString(height),
                            "fps", Integer.toString(fps),
                            "scale", Integer.toString(this.scale),
                            "scaledWidth", Integer.toString(width * this.scale),
                            "scaledHeight", Integer.toString(height * this.scale),
                            "videoCodecArgs", String.join(" ", videoCodec.arguments()),
                            "videoOut", tempVideo.toAbsolutePath().toString()))));
            this.videoStdin = videoProc.getOutputStream();
            this.audioOut = Files.newOutputStream(tempAudio);
            this.stderrDrain = drainAsync(videoProc);
        } catch (IOException e) {
            String detail = ffmpegDiagnostics();
            abort();
            throw new CaptureException(
                    "failed to start ffmpeg video process" + detail, e);
        }
    }

    @Override
    public void encode(CapturedFrame frame) throws CaptureException {
        try {
            videoStdin.write(frame.rgba());
            // s16le little-endian PCM for this frame
            short[] pcm = frame.pcm();
            int n = frame.sampleCount() * 2;
            byte[] bytes = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                short s = pcm[i];
                bytes[i * 2] = (byte) (s & 0xFF);
                bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            audioOut.write(bytes);
        } catch (IOException e) {
            String detail = ffmpegDiagnostics();
            abort();
            throw new CaptureException("ffmpeg write failed" + detail, e);
        }
    }

    @Override
    public Path finish() throws CaptureException {
        synchronized (lifecycleLock) {
            if (aborted) throw new CaptureException("ffmpeg capture aborted");
            if (terminal) throw new CaptureException("ffmpeg capture already finalized");
        }
        boolean success = false;
        try {
            int vexit = finishVideoProcess();
            if (stderrDrain != null) stderrDrain.join(2000);
            closeAudioOut();
            if (vexit != 0) throw new CaptureException("ffmpeg video exited " + vexit);
            if (skipsMux()) {
                // Publish the first pass's output as-is. The raw audio is
                // discarded with the other temporaries: there is no second pass
                // to mux it in, so the recording is video-only by request.
                Files.createDirectories(finalOut.toAbsolutePath().getParent());
                Files.move(tempVideo, finalOut,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                lifecycleSeam.beforeSuccessPublication();
                synchronized (lifecycleLock) {
                    if (aborted) {
                        throw new CaptureException(
                                "ffmpeg capture aborted before success publication");
                    }
                    terminal = true;
                    success = true;
                }
                return finalOut;
            }
            // phase 2 mux
            Process launchedMux = launcher.start(
                    FfmpegCommandTemplate.usesBuiltIn(pass2Override)
                            ? phase2MuxCommand(ffmpeg, tempVideo, tempAudio, sampleRate,
                                    finalOut, audioCodec)
                            : prefixed(FfmpegCommandTemplate.expand(pass2Override,
                                    java.util.Map.of(
                                            "videoIn", tempVideo.toAbsolutePath().toString(),
                                            "audioIn", tempAudio.toAbsolutePath().toString(),
                                            "sampleRate", Integer.toString(sampleRate),
                                            "audioCodecArgs", String.join(" ", audioCodec.arguments()),
                                            "output", finalOut.toAbsolutePath().toString()))));
            lifecycleSeam.beforeMuxPublication(launchedMux);
            synchronized (lifecycleLock) {
                if (aborted) {
                    destroyAndWait(launchedMux);
                    throw new CaptureException("ffmpeg capture aborted before mux publication");
                }
                muxProc = launchedMux;
            }
            Thread muxDrain = drainAsync(launchedMux);
            int mexit = waitForProcess(launchedMux, "mux");
            muxDrain.join(2000);
            if (mexit != 0) throw new CaptureException("ffmpeg mux exited " + mexit);
            lifecycleSeam.beforeSuccessPublication();
            synchronized (lifecycleLock) {
                if (aborted) throw new CaptureException("ffmpeg capture aborted before success publication");
                terminal = true;
                success = true;
            }
            return finalOut;
        } catch (IOException e) {
            throw new CaptureException("ffmpeg finish failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureException("interrupted during ffmpeg finish", e);
        } finally {
            closeAudioOutQuietly();
            deleteQuietly(tempVideo);
            deleteQuietly(tempAudio);
            if (!success) {
                destroyAndWait(videoProc);
                destroyAndWait(muxProc);
                deleteQuietly(finalOut);
                synchronized (lifecycleLock) { terminal = true; }
            }
        }
    }

    @Override
    public void abort() {
        abortUntil(System.nanoTime() + Duration.ofMillis(processTimeoutMillis).toNanos());
    }

    void abortUntil(long deadlineNanos) {
        Process video;
        Process mux;
        synchronized (lifecycleLock) {
            if (aborted || terminal) return;
            aborted = true;
            video = videoProc;
            mux = muxProc;
        }
        // A frame writer can hold stdin's monitor while blocked on a full pipe.
        // Killing the reader releases that write; closing/flushing stdin first
        // can wait forever and never reach the process termination below.
        forceDestroy(video);
        forceDestroy(mux);
        try { if (videoStdin != null) videoStdin.close(); } catch (IOException ignored) { }
        closeAudioOutQuietly();
        waitUntil(video, deadlineNanos);
        waitUntil(mux, deadlineNanos);
        deleteQuietly(tempVideo);
        deleteQuietly(tempAudio);
        deleteQuietly(finalOut);
        synchronized (lifecycleLock) { terminal = true; }
    }

    private static void forceDestroy(Process process) {
        if (process != null && process.isAlive()) process.destroyForcibly();
    }

    private static void waitUntil(Process process, long deadlineNanos) {
        if (process == null) return;
        long remaining = Math.max(0, deadlineNanos - System.nanoTime());
        try {
            process.waitFor(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int waitForProcess(Process process, String phase)
            throws InterruptedException, CaptureException {
        if (!process.waitFor(processTimeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(processTimeoutMillis, TimeUnit.MILLISECONDS);
            throw new CaptureException("ffmpeg " + phase + " process timed out");
        }
        return process.exitValue();
    }

    private int finishVideoProcess() throws IOException, InterruptedException, CaptureException {
        Process video = videoProc;
        AtomicBoolean timedOut = new AtomicBoolean();
        // Closing buffered stdin can itself block while flushing to a full pipe.
        // The phase deadline must therefore run independently of the writer and
        // kill the reader before attempting any stream cleanup.
        try (var watchdog = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("ffmpeg-video-finalization-timeout").factory())) {
            var timeout = watchdog.schedule(() -> {
                if (video.isAlive()) {
                    timedOut.set(true);
                    forceDestroy(video);
                }
            }, processTimeoutMillis, TimeUnit.MILLISECONDS);
            try {
                videoStdin.close(); // EOF -> ffmpeg finalizes video
                int exit = waitForProcess(video, "video");
                if (timedOut.get()) {
                    throw new CaptureException("ffmpeg video process timed out");
                }
                return exit;
            } catch (IOException failure) {
                if (timedOut.get()) {
                    throw new CaptureException("ffmpeg video process timed out while closing stdin", failure);
                }
                throw failure;
            } finally {
                timeout.cancel(false);
            }
        }
    }

    private void destroyAndWait(Process process) {
        if (process == null) return;
        if (process.isAlive()) process.destroyForcibly();
        try {
            process.waitFor(processTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> prefixed(List<String> arguments) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(ffmpeg);
        command.addAll(arguments);
        return command;
    }

    private void retainDiagnostics(String chunk) {
        synchronized (diagnostics) {
            diagnostics.append(chunk);
            int excess = diagnostics.length() - DIAGNOSTICS_LIMIT;
            if (excess > 0) {
                diagnostics.delete(0, excess);
            }
        }
    }

    /**
     * The last of ffmpeg's own output, for attaching to a failure. ffmpeg
     * reports why it stopped on stderr and then exits; the write that fails
     * afterwards only knows the pipe is gone.
     */
    private String ffmpegDiagnostics() {
        synchronized (diagnostics) {
            String tail = diagnostics.toString().strip();
            return tail.isEmpty() ? "" : "; ffmpeg said: " + lastLines(tail, 3);
        }
    }

    private static String lastLines(String text, int count) {
        String[] lines = text.split("\\R");
        int from = Math.max(0, lines.length - count);
        return String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private Thread drainAsync(Process p) {
        Thread t = new Thread(() -> {
            try (var in = p.getErrorStream()) {
                byte[] buf = new byte[4096];
                int read;
                while ((read = in.read(buf)) >= 0) {
                    // Keep the pipe drained, but retain the tail: when ffmpeg
                    // exits, its stdin becomes a NullOutputStream and every
                    // later write fails with a bare "Stream closed". Without
                    // these lines the actual cause -- "No space left on
                    // device", an unsupported pixel format, a bad argument --
                    // is lost and the caller is left with a symptom.
                    retainDiagnostics(new String(buf, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        }, "ffmpeg-stderr-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void closeAudioOut() throws IOException {
        if (audioOut != null) {
            audioOut.close();
            audioOut = null;
        }
    }

    private void closeAudioOutQuietly() {
        try {
            closeAudioOut();
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }
}
