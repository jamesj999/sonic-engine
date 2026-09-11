package com.openggf.game.recording;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.control.PlayerInputState;
import com.openggf.game.session.EngineServices;
import com.openggf.version.AppVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class UserRecordingSession {
    private final RecordingLaunchContext launchContext;
    private final Path outputBk2Path;
    private final RecordingFileWriter writer;
    private final SidecarSnapshotSource snapshotSource;
    private final Supplier<Instant> clock;
    private final List<RecordedFrameInput> bufferedInputs = new ArrayList<>();
    private final List<DesyncLiteFrame> bufferedSidecarFrames = new ArrayList<>();

    private int currentMovieFrame;
    private RecordedFrameInput bufferedFrameInput;
    private DesyncLiteFrame bufferedDesyncFrame;
    private UserRecordingStopReason stopReason;
    private boolean active = true;
    private String ioErrorMessage = "";

    public UserRecordingSession(RecordingLaunchContext launchContext, Path outputBk2Path) {
        this(
                launchContext,
                outputBk2Path,
                EngineServices.current().configuration(),
                UserRecordingWriter::write,
                DesyncLiteSnapshotter::capture,
                Instant::now);
    }

    UserRecordingSession(RecordingLaunchContext launchContext, Path outputBk2Path,
            SonicConfigurationService configService, RecordingFileWriter writer,
            SidecarSnapshotSource snapshotSource, Supplier<Instant> clock) {
        this.launchContext = Objects.requireNonNull(launchContext, "launchContext");
        this.outputBk2Path = Objects.requireNonNull(outputBk2Path, "outputBk2Path");
        Objects.requireNonNull(configService, "configService");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void beforeLevelFrame(InputHandler input) {
        if (!active) {
            return;
        }
        Objects.requireNonNull(input, "input");
        PlayerInputState p1 = input.logical().player1();
        PlayerInputState p2 = input.logical().player2();
        bufferedFrameInput = new RecordedFrameInput(
                currentMovieFrame,
                p1.heldMask(),
                p1.actionHeldMask(),
                p1.startHeld(),
                p2.heldMask(),
                p2.actionHeldMask(),
                p2.startHeld());
    }

    public void afterLevelFrame() {
        if (!active || bufferedFrameInput == null) {
            return;
        }
        bufferedDesyncFrame = snapshotSource.capture(currentMovieFrame);
        bufferedInputs.add(bufferedFrameInput);
        bufferedSidecarFrames.add(bufferedDesyncFrame);
        bufferedFrameInput = null;
        bufferedDesyncFrame = null;
        currentMovieFrame++;
    }

    public void requestStop(UserRecordingStopReason reason) {
        if (!active) {
            return;
        }
        UserRecordingStopReason requestedReason = reason == null ? UserRecordingStopReason.UNKNOWN : reason;
        stopReason = requestedReason;

        if (bufferedInputs.isEmpty() && requestedReason == UserRecordingStopReason.ABORTED_BEFORE_GAMEPLAY) {
            active = false;
            deleteAbortedOutput();
            return;
        }

        try {
            writer.write(outputBk2Path, manifest(requestedReason), List.copyOf(bufferedInputs),
                    List.copyOf(bufferedSidecarFrames));
            active = false;
        } catch (IOException | RuntimeException ex) {
            stopReason = UserRecordingStopReason.IO_ERROR;
            ioErrorMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            active = false;
        }
    }

    public boolean isActive() {
        return active;
    }

    public UserRecordingHudState hudState() {
        if (active) {
            return new UserRecordingHudState(
                    true,
                    "REC frame " + currentMovieFrame,
                    outputBk2Path.getFileName() == null ? outputBk2Path.toString() : outputBk2Path.getFileName().toString(),
                    currentMovieFrame,
                    false,
                    false);
        }
        if (stopReason == UserRecordingStopReason.IO_ERROR) {
            String suffix = ioErrorMessage.isBlank() ? "" : ": " + ioErrorMessage;
            return new UserRecordingHudState(
                    true,
                    "REC ERROR",
                    UserRecordingStopReason.IO_ERROR.name() + suffix,
                    currentMovieFrame,
                    false,
                    true);
        }
        UserRecordingStopReason displayedReason = stopReason == null ? UserRecordingStopReason.UNKNOWN : stopReason;
        return new UserRecordingHudState(
                true,
                "REC STOPPED",
                displayedReason.name(),
                currentMovieFrame,
                false,
                false);
    }

    RecordingLaunchContext launchContext() {
        return launchContext;
    }

    Path outputBk2Path() {
        return outputBk2Path;
    }

    int currentMovieFrame() {
        return currentMovieFrame;
    }

    RecordedFrameInput bufferedFrameInput() {
        return bufferedFrameInput;
    }

    DesyncLiteFrame bufferedDesyncFrame() {
        return bufferedDesyncFrame;
    }

    UserRecordingStopReason stopReason() {
        return stopReason;
    }

    private UserRecordingManifest manifest(UserRecordingStopReason reason) {
        String fileName = outputBk2Path.getFileName() == null ? outputBk2Path.toString() : outputBk2Path.getFileName().toString();
        return new UserRecordingManifest(
                UserRecordingManifest.CURRENT_SCHEMA_VERSION,
                fileName,
                AppVersion.identity(),
                launchContext,
                UserRecordingSidecarMetadata.everyFrame(),
                new RecordingDeterminismMetadata(null, null),
                "A",
                bufferedInputs.size(),
                reason,
                clock.get());
    }

    private void deleteAbortedOutput() {
        try {
            Files.deleteIfExists(outputBk2Path);
        } catch (IOException ex) {
            stopReason = UserRecordingStopReason.IO_ERROR;
            ioErrorMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        }
    }

    @FunctionalInterface
    interface RecordingFileWriter {
        void write(Path path, UserRecordingManifest manifest, List<RecordedFrameInput> inputs,
                List<DesyncLiteFrame> sidecarFrames) throws IOException;
    }

    @FunctionalInterface
    interface SidecarSnapshotSource {
        DesyncLiteFrame capture(int movieFrame);
    }
}
