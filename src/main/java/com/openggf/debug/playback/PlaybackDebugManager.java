package com.openggf.debug.playback;

import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.GameMode;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.BooleanSupplier;

/**
 * Runtime controller for in-engine BizHawk playback debugging.
 */
public final class PlaybackDebugManager {
    private static final Logger LOGGER = Logger.getLogger(PlaybackDebugManager.class.getName());
    private static final PlaybackDebugManager INSTANCE = new PlaybackDebugManager();
    private static final int DEFAULT_JUMP_FRAMES = 60;
    private static final int PERIODIC_LOG_INTERVAL_FRAMES = 60;

    private final Bk2MovieLoader movieLoader = new Bk2MovieLoader();

    private Bk2Movie movie;
    private PlaybackTimelineController timeline;
    private boolean enabled;
    private String statusMessage = "Playback disabled";
    private int lastAppliedMask;
    private boolean lastAppliedStart;
    private int previousActionMask;
    private boolean previousStartPressed;
    private int pendingP1ActionPressMask;
    private boolean currentForcedStartPress;
    private GameMode lastObservedMode = GameMode.LEVEL;
    private boolean overlayOwnedExternally;
    private int firstActiveFrame = -1;
    private int periodicLogCounter;
    private PlaybackFrameObserver frameObserver;
    private boolean currentTickSuppressed;
    private int preparedCursor = -1;
    private Bk2FrameInput preparedValidationFrame;
    private Bk2FrameInput preparedAppliedFrame;
    private Bk2FrameInput preparedAppliedPredecessor;
    private boolean preparedInputApplied;
    private boolean preparedSkipEvaluated;
    private int preparedVblankAdvanceCount;
    private Bk2Movie pendingLevelLoadMovie;
    private int pendingLevelLoadOffset = -1;
    private BooleanSupplier pendingLevelLoadActivationGuard;

    /**
     * Observer hook that lets an external comparator classify each BK2
     * frame as gameplay or lag and accumulate results after each tick.
     * A null observer means no gating and no callbacks (normal BK2
     * playback).
     */
    public interface PlaybackFrameObserver {
        /**
         * Purely prepares structural policy for {@code frame}. Implementations
         * must not mutate gameplay, PLC, or hardware timing state.
         */
        default void prepareFrame(Bk2FrameInput frame) {
        }

        /** Relative movie row to apply while {@code frame} remains validation authority. */
        default int appliedInputOffset(Bk2FrameInput frame) {
            return 0;
        }

        boolean shouldSkipGameplayTick(Bk2FrameInput frame);

        /** Whether a suppressed gameplay row still executed the ROM VBlank clock tick. */
        default boolean shouldAdvanceVblankOnSkippedTick(Bk2FrameInput frame) {
            return true;
        }

        /** Exact number of ROM VBlank ticks represented by a suppressed row. */
        default int vblankAdvanceCountOnSkippedTick(Bk2FrameInput frame) {
            return shouldAdvanceVblankOnSkippedTick(frame) ? 1 : 0;
        }

        void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped);

        /**
         * Whether the observer still has recorded rows it has not consumed for
         * the span it is currently comparing. Read by the level-mode transition
         * freeze to decide whether a frozen engine frame owns a recorded row --
         * see {@code GameLoop.updateLevelMode}. Default false: an observer that
         * cannot answer never grants ownership.
         */
        default boolean hasUnconsumedRecordedRows() {
            return false;
        }

