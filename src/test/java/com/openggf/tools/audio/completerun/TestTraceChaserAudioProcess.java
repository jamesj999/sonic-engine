package com.openggf.tools.audio.completerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestTraceChaserAudioProcess {
    @TempDir Path temporary;

    @Test
    void invokesThePinnedS2CommandAsAnExactArgumentVector() throws Exception {
        Fixture fixture = fixture("root with spaces");
        Path output = temporary.resolve("capture with ; and $(touch nope)").toAbsolutePath();

        Path raw;
        try (var result = new TraceChaserAudioProcess().capture(
                fixture.request(output), TraceChaserAudioProcess.Game.S2)) {
            raw = result.raw();
            assertEquals("raw\n", Files.readString(raw));

            assertEquals(List.of(
                    "--complete-audio-game", "s2",
                    "--rom", fixture.rom.toString(),
                    "--movie", fixture.movie.toString(),
                    "--service-manifest", fixture.serviceManifest.toString(),
                    "--capability", fixture.capability.toString(),
                    "--output", raw.toString()), Files.readAllLines(fixture.argvLog));
        }
        assertFalse(Files.exists(raw));
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(temporary.resolve("nope")));
    }

    @Test
    void invokesS3kWithoutTheS2CapabilityArgument() throws Exception {
        Fixture fixture = fixture("s3k-root");
        Path output = temporary.resolve("s3k-capture").toAbsolutePath();

        Path raw;
        try (var result = new TraceChaserAudioProcess().capture(
                fixture.request(output), TraceChaserAudioProcess.Game.S3K)) {
            raw = result.raw();

            assertEquals(List.of(
                    "--complete-audio-game", "s3k",
                    "--rom", fixture.rom.toString(),
                    "--movie", fixture.movie.toString(),
                    "--service-manifest", fixture.serviceManifest.toString(),
                    "--output", raw.toString()), Files.readAllLines(fixture.argvLog));
        }
        assertFalse(Files.exists(raw));
    }

    @Test
    void rejectsNonCanonicalOrLinkedInputsBeforeStartingAProcess() throws Exception {
        Fixture fixture = fixture("strict-root");
        Path realManifest = fixture.referenceHome.resolve("real-manifest.json");
        Files.move(fixture.serviceManifest, realManifest);
        Files.createSymbolicLink(fixture.serviceManifest, realManifest);
        Path output = temporary.resolve("not-started").toAbsolutePath();

        assertThrows(IllegalArgumentException.class, () -> new TraceChaserAudioProcess().capture(
                fixture.request(output), TraceChaserAudioProcess.Game.S2));

        assertFalse(Files.exists(fixture.argvLog));
        assertFalse(Files.exists(output));
    }

    @Test
    void propagatesNonzeroExitWithBoundedStderrAndRemovesItsUnpublishedRawFile() throws Exception {
        Fixture fixture = fixture("failing-root");
        Files.writeString(fixture.launcher, "#!/bin/sh\n"
                + "output=\n"
                + "while [ \"$#\" -gt 0 ]; do\n"
                + "  if [ \"$1\" = --output ]; then output=$2; shift 2; else shift; fi\n"
                + "done\n"
                + "printf partial > \"$output\"\n"
                + "i=0; while [ \"$i\" -lt 70000 ]; do printf x >&2; i=$((i + 1)); done\n"
                + "exit 23\n");
        fixture.launcher.toFile().setExecutable(true, true);
        Path output = temporary.resolve("failed").toAbsolutePath();

        IOException failure = assertThrows(IOException.class,
                () -> new TraceChaserAudioProcess().capture(
                        fixture.request(output), TraceChaserAudioProcess.Game.S2));

        assertTrue(failure.getMessage().contains("23"));
        assertTrue(failure.getMessage().length() <= TraceChaserAudioProcess.MAX_STDERR_BYTES + 256);
        assertFalse(Files.exists(output));
        try (var entries = Files.list(temporary)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".audio-reference-")));
        }
    }

    @Test
    void interruptionWaitsForChildDeathAndStderrDrainBeforeRemovingStaging() throws Exception {
        Fixture fixture = fixture("interrupted-root");
        Path output = temporary.resolve("interrupted").toAbsolutePath();
        DelayedTerminationProcess child = new DelayedTerminationProcess(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();

        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                new TraceChaserAudioProcess(invocation -> {
                    List<String> arguments = invocation.arguments();
                    child.staging.set(Path.of(arguments.get(arguments.indexOf("--output") + 1)).getParent());
                    return child;
                }).capture(
                        fixture.request(output), TraceChaserAudioProcess.Game.S2);
            } catch (Throwable problem) {
                failure.set(problem);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });
        assertTrue(child.firstWaitEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(hasStagingDirectory());

        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "capture did not return after interruption");
        assertTrue(failure.get() instanceof InterruptedException);
        assertTrue(interruptPreserved.get(), "capture did not restore the interrupt status");
        assertTrue(java.util.Arrays.stream(failure.get().getSuppressed())
                .anyMatch(IOException.class::isInstance),
                "capture did not retain the stderr-drain cleanup failure");
        assertFalse(child.isAlive(),
                "capture returned while the TraceChaser child was still alive");
        assertTrue(child.stderrClosed.get(), "capture returned before the stderr reader drained");
        assertTrue(child.stagingPresentAtTermination.get(),
                "capture removed staging before the child termination was observed");
        assertTrue(child.stagingPresentAtStderrClose.get(),
                "capture removed staging before the stderr reader closed");
        assertFalse(hasStagingDirectory(), "capture returned before staging cleanup completed");
        assertFalse(Files.exists(output));
    }

    @Test
    void customProcessReceivesOnlyTheFixedEnvironmentAndPrivateWorkingDirectory() throws Exception {
        Fixture fixture = fixture("isolated-process");
        Path output = temporary.resolve("isolated-output").toAbsolutePath();
        AtomicReference<TraceChaserAudioProcess.Invocation> observed = new AtomicReference<>();

        try (var result = new TraceChaserAudioProcess(invocation -> {
            observed.set(invocation);
            Path raw = Path.of(invocation.arguments().get(invocation.arguments().indexOf("--output") + 1));
            Files.writeString(raw, "raw\n");
            return new CompletedProcess();
        }).capture(fixture.request(output), TraceChaserAudioProcess.Game.S3K)) {
            assertEquals("raw\n", Files.readString(result.raw()));
        }

        var invocation = observed.get();
        assertEquals(Map.of("PATH", "/usr/bin:/bin", "LC_ALL", "C", "LANG", "C",
                "HOME", invocation.directory().getParent().resolve("home").toString()),
                invocation.environment());
        assertEquals("work", invocation.directory().getFileName().toString());
        assertFalse(Files.exists(invocation.directory().getParent()),
                "the private cwd and HOME must be removed with raw staging");
    }

    @Test
    void resultCloseNeverDeletesAReplacementAtItsOwnedStagingPath() throws Exception {
        Fixture fixture = fixture("replacement-safe");
        Path output = temporary.resolve("replacement-output").toAbsolutePath();
        TraceChaserAudioProcess.Result result = new TraceChaserAudioProcess(invocation -> {
            Path raw = Path.of(invocation.arguments().get(invocation.arguments().indexOf("--output") + 1));
            Files.writeString(raw, "raw\n");
            return new CompletedProcess();
        }).capture(fixture.request(output), TraceChaserAudioProcess.Game.S2);
        Path owned = result.raw().getParent();
        Path moved = owned.resolveSibling("moved-audio-reference");
        Files.move(owned, moved);
        Files.createDirectory(owned);
        Files.writeString(owned.resolve("foreign-sentinel"), "keep");

        assertThrows(IOException.class, result::close);
        assertEquals("keep", Files.readString(owned.resolve("foreign-sentinel")));

        deleteForTest(owned);
        deleteForTest(moved);
    }

    private static void deleteForTest(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private Fixture fixture(String directory) throws IOException {
        Path referenceHome = Files.createDirectories(temporary.resolve(directory)).toAbsolutePath();
        Path tool = Files.createDirectories(referenceHome.resolve("bizhawk-headless"));
        Path fixtures = Files.createDirectories(tool.resolve("fixtures"));
        Path launcher = tool.resolve("run-complete-audio.sh");
        Path argvLog = referenceHome.resolve("argv.txt");
        Files.writeString(launcher, "#!/bin/sh\n"
                + "printf '%s\\n' \"$@\" > '" + argvLog + "'\n"
                + "output=\n"
                + "while [ \"$#\" -gt 0 ]; do\n"
                + "  if [ \"$1\" = --output ]; then output=$2; shift 2; else shift; fi\n"
                + "done\n"
                + "printf 'raw\\n' > \"$output\"\n");
        launcher.toFile().setExecutable(true, true);
        Path serviceManifest = Files.writeString(fixtures.resolve(
                "gpgx-audio-service-manifests-v1.json"), "manifest").toAbsolutePath();
        Path capability = Files.writeString(fixtures.resolve(
                "gpgx-audio-capability-v1.json"), "capability").toAbsolutePath();
        Path rom = Files.writeString(temporary.resolve(directory + ".gen"), "rom").toAbsolutePath();
        Path movie = Files.writeString(temporary.resolve(directory + ".bk2"), "movie").toAbsolutePath();
        Path runManifest = Files.writeString(temporary.resolve(directory + "-run.json"), "run").toAbsolutePath();
        return new Fixture(referenceHome, launcher, serviceManifest, capability, argvLog,
                rom, movie, runManifest);
    }

    private boolean hasStagingDirectory() {
        try (var entries = Files.list(temporary)) {
            return entries.anyMatch(path -> path.getFileName().toString().startsWith(".audio-reference-"));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Fixture(Path referenceHome, Path launcher, Path serviceManifest,
            Path capability, Path argvLog, Path rom, Path movie, Path runManifest) {
        CompleteRunAudioProducer.Request request(Path output) {
            return new CompleteRunAudioProducer.Request(ProducerKind.REFERENCE, "profile",
                    rom, movie, runManifest, referenceHome, output);
        }
    }

    private static final class CompletedProcess extends Process {
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
    }

    private static final class DelayedTerminationProcess extends Process {
        private final CountDownLatch firstWaitEntered = new CountDownLatch(1);
        private final PipedInputStream stderr = new PipedInputStream();
        private final PipedOutputStream stderrWriter;
        private final CountDownLatch terminated = new CountDownLatch(1);
        private final AtomicBoolean stderrClosed = new AtomicBoolean();
        private final AtomicBoolean stagingPresentAtTermination = new AtomicBoolean();
        private final AtomicBoolean stagingPresentAtStderrClose = new AtomicBoolean();
        private final AtomicReference<Path> staging = new AtomicReference<>();
        private final boolean failStderr;
        private int waits;
        private volatile boolean alive = true;

        private DelayedTerminationProcess(boolean failStderr) throws IOException {
            this.failStderr = failStderr;
            stderrWriter = new PipedOutputStream(stderr);
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() {
            InputStream source = failStderr ? new InputStream() {
                @Override public int read() throws IOException {
                    try { terminated.await(); }
                    catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException("stderr reader interrupted", failure);
                    }
                    throw new IOException("synthetic stderr drain failure");
                }
            } : stderr;
            return new java.io.FilterInputStream(source) {
                @Override public void close() throws IOException {
                    super.close();
                    stagingPresentAtStderrClose.set(Files.isDirectory(staging.get()));
                    stderrClosed.set(true);
                }
            };
        }

        @Override public synchronized int waitFor() throws InterruptedException {
            waits++;
            if (waits == 1) {
                firstWaitEntered.countDown();
                new CountDownLatch(1).await();
            }
            alive = false;
            stagingPresentAtTermination.set(Files.isDirectory(staging.get()));
            terminated.countDown();
            try { stderrWriter.close(); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
            return 137;
        }

        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException("alive");
            return 137;
        }

        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
