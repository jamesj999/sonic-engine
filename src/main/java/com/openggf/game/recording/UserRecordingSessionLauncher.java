package com.openggf.game.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.GameLoop;
import com.openggf.TraceSessionLauncher;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.level.LevelManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class UserRecordingSessionLauncher {
    private static final String ROUTE_CURRENT_ACT_FRESH_START = "current-act-fresh-start";
    private static final String MANIFEST_ENTRY = "OpenGGF/manifest.json";
    private static final String DESYNC_LITE_ENTRY = "OpenGGF/desync-lite.jsonl";
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final ObjectMapper SIDECAR_MAPPER = new ObjectMapper();

    private final GameLoopAdapter gameLoop;
    private final LaunchContextSource launchContextSource;
    private final TraceModeGuard traceModeGuard;
    private final PlaybackAdapter playback;
    private final Bk2MovieLoader movieLoader;
    private final Path recordingRoot;
    private final Supplier<Instant> clock;
    private final ZoneId timestampZone;

    private UserRecordingSession activeRecordingSession;
    private UserRecordingVerifier activeVerifier;
    private UserRecordingPlaybackOptions activePlaybackOptions;
    private UserRecordingPlaybackState activePlaybackState;

    public UserRecordingSessionLauncher(GameLoop gameLoop) {
        this(
                new LiveGameLoopAdapter(gameLoop),
                new LiveLaunchContextSource(),
                new LiveTraceModeGuard(),
                new LivePlaybackAdapter(GameServices.playbackDebug()),
                new Bk2MovieLoader(),
                Path.of(System.getProperty("user.dir", ".")),
                Instant::now,
                ZoneId.systemDefault());
    }

    UserRecordingSessionLauncher(GameLoopAdapter gameLoop,
            LaunchContextSource launchContextSource,
            TraceModeGuard traceModeGuard,
            PlaybackAdapter playback,
            Path recordingRoot,
            Supplier<Instant> clock,
            ZoneId timestampZone) {
        this(gameLoop, launchContextSource, traceModeGuard, playback, new Bk2MovieLoader(),
                recordingRoot, clock, timestampZone);
    }

    UserRecordingSessionLauncher(GameLoopAdapter gameLoop,
            LaunchContextSource launchContextSource,
            TraceModeGuard traceModeGuard,
            PlaybackAdapter playback,
            Bk2MovieLoader movieLoader,
            Path recordingRoot,
            Supplier<Instant> clock,
            ZoneId timestampZone) {
        this.gameLoop = Objects.requireNonNull(gameLoop, "gameLoop");
        this.launchContextSource = Objects.requireNonNull(launchContextSource, "launchContextSource");
        this.traceModeGuard = Objects.requireNonNull(traceModeGuard, "traceModeGuard");
        this.playback = Objects.requireNonNull(playback, "playback");
        this.movieLoader = Objects.requireNonNull(movieLoader, "movieLoader");
        this.recordingRoot = Objects.requireNonNull(recordingRoot, "recordingRoot");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timestampZone = Objects.requireNonNull(timestampZone, "timestampZone");
    }

    public RecordingLaunchContext captureCurrentLaunchContext() {
        return launchContextSource.captureCurrentLaunchContext();
    }

    public UserRecordingSession beginRecordingFromCurrentLevel() {
        if (gameLoop.currentGameMode() != GameMode.LEVEL) {
            throw new IllegalStateException("User recording can only start from LEVEL mode.");
        }
        if (traceModeGuard.isTraceOrTestModeActive()) {
            throw new IllegalStateException("User recording is disabled while Trace Test playback is active.");
        }

        RecordingLaunchContext context = captureCurrentLaunchContext();
        gameLoop.restartFromRecordingLaunchContext(context);
        activeRecordingSession = new UserRecordingSession(context, recordingPathFor(context));
        return activeRecordingSession;
    }

    public UserRecordingPlaybackState beginPlayback(UserRecordingEntry entry,
            UserRecordingPlaybackOptions options) throws IOException {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(options, "options");

        UserRecordingManifest manifest = readManifest(entry.path());
        RecordingLaunchContext context = manifest.launchContext();
        Bk2Movie movie = movieLoader.load(entry.path());
        UserRecordingVerifier verifier = createVerifier(entry.path(), manifest);

        gameLoop.restartFromRecordingLaunchContext(context);
        playback.startSession(movie, 0);
        playback.setFrameObserver(verifier.observer());

        activeVerifier = verifier;
        activePlaybackOptions = options;
        activePlaybackState = UserRecordingPlaybackState.PLAYING;
        return activePlaybackState;
    }

    Path recordingPathFor(RecordingLaunchContext context) {
        Objects.requireNonNull(context, "context");
        String zoneAct = context.zone() + "-" + context.act();
        String timestamp = FILE_TIMESTAMP.format(clock.get().atZone(timestampZone));
        String fileName = context.gameId() + "-" + zoneAct + "-" + timestamp + ".bk2";
        return recordingRoot.resolve("recordings").resolve(context.gameId()).resolve(fileName);
    }

    UserRecordingSession activeRecordingSession() {
        return activeRecordingSession;
    }

    public boolean hasActiveRecordingSession() {
        return activeRecordingSession != null && activeRecordingSession.isActive();
    }

    public void beforeActiveRecordingLevelFrame(com.openggf.control.InputHandler input) {
        if (hasActiveRecordingSession()) {
            activeRecordingSession.beforeLevelFrame(input);
        }
    }

    public void afterActiveRecordingLevelFrame() {
        if (hasActiveRecordingSession()) {
            activeRecordingSession.afterLevelFrame();
        }
    }

    public void stopActiveRecording(UserRecordingStopReason reason) {
        if (hasActiveRecordingSession()) {
            activeRecordingSession.requestStop(reason);
        }
    }

    public UserRecordingHudState activeRecordingHudState() {
        if (activeRecordingSession == null) {
            return null;
        }
        return activeRecordingSession.hudState();
    }

    UserRecordingVerifier activeVerifier() {
        return activeVerifier;
    }

    UserRecordingPlaybackOptions activePlaybackOptions() {
        return activePlaybackOptions;
    }

    UserRecordingPlaybackState activePlaybackState() {
        return activePlaybackState;
    }

    public UserRecordingPlaybackOptions currentPlaybackOptions() {
        return activePlaybackOptions;
    }

    public UserRecordingPlaybackState currentPlaybackState() {
        return activePlaybackState == null ? UserRecordingPlaybackState.STOPPED : activePlaybackState;
    }

    public boolean activePlaybackHasDesynced() {
        return activeVerifier != null && activeVerifier.hasMismatch();
    }

    public UserRecordingVerificationResult currentPlaybackVerificationResult() {
        return activeVerifier == null ? null : activeVerifier.result();
    }

    public void updateActivePlaybackState(UserRecordingPlaybackState state) {
        activePlaybackState = state == null ? UserRecordingPlaybackState.STOPPED : state;
    }

    public void endPlaybackSession() {
        playback.endSession();
        activeVerifier = null;
        activePlaybackOptions = null;
        activePlaybackState = UserRecordingPlaybackState.STOPPED;
    }

    private static UserRecordingManifest readManifest(Path bk2Path) throws IOException {
        try (ZipFile zip = new ZipFile(bk2Path.toFile())) {
            ZipEntry entry = findEntryIgnoreCase(zip, MANIFEST_ENTRY);
            if (entry == null) {
                throw new IOException("BK2 missing required entry: " + MANIFEST_ENTRY);
            }
            try (var in = zip.getInputStream(entry)) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return UserRecordingJson.readManifest(json);
            }
        }
    }

    private static UserRecordingVerifier createVerifier(Path bk2Path, UserRecordingManifest manifest)
            throws IOException {
        UserRecordingSidecarMetadata sidecar = manifest.sidecar();
        if (sidecar != null
                && sidecar.desyncLiteSchemaVersion()
                > UserRecordingSidecarMetadata.CURRENT_DESYNC_LITE_SCHEMA_VERSION) {
            return UserRecordingVerifier.unsupportedSchema(sidecar.desyncLiteSchemaVersion());
        }
        SidecarReadResult sidecarRead = readDesyncLiteWithPresence(bk2Path);
        if (!sidecarRead.present()) {
            return UserRecordingVerifier.missingSidecar();
        }
        return new UserRecordingVerifier(sidecarRead.frames(),
                sidecar == null ? UserRecordingSidecarMetadata.everyFrame() : sidecar);
    }

    private static SidecarReadResult readDesyncLiteWithPresence(Path bk2Path) throws IOException {
        try (ZipFile zip = new ZipFile(bk2Path.toFile())) {
            ZipEntry entry = findEntryIgnoreCase(zip, DESYNC_LITE_ENTRY);
            if (entry == null) {
                return new SidecarReadResult(false, List.of());
            }
            List<DesyncLiteFrame> frames = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        frames.add(SIDECAR_MAPPER.readValue(trimmed, DesyncLiteFrame.class));
                    }
                }
            }
            return new SidecarReadResult(true, frames);
        }
    }

    private record SidecarReadResult(boolean present, List<DesyncLiteFrame> frames) {
    }

    private static ZipEntry findEntryIgnoreCase(ZipFile zip, String expectedName) {
        String expected = expectedName.toLowerCase(java.util.Locale.ROOT);
        return zip.stream()
                .filter(entry -> entry.getName().toLowerCase(java.util.Locale.ROOT).equals(expected))
                .findFirst()
                .orElse(null);
    }

    interface GameLoopAdapter {
        GameMode currentGameMode();

        void restartFromRecordingLaunchContext(RecordingLaunchContext context);
    }

    interface LaunchContextSource {
        RecordingLaunchContext captureCurrentLaunchContext();
    }

    interface TraceModeGuard {
        boolean isTraceOrTestModeActive();
    }

    interface PlaybackAdapter {
        void startSession(Bk2Movie movie, int startOffsetIndex);

        void setFrameObserver(PlaybackDebugManager.PlaybackFrameObserver observer);

        void endSession();
    }

    private record LiveGameLoopAdapter(GameLoop loop) implements GameLoopAdapter {
        private LiveGameLoopAdapter {
            Objects.requireNonNull(loop, "loop");
        }

        @Override
        public GameMode currentGameMode() {
            return loop.getCurrentGameMode();
        }

        @Override
        public void restartFromRecordingLaunchContext(RecordingLaunchContext context) {
            loop.restartFromRecordingLaunchContext(context);
        }
    }

    private static final class LiveLaunchContextSource implements LaunchContextSource {
        @Override
        public RecordingLaunchContext captureCurrentLaunchContext() {
            var config = GameServices.configuration();
            LevelManager level = GameServices.level();
            return new RecordingLaunchContext(
                    GameServices.module().getGameId().code(),
                    level.getCurrentZone(),
                    level.getCurrentAct(),
                    ActiveGameplayTeamResolver.resolveMainCharacterCode(config),
                    ActiveGameplayTeamResolver.resolveSidekicks(config),
                    config.getBoolean(com.openggf.configuration.SonicConfiguration.DEBUG_VIEW_ENABLED),
                    ROUTE_CURRENT_ACT_FRESH_START);
        }
    }

    private static final class LiveTraceModeGuard implements TraceModeGuard {
        @Override
        public boolean isTraceOrTestModeActive() {
            var config = GameServices.configuration();
            return TraceSessionLauncher.active() != null
                    || config.getBoolean(com.openggf.configuration.SonicConfiguration.TEST_MODE_ENABLED)
                    || GameServices.playbackDebug().isDriving(GameMode.LEVEL);
        }
    }

    private record LivePlaybackAdapter(PlaybackDebugManager playbackDebugManager) implements PlaybackAdapter {
        private LivePlaybackAdapter {
            Objects.requireNonNull(playbackDebugManager, "playbackDebugManager");
        }

        @Override
        public void startSession(Bk2Movie movie, int startOffsetIndex) {
            playbackDebugManager.startSession(movie, startOffsetIndex);
        }

        @Override
        public void setFrameObserver(PlaybackDebugManager.PlaybackFrameObserver observer) {
            playbackDebugManager.setFrameObserver(observer);
        }

        @Override
        public void endSession() {
            playbackDebugManager.endSession();
        }
    }
}
