package com.openggf.tools.audio.completerun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed subprocess boundary for TraceChaser's complete-audio command. */
public final class TraceChaserAudioProcess {
    public static final int MAX_STDERR_BYTES = 64 * 1024;
    private static final Path LAUNCHER = Path.of("bizhawk-headless/run-complete-audio.sh");
    private static final Path SERVICE_MANIFEST =
            Path.of("bizhawk-headless/fixtures/gpgx-audio-service-manifests-v1.json");
    private static final Path CAPABILITY =
            Path.of("bizhawk-headless/fixtures/gpgx-audio-capability-v1.json");
    private final ProcessFactory processFactory;

    @FunctionalInterface
    interface ProcessFactory {
        Process start(Invocation invocation) throws IOException;
    }

    record Invocation(List<String> arguments, Map<String, String> environment, Path directory) {
        Invocation {
            arguments = List.copyOf(arguments);
            environment = Map.copyOf(environment);
            Objects.requireNonNull(directory, "process working directory");
        }
    }

    public TraceChaserAudioProcess() {
        this(invocation -> {
            ProcessBuilder builder = new ProcessBuilder(invocation.arguments())
                    .directory(invocation.directory().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            builder.environment().putAll(invocation.environment());
            return builder.start();
        });
    }

    TraceChaserAudioProcess(ProcessFactory processFactory) {
        this.processFactory = Objects.requireNonNull(processFactory, "process factory");
    }

    public enum Game { S2("s2"), S3K("s3k");
        private final String selector;
        Game(String selector) { this.selector = selector; }
    }

    public static final class Result implements AutoCloseable {
        private final Path raw;
        private final OwnedTemporaryDirectory staging;
        private boolean closed;

        private Result(Path raw, OwnedTemporaryDirectory staging) {
            this.raw = raw;
            this.staging = staging;
        }

        public Path raw() { return raw; }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            staging.delete();
        }
    }

    public Result capture(CompleteRunAudioProducer.Request request, Game game)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "complete-run request");
        Objects.requireNonNull(game, "TraceChaser game");
        Path referenceHome = directory(request.referenceHome(), "TraceChaser root");
        Path launcher = file(referenceHome.resolve(LAUNCHER), "TraceChaser complete-audio launcher");
        if (!Files.isExecutable(launcher)) {
            throw new IllegalArgumentException("TraceChaser complete-audio launcher is not executable");
        }
        Path rom = file(request.rom(), "ROM");
        Path movie = file(request.bk2(), "BK2 movie");
        Path serviceManifest = file(referenceHome.resolve(SERVICE_MANIFEST), "service manifest");
        Path capability = game == Game.S2
                ? file(referenceHome.resolve(CAPABILITY), "S2 capability fixture") : null;
        Path output = absoluteNew(request.output(), "canonical capture output");
        Path parent = directory(output.getParent(), "canonical capture output parent");
        OwnedTemporaryDirectory staging = OwnedTemporaryDirectory.create(parent, ".audio-reference-");
        Path home = Files.createDirectory(staging.path().resolve("home"));
        Path work = Files.createDirectory(staging.path().resolve("work"));
        Path raw = staging.path().resolve("raw.jsonl");

        List<String> argv = new ArrayList<>();
        argv.add(launcher.toString());
        argv.add("--complete-audio-game");
        argv.add(game.selector);
        argv.add("--rom");
        argv.add(rom.toString());
        argv.add("--movie");
        argv.add(movie.toString());
        argv.add("--service-manifest");
        argv.add(serviceManifest.toString());
        if (capability != null) {
            argv.add("--capability");
            argv.add(capability.toString());
        }
        argv.add("--output");
        argv.add(raw.toString());

        Process process = null;
        Thread reader = null;
        BoundedStderr stderr = null;
        try {
            process = processFactory.start(new Invocation(argv,
                    Map.of("PATH", "/usr/bin:/bin", "LC_ALL", "C", "LANG", "C",
                            "HOME", home.toString()), work));
            stderr = new BoundedStderr(process.getErrorStream());
            reader = Thread.ofVirtual().start(stderr);
            int exit = process.waitFor();
            reader.join();
            if (stderr.failure != null) throw stderr.failure;
            if (exit != 0) {
                throw new IOException("TraceChaser complete-audio exited " + exit + ": " + stderr.text());
            }
            file(raw, "TraceChaser raw output");
            return new Result(raw, staging);
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            boolean interrupted = terminateAndDrain(process, reader, failure);
            if (stderr != null && stderr.failure != null && stderr.failure != failure) {
                failure.addSuppressed(stderr.failure);
            }
            try { staging.delete(); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            if (failure instanceof InterruptedException || interrupted) Thread.currentThread().interrupt();
            throw failure;
        }
    }

    private static boolean terminateAndDrain(Process process, Thread reader, Throwable failure) {
        boolean interrupted = false;
        if (process != null) {
            try { process.destroyForcibly(); }
            catch (RuntimeException | Error terminationFailure) {
                failure.addSuppressed(terminationFailure);
            }
            boolean reaped = false;
            while (!reaped) {
                try {
                    process.waitFor();
                    reaped = true;
                } catch (InterruptedException waitInterrupted) {
                    interrupted = true;
                    failure.addSuppressed(waitInterrupted);
                }
            }
        }
        if (reader != null) {
            boolean joined = false;
            while (!joined) {
                try {
                    reader.join();
                    joined = true;
                } catch (InterruptedException joinInterrupted) {
                    interrupted = true;
                    failure.addSuppressed(joinInterrupted);
                }
            }
        }
        return interrupted;
    }

    private static Path directory(Path value, String label) {
        Path path = absolute(value, label);
        if (!canonical(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " must be an ordinary non-symlink directory");
        }
        return path;
    }

    private static Path file(Path value, String label) {
        Path path = absolute(value, label);
        if (!canonical(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " must be an ordinary non-symlink file");
        }
        return path;
    }

    private static Path absoluteNew(Path value, String label) {
        Path path = absolute(value, label);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " already exists");
        }
        if (!Files.isDirectory(path.getParent(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " parent does not exist");
        }
        return path;
    }

    private static Path absolute(Path value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.isAbsolute() || !value.equals(value.normalize())) {
            throw new IllegalArgumentException(label + " must be an absolute normalized path");
        }
        return value;
    }

    private static boolean canonical(Path path) {
        try { return path.equals(path.toRealPath()); }
        catch (IOException missing) { return false; }
    }

    private static final class BoundedStderr implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream retained = new ByteArrayOutputStream(MAX_STDERR_BYTES);
        private IOException failure;

        private BoundedStderr(InputStream input) { this.input = input; }

        @Override public void run() {
            try (input) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    int remaining = MAX_STDERR_BYTES - retained.size();
                    if (remaining > 0) retained.write(buffer, 0, Math.min(remaining, count));
                }
            } catch (IOException problem) {
                failure = problem;
            }
        }

        private String text() {
            return retained.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