        /**
         * Called at the transition request site, before the gameplay context
         * can be replaced and regardless of where the request falls relative
         * to the ordinary end-of-frame callback.
         */
        default void onSpecialStageRequestRaised() {
        }
    }

    private PlaybackDebugManager() {
    }

    public synchronized void setFrameObserver(PlaybackFrameObserver observer) {
        this.frameObserver = observer;
        clearPreparedFrame();
    }

    /** Publishes a non-consuming special-stage request edge to trace tooling. */
    public synchronized void onSpecialStageRequestRaised() {
        if (frameObserver != null) {
            frameObserver.onSpecialStageRequestRaised();
        }
    }

    private SonicConfigurationService configService() {
        return GameServices.configuration();
    }

    public static PlaybackDebugManager getInstance() {
        return INSTANCE;
    }

    /**
     * Whether a playback cursor currently owns, or is scheduled to own, a
     * production input boundary. This is deliberately mode-independent: a
     * pending level-load rebind still reserves future input ownership.
     */
    public synchronized boolean hasActiveOrScheduledSession() {
        return (enabled && movie != null && timeline != null)
                || pendingLevelLoadMovie != null;
    }

    public synchronized void handleInput(InputHandler input) {
        if (input == null) {
            return;
        }

        if (input.isKeyPressedWithoutModifiers(configService().getInt(SonicConfiguration.PLAYBACK_TOGGLE_KEY))) {
            enabled = !enabled;
            if (enabled) {
                if (movie == null) {
                    loadFromConfig();
                }
                if (movie != null) {
                    setStatus("Playback enabled", true);
                }
            } else {
                if (timeline != null) {
                    timeline.setPlaying(false);
                }
                setStatus("Playback disabled", true);
            }
        }

        if (input.isKeyPressedWithoutModifiers(configService().getInt(SonicConfiguration.PLAYBACK_LOAD_KEY))) {
            loadFromConfig();
        }

        if (!enabled || movie == null || timeline == null) {
            return;
        }

        if (input.isKeyPressedWithoutModifiers(configService().getInt(SonicConfiguration.PLAYBACK_PLAY_PAUSE_KEY))) {
            timeline.togglePlaying();
            periodicLogCounter = 0;
            setStatus(timeline.isPlaying() ? "Playback running" : "Playback paused", true);
        }

        if (input.isKeyPressedWithoutModifiers(configService().getInt(SonicConfiguration.PLAYBACK_STEP_BACK_KEY))) {
            timeline.stepBackward();
            setStatus("Stepped movie frame backward", true);
        }

        if (input.isKeyPressedWithoutModifiers(configService().getInt(SonicConfiguration.PLAYBACK_STEP_FORWARD_KEY))) {
            timeline.stepForward();
            setStatus("Stepped movie frame forward", true);
        }

        if (input.isKeyPressedWithoutModifiers(configService().getInt(SonicConfiguration.PLAYBACK_JUMP_BACK_KEY))) {
            timeline.jumpBackward(DEFAULT_JUMP_FRAMES);
            setStatus("Jumped movie backward by " + DEFAULT_JUMP_FRAMES + " frames", true);
        }

        if (input.isKeyPressedWithoutModifiers(configService().getInt(SonicConfiguration.PLAYBACK_JUMP_FORWARD_KEY))) {
            timeline.jumpForward(DEFAULT_JUMP_FRAMES);
            setStatus("Jumped movie forward by " + DEFAULT_JUMP_FRAMES + " frames", true);
        }

        if (input.isKeyPressedWithoutModifiers(configService().getInt(SonicConfiguration.PLAYBACK_FAST_RATE_KEY))) {
            timeline.cycleRate();
            setStatus("Playback rate set to " + timeline.getRate() + "x", true);
        }

        if (input.isKeyPressedWithoutModifiers(configService().getInt(SonicConfiguration.PLAYBACK_RESET_TO_START_KEY))) {
            resetToConfiguredOffset();
            setStatus("Reset movie cursor to start offset", true);
        }
    }

    public synchronized boolean isDriving(GameMode mode) {
        return enabled && movie != null && timeline != null
                && (mode == GameMode.LEVEL || mode == GameMode.BONUS_STAGE)
                && com.openggf.TraceSessionLauncher
                        .allowsRunLogicalGameplayInput();
    }

    public synchronized int getCurrentForcedInputMask() {
        getCurrentLogicalInputSnapshot();
        return lastAppliedMask;
    }

    /** Returns the selected BK2 row as ROM-visible held and edge input. */
    public synchronized LogicalInputSnapshot getCurrentLogicalInputSnapshot() {
        if (movie == null || timeline == null) {
            lastAppliedMask = 0;
            lastAppliedStart = false;
            pendingP1ActionPressMask = 0;
            currentForcedStartPress = false;
            return LogicalInputSnapshot.neutral();
        }
        if (!timeline.isPlaying()) {
            lastAppliedMask = 0;
            lastAppliedStart = false;
            pendingP1ActionPressMask = 0;
            currentForcedStartPress = false;
            return LogicalInputSnapshot.neutral();
        }
        prepareCurrentFrame();
        if (preparedInputApplied) {
            return RecordedInputSnapshots.fromBk2(
                    preparedAppliedFrame, preparedAppliedPredecessor,
                    pendingP1ActionPressMask);
        }
        lastAppliedMask = preparedAppliedFrame.p1InputMask();
        lastAppliedStart = preparedAppliedFrame.p1StartPressed();
        preparedInputApplied = true;

        return RecordedInputSnapshots.fromBk2(
                preparedAppliedFrame, preparedAppliedPredecessor,
                pendingP1ActionPressMask);
    }

    /**
     * Purely prepares the current validation row and its selected applied row.
     * Repeated calls for one cursor are idempotent and never advance playback.
     */
    public synchronized void prepareCurrentFrame() {
        if (!enabled || movie == null || timeline == null || !timeline.isPlaying()) {
            return;
        }
        int cursor = timeline.getCursorFrame();
        if (preparedValidationFrame != null && preparedCursor == cursor) {
            return;
        }
        clearPreparedFrame();
        Bk2FrameInput validation = movie.getFrame(cursor);
        if (frameObserver != null) {
            frameObserver.prepareFrame(validation);
        }
        int appliedOffset = frameObserver != null
                ? frameObserver.appliedInputOffset(validation)
                : 0;
        int appliedIndex = cursor + appliedOffset;
        if (appliedIndex < 0 || appliedIndex >= movie.getFrameCount()) {
            throw new IllegalStateException(
                    "applied BK2 row " + appliedIndex + " outside movie 0.."
                            + (movie.getFrameCount() - 1)
                            + " for validation row " + cursor);
        }
        Bk2FrameInput applied = movie.getFrame(appliedIndex);
        Bk2FrameInput predecessor = appliedIndex > 0
                ? movie.getFrame(appliedIndex - 1)
                : null;
        preparedCursor = cursor;
        preparedValidationFrame = validation;
        preparedAppliedFrame = applied;
        preparedAppliedPredecessor = predecessor;

        int previousAction = predecessor != null ? predecessor.p1ActionMask() : 0;
        int pressed = applied.p1ActionMask() & ~previousAction;
        pendingP1ActionPressMask |= pressed;
        boolean previousStart = predecessor != null && predecessor.p1StartPressed();
        currentForcedStartPress = applied.p1StartPressed() && !previousStart;
        previousActionMask = applied.p1ActionMask();
        previousStartPressed = applied.p1StartPressed();
    }

    /**
     * Reads the replay-validation input mask relative to the current cursor.
     * Returns {@code -1} when no active movie or when the requested row is out
     * of range, matching the fixture peek contract.
     */
    public synchronized int peekInputMaskAt(int offset) {
        if (!enabled || movie == null || timeline == null) {
            return -1;
        }
        int index = timeline.getCursorFrame() + offset;
        if (index < 0 || index >= movie.getFrameCount()) {
            return -1;
        }
        return validationInputMask(movie.getFrame(index));
    }

    public synchronized boolean isCurrentForcedJumpPress() {
        return pendingP1ActionPressMask != 0;
    }

    public synchronized boolean isCurrentForcedStartPress() {
        return currentForcedStartPress;
    }

    /**
     * Consumes a pending playback action edge after the level gameplay body
     * actually ran. Input-only rows may advance the movie cursor without
     * gameplay, so their edge remains latched across later held rows until this
     * callback.
     */
    public synchronized void onCurrentGameplayTickExecuted() {
        pendingP1ActionPressMask = 0;
    }

    /**
     * Whether the attached observer still owns unconsumed recorded rows for the
     * span it is comparing. False when no session/observer is attached, so
     * production play is unaffected.
     */
    public synchronized boolean observerHasUnconsumedRecordedRows() {
        if (!enabled || movie == null || timeline == null || frameObserver == null) {
            return false;
        }
        return frameObserver.hasUnconsumedRecordedRows();
    }

    public synchronized void onLevelFrameAdvanced() {
        if (!enabled || movie == null || timeline == null) {
            currentTickSuppressed = false;
            return;
        }
        Bk2FrameInput beforeFrame = movie.getFrame(timeline.getCursorFrame());
        boolean wasSuppressed = currentTickSuppressed;
        currentTickSuppressed = false;
        timeline.advanceIfPlaying();
        if (frameObserver != null) {
            frameObserver.afterFrameAdvanced(beforeFrame, wasSuppressed);
        }
        clearPreparedFrame();
        if (timeline.isPlaying()) {
            periodicLogCounter++;
            if (periodicLogCounter >= PERIODIC_LOG_INTERVAL_FRAMES) {
                periodicLogCounter = 0;
                // Periodic heartbeat at FINE — default log level won't
                // surface it; opt in via java.util.logging when
                // debugging playback cadence.
                logStatus("tick", Level.FINE);
            }
        } else {
            periodicLogCounter = 0;
        }
    }

    /**
     * Called by {@link com.openggf.GameLoop} immediately before the LEVEL
     * mode gameplay tick. Returns true when the attached observer wants
     * the tick suppressed (ROM lag frame). The BK2 cursor still advances
     * via {@link #onLevelFrameAdvanced()}.
     */
    public synchronized boolean shouldSkipCurrentGameplayTick() {
        if (!enabled || movie == null || timeline == null || frameObserver == null) {
            currentTickSuppressed = false;
            return false;
        }
        prepareCurrentFrame();
        if (preparedSkipEvaluated) {
            return currentTickSuppressed;
        }
        currentTickSuppressed =
                frameObserver.shouldSkipGameplayTick(preparedValidationFrame);
        preparedVblankAdvanceCount = currentTickSuppressed
                ? Math.max(0, frameObserver.vblankAdvanceCountOnSkippedTick(
                        preparedValidationFrame))
                : 0;
        preparedSkipEvaluated = true;
        return currentTickSuppressed;
    }

    /** Cached suppression result for the prepared current cursor. */
    public synchronized boolean isCurrentGameplayTickSuppressed() {
        return preparedSkipEvaluated && currentTickSuppressed;
    }

    public synchronized boolean shouldAdvanceVblankOnCurrentSkippedTick() {
        if (!currentTickSuppressed || movie == null || timeline == null || frameObserver == null) {
            return false;
        }
        return preparedSkipEvaluated && preparedVblankAdvanceCount > 0;
    }

    public synchronized int currentSkippedTickVblankAdvanceCount() {
        if (!currentTickSuppressed || movie == null || timeline == null || frameObserver == null) {
            return 0;
        }
        return preparedSkipEvaluated ? preparedVblankAdvanceCount : 0;
    }

    /**
     * Programmatic entrypoint used by {@code TraceSessionLauncher} to
     * drive playback without the hotkey / config-path path.
     */
    public synchronized void startSession(Bk2Movie movie, int startOffsetIndex) {
        this.movie = movie;
        this.timeline = new PlaybackTimelineController(movie.getFrameCount());
        this.firstActiveFrame = findFirstActiveFrame(movie);
        this.timeline.resetTo(Math.max(0, startOffsetIndex));
        this.periodicLogCounter = 0;
        this.enabled = true;
        this.timeline.setPlaying(true);
        clearLastAppliedState();
        clearPreparedFrame();
        setStatus("Session started (" + movie.getFrameCount() + " frames)", true);
    }

    /**
     * Defers a movie-cursor rebind until the next synchronous level load.
     * Level loads can complete in the middle of a {@code GameLoop} step and
     * immediately fall through to the new level's first gameplay tick. Arming
     * the rebind here lets that tick read the destination segment's frame 0
     * input without advancing the destination cursor during the preceding fade.
     */
    public synchronized void scheduleSessionAtNextLevelLoad(Bk2Movie movie, int startOffsetIndex) {
        scheduleSessionAtNextLevelLoad(movie, startOffsetIndex, () -> true);
    }

    /**
     * Defers a movie rebind until a level load that satisfies a structural
     * target guard. A rejected load leaves the descriptor pending, so an
     * unrelated reload cannot steal a later run segment's input offset.
     * The guard may inspect live identity but must not mutate gameplay.
     */
    public synchronized void scheduleSessionAtNextLevelLoad(
            Bk2Movie movie, int startOffsetIndex,
            BooleanSupplier activationGuard) {
        this.pendingLevelLoadMovie = movie;
        this.pendingLevelLoadOffset = Math.max(0, startOffsetIndex);
        this.pendingLevelLoadActivationGuard =
                java.util.Objects.requireNonNull(activationGuard, "activationGuard");
    }

    /**
     * True once the preceding playback cursor has reached the destination row
     * of a deferred level-load rebind. Additional engine-only transition ticks
     * must not age global ROM clocks beyond that recorded boundary.
     */
    public synchronized boolean shouldHoldVblankForPendingLevelLoad() {
        return pendingLevelLoadMovie != null
                && timeline != null
                && timeline.getCursorFrame() >= pendingLevelLoadOffset;
    }

    /** Returns whether whole-run playback is waiting to rebind at a level load. */
    public synchronized boolean hasScheduledLevelLoadSession() {
        return pendingLevelLoadMovie != null;
    }

    /**
     * Activates a rebind scheduled by {@link #scheduleSessionAtNextLevelLoad}.
     * Called by {@code GameLoop} immediately after a level load, before any
     * same-step gameplay fallthrough.
     *
     * @return true when a pending session was activated
     */
    public synchronized boolean activateScheduledLevelLoadSession() {
        if (pendingLevelLoadMovie == null) {
            return false;
        }
        if (pendingLevelLoadActivationGuard != null
                && !pendingLevelLoadActivationGuard.getAsBoolean()) {
            return false;
        }
        Bk2Movie scheduledMovie = pendingLevelLoadMovie;
        int scheduledOffset = pendingLevelLoadOffset;
        pendingLevelLoadMovie = null;
        pendingLevelLoadOffset = -1;
        pendingLevelLoadActivationGuard = null;
        startSession(scheduledMovie, scheduledOffset);
        return true;
    }

    /** Cancels a deferred level-load rebind without disturbing active playback. */
    public synchronized void cancelScheduledLevelLoadSession() {
        pendingLevelLoadMovie = null;
        pendingLevelLoadOffset = -1;
        pendingLevelLoadActivationGuard = null;
    }

    /** Programmatic teardown for {@link #startSession}. Idempotent. */
    public synchronized void endSession() {
        if (timeline != null) {
            timeline.setPlaying(false);
        }
        this.enabled = false;
        this.movie = null;
        this.timeline = null;
        this.firstActiveFrame = -1;
        this.frameObserver = null;
        this.currentTickSuppressed = false;
        this.pendingLevelLoadMovie = null;
        this.pendingLevelLoadOffset = -1;
        this.pendingLevelLoadActivationGuard = null;
        clearPreparedFrame();
        clearLastAppliedState();
        setStatus("Session ended", true);
    }

    /** Returns the frame at the current cursor without advancing it. */
    public synchronized Bk2FrameInput currentFrameOrThrow() {
        if (!enabled || movie == null || timeline == null) {
            throw new IllegalStateException("No active playback session");
        }
        return movie.getFrame(timeline.getCursorFrame());
    }

    /** Advance the BK2 cursor without running a gameplay tick. No-op if not enabled. */
    public synchronized void advanceCurrentFrameWithoutGameplay() {
        if (!enabled || movie == null || timeline == null) {
            return;
        }
        currentTickSuppressed = false;
        timeline.advanceIfPlaying();
        clearPreparedFrame();
    }

    /**
     * Programmatic seek used by Trace Test Mode rewind. Keeps the BK2 cursor
     * aligned with the restored engine snapshot without invoking comparator
     * callbacks or moving gameplay state.
     */
    public synchronized void seekSessionFrame(int frame, boolean playing) {
        if (!enabled || movie == null || timeline == null) {
            return;
        }
        timeline.seekAndPlay(frame, playing);
        int cursor = timeline.getCursorFrame();
        previousActionMask = cursor > 0 ? movie.getFrame(cursor - 1).p1ActionMask() : 0;
        previousStartPressed = cursor > 0 && movie.getFrame(cursor - 1).p1StartPressed();
        lastAppliedMask = 0;
        lastAppliedStart = false;
        pendingP1ActionPressMask = 0;
        currentForcedStartPress = false;
        currentTickSuppressed = false;
        clearPreparedFrame();
    }

    public synchronized void clearLastAppliedState() {
        lastAppliedMask = 0;
        lastAppliedStart = false;
        pendingP1ActionPressMask = 0;
        currentForcedStartPress = false;
        previousActionMask = 0;
        previousStartPressed = false;
    }

    private void clearPreparedFrame() {
        preparedCursor = -1;
        preparedValidationFrame = null;
        preparedAppliedFrame = null;
        preparedAppliedPredecessor = null;
        preparedInputApplied = false;
        preparedSkipEvaluated = false;
        preparedVblankAdvanceCount = 0;
        currentTickSuppressed = false;
        currentForcedStartPress = false;
    }

    private static int validationInputMask(Bk2FrameInput frame) {
        int mask = frame.p1InputMask();
        if (frame.p1ActionMask() != 0) {
            mask |= AbstractPlayableSprite.INPUT_JUMP;
        }
        return mask;
    }


    public synchronized List<String> buildOverlayLines(GameMode mode) {
        if (mode != null) {
            lastObservedMode = mode;
        }
        return buildOverlayLines();
    }

    public synchronized List<String> buildOverlayLines() {
        if (overlayOwnedExternally || (!enabled && movie == null)) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(8);
        String state;
        if (!enabled) {
            state = "OFF";
        } else if (timeline != null && timeline.isPlaying()) {
            state = "PLAY";
        } else {
            state = "PAUSE";
        }
        lines.add("== PLAYBACK ==");
        lines.add("State: " + state + "  Mode: " + lastObservedMode.name());

        if (movie == null || timeline == null) {
            lines.add("Movie: <none>");
            lines.add(statusMessage);
            return lines;
        }

        String fileName = movie.getSourcePath().getFileName() != null
                ? movie.getSourcePath().getFileName().toString()
                : movie.getSourcePath().toString();
        lines.add("Movie: " + fileName);
        lines.add("Frame: " + timeline.getCursorFrame() + "/" + (movie.getFrameCount() - 1)
                + "  Rate: " + timeline.getRate() + "x");
        if (firstActiveFrame >= 0) {
            lines.add("First Active: " + firstActiveFrame);
        }
        lines.add("Input: " + formatInput(lastAppliedMask, lastAppliedStart));
        lines.add(statusMessage);
        return lines;
    }

    public synchronized boolean hasLoadedMovie() {
        return movie != null;
    }

    public synchronized boolean isSessionPlaying() {
        return enabled && movie != null && timeline != null && timeline.isPlaying();
    }

    public synchronized int getCursorFrame() {
        return timeline == null ? 0 : timeline.getCursorFrame();
    }

    public synchronized int getMovieFrameCount() {
        return movie == null ? 0 : movie.getFrameCount();
    }

    public synchronized boolean isHudVisible() {
        return !overlayOwnedExternally && (enabled || movie != null);
    }

    /**
     * Hands the playback panel over to another owner, which then renders this
     * information itself. Visual Trace Test Mode takes it for the duration of a
     * session so its own HUD is the single transport display, rather than
     * painting a second, differently-placed panel over the same frame.
     */
    public synchronized void setOverlayOwnedExternally(boolean owned) {
        this.overlayOwnedExternally = owned;
    }

    /** File name of the loaded movie, or null when none is loaded. */
    public synchronized String movieName() {
        if (movie == null) {
            return null;
        }
        return movie.getSourcePath().getFileName() != null
                ? movie.getSourcePath().getFileName().toString()
                : movie.getSourcePath().toString();
    }

    /** Last mode observed by the playback session. */
    public synchronized GameMode observedMode() {
        return lastObservedMode;
    }

    public synchronized void setObservedMode(GameMode mode) {
        if (mode != null) {
            lastObservedMode = mode;
        }
    }

    private void loadFromConfig() {
        String configuredPath = configService().getString(SonicConfiguration.PLAYBACK_MOVIE_PATH);
        if (configuredPath == null || configuredPath.isBlank()) {
            setStatus("Playback movie path is blank", true);
            return;
        }
        Path moviePath = resolveAgainstWorkingDir(configuredPath);
        try {
            Bk2Movie loaded = movieLoader.load(moviePath);
            this.movie = loaded;
            this.timeline = new PlaybackTimelineController(loaded.getFrameCount());
            this.firstActiveFrame = findFirstActiveFrame(loaded);
            resetToConfiguredOffset();
            setStatus("Loaded movie (" + loaded.getFrameCount() + " frames)", true);
        } catch (IOException e) {
            setStatus("Failed to load BK2: " + e.getMessage(), true);
            LOGGER.log(Level.WARNING, "Failed to load BK2 movie: " + moviePath, e);
        }
    }

    private void resetToConfiguredOffset() {
        if (timeline == null) {
            return;
        }
        timeline.resetTo(getConfiguredStartOffset());
        periodicLogCounter = 0;
        clearLastAppliedState();
    }

    /**
     * Converts the user-configured BizHawk frame number to a 0-based internal index.
     * BizHawk frame numbers correspond to 1-based line numbers in the Input Log file.
     */
    private int getConfiguredStartOffset() {
        int bk2Frame = configService().getInt(SonicConfiguration.PLAYBACK_START_OFFSET_FRAME);
        if (movie != null) {
            return Math.max(0, movie.bk2FrameToIndex(bk2Frame));
        }
        return Math.max(0, bk2Frame);
    }

    private static Path resolveAgainstWorkingDir(String configuredPath) {
        Path path = Path.of(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        String userDir = System.getProperty("user.dir");
        if (userDir == null || userDir.isBlank()) {
            return path.normalize();
        }
        return Path.of(userDir).resolve(path).normalize();
    }

    private static String formatInput(int mask, boolean start) {
        StringBuilder sb = new StringBuilder(8);
        sb.append((mask & AbstractPlayableSprite.INPUT_UP) != 0 ? 'U' : '.');
        sb.append((mask & AbstractPlayableSprite.INPUT_DOWN) != 0 ? 'D' : '.');
        sb.append((mask & AbstractPlayableSprite.INPUT_LEFT) != 0 ? 'L' : '.');
        sb.append((mask & AbstractPlayableSprite.INPUT_RIGHT) != 0 ? 'R' : '.');
        sb.append((mask & AbstractPlayableSprite.INPUT_JUMP) != 0 ? 'J' : '.');
        sb.append(start ? 'S' : '.');
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    private static int findFirstActiveFrame(Bk2Movie movie) {
        for (Bk2FrameInput frame : movie.getFrames()) {
            if (frame.p1InputMask() != 0 || frame.p1StartPressed()) {
                return frame.frameIndex();
            }
        }
        return -1;
    }

    private void setStatus(String message, boolean logNow) {
        statusMessage = message;
        if (logNow) {
            logStatus("status");
        }
    }

    private void logStatus(String reason) {
        logStatus(reason, Level.INFO);
    }

    private void logStatus(String reason, Level level) {
        if (!LOGGER.isLoggable(level)) {
            return;
        }
        if (movie == null || timeline == null) {
            LOGGER.log(level, "[Playback][" + reason + "] " + statusMessage);
            return;
        }
        Bk2FrameInput frame = movie.getFrame(timeline.getCursorFrame());
        String summary = String.format(
                "[Playback][%s] state=%s mode=%s frame=%d/%d rate=%dx input=%s firstActive=%d msg=%s",
                reason,
                timeline.isPlaying() ? "PLAY" : "PAUSE",
                lastObservedMode.name(),
                timeline.getCursorFrame(),
                movie.getFrameCount() - 1,
                timeline.getRate(),
                formatInput(frame.p1InputMask(), frame.p1StartPressed()),
                firstActiveFrame,
                statusMessage);
        LOGGER.log(level, summary);
    }
}
