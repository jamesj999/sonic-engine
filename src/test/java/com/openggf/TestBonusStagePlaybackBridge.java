package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.debug.playback.PlaybackInputBridge;
import com.openggf.game.GameMode;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the BONUS_STAGE playback bridge (spec engine-side addition #8):
 * trace playback must be able to feed input and advance the BK2 cursor while
 * the game is inside a bonus-stage interior, not just LEVEL mode.
 *
 * <p>Tier 1 ({@link IsDrivingWidening}) is ROM-free and exercises only
 * {@link PlaybackDebugManager#isDriving(GameMode)}. Tier 2
 * ({@link BonusStageModeCursorAdvance}) is ROM-backed and proves the real
 * {@code GameLoop.updateBonusStageMode()} bridge: cursor advance and forced
 * input application.
 */
class TestBonusStagePlaybackBridge {

    private static final Path MOVIE_PATH =
            Path.of("src", "test", "resources", "traces", "s3k", "_movies", "s3k-complete-sonic-tails.bk2");

    /**
     * Tier 1: ROM-free. Only exercises {@code PlaybackDebugManager.isDriving}
     * against a real {@link Bk2Movie} fabricated via {@link Bk2MovieLoader}
     * (the {@code TestGameLoopSpecialStageSkipGate} precedent for a
     * headless-safe movie fixture).
     */
    @Nested
    class IsDrivingWidening {

        private final PlaybackDebugManager manager = PlaybackDebugManager.getInstance();

        @BeforeEach
        void setUp() {
            manager.endSession();
        }

        @AfterEach
        void tearDown() {
            manager.endSession();
        }

        @Test
        void bonusStageNotDrivingWithoutActiveSession() {
            assertFalse(manager.isDriving(GameMode.BONUS_STAGE),
                    "No active session must never drive BONUS_STAGE mode");
            assertFalse(manager.isDriving(GameMode.LEVEL),
                    "No active session must never drive LEVEL mode");
        }

        @Test
        void bonusStageDrivesWhenSessionActive() throws Exception {
            Bk2Movie movie = new Bk2MovieLoader().load(MOVIE_PATH);
            manager.startSession(movie, 0);

            assertTrue(manager.isDriving(GameMode.BONUS_STAGE),
                    "An active session must drive BONUS_STAGE mode now that it is widened");
            assertTrue(manager.isDriving(GameMode.LEVEL),
                    "LEVEL driving behavior must be unchanged by the BONUS_STAGE widening");

            manager.endSession();

            assertFalse(manager.isDriving(GameMode.BONUS_STAGE),
                    "Ending the session must stop driving BONUS_STAGE mode");
        }

        @Test
        void scheduledLevelLoadRebindDoesNotMoveCursorUntilActivated() throws Exception {
            Bk2Movie movie = new Bk2MovieLoader().load(MOVIE_PATH);
            manager.startSession(movie, 3);

            manager.scheduleSessionAtNextLevelLoad(movie, 17);

            assertEquals(3, manager.getCursorFrame(),
                    "Scheduling must not disturb input during the preceding fade");
            assertTrue(manager.activateScheduledLevelLoadSession());
            assertEquals(17, manager.getCursorFrame(),
                    "The synchronous level-load hook must activate the destination cursor");
            assertFalse(manager.activateScheduledLevelLoadSession(),
                    "A scheduled level-load rebind must be one-shot");
        }

        @Test
        void directDestinationAdmissionPublishesItsCurrentInputImmediately()
                throws Exception {
            Bk2Movie movie = new Bk2MovieLoader().load(MOVIE_PATH);
            manager.startSession(movie, 17);
            InputHandler input = new InputHandler();

            new PlaybackInputBridge().publishImmediately(manager, input, null);

            assertEquals(manager.getCurrentLogicalInputSnapshot()
                    .player1().heldMask(), input.logical().player1().heldMask());
            assertEquals(manager.getCurrentLogicalInputSnapshot()
                    .player1().pressedMask(), input.logical().player1().pressedMask());
        }

        @Test
        void wrongLevelLoadCannotConsumeTargetAwareRebind() throws Exception {
            Bk2Movie movie = new Bk2MovieLoader().load(MOVIE_PATH);
            manager.startSession(movie, 3);
            AtomicBoolean targetMatches = new AtomicBoolean();
            manager.scheduleSessionAtNextLevelLoad(
                    movie, 17, targetMatches::get);

            assertFalse(manager.activateScheduledLevelLoadSession());
            assertEquals(3, manager.getCursorFrame());
            assertTrue(manager.hasScheduledLevelLoadSession(),
                    "a rejected reload must leave the destination pending");

            targetMatches.set(true);
            assertTrue(manager.activateScheduledLevelLoadSession());
            assertEquals(17, manager.getCursorFrame());
            assertFalse(manager.hasScheduledLevelLoadSession());
        }

        @Test
        void targetAwareRebindCanBeCancelledIdempotently() throws Exception {
            Bk2Movie movie = new Bk2MovieLoader().load(MOVIE_PATH);
            manager.startSession(movie, 3);
            manager.scheduleSessionAtNextLevelLoad(movie, 17, () -> true);

            manager.cancelScheduledLevelLoadSession();
            manager.cancelScheduledLevelLoadSession();

            assertFalse(manager.hasScheduledLevelLoadSession());
            assertFalse(manager.activateScheduledLevelLoadSession());
            assertEquals(3, manager.getCursorFrame());
        }

        @Test
        void scheduledLevelLoadHoldsClockOnlyAfterDestinationRow() throws Exception {
            Bk2Movie movie = new Bk2MovieLoader().load(MOVIE_PATH);
            manager.startSession(movie, 3);
            manager.scheduleSessionAtNextLevelLoad(movie, 5);

            assertFalse(manager.shouldHoldVblankForPendingLevelLoad());
            manager.advanceCurrentFrameWithoutGameplay();
            assertFalse(manager.shouldHoldVblankForPendingLevelLoad());
            manager.advanceCurrentFrameWithoutGameplay();
            assertTrue(manager.shouldHoldVblankForPendingLevelLoad());
            manager.activateScheduledLevelLoadSession();
            assertFalse(manager.shouldHoldVblankForPendingLevelLoad());
        }
    }

    /**
     * Tier 2: ROM-backed. Boots the S3K Gumball bonus-stage zone headlessly,
     * forces {@code GameLoop.currentGameMode} to {@code BONUS_STAGE} via
     * reflection, starts a real playback session, and invokes
     * {@code GameLoop.updateBonusStageMode()} (mirrored with
     * {@code syncPlaybackInputBridge()}, exactly as {@code stepInternal()}
     * orders the two calls) twice. This proves the real cursor/input bridge,
     * not just the {@code isDriving} predicate.
     */
    @Nested
    @RequiresRom(SonicGame.SONIC_3K)
    class BonusStageModeCursorAdvance {

        private final PlaybackDebugManager manager = PlaybackDebugManager.getInstance();

        @AfterEach
        void tearDown() {
            manager.endSession();
        }

        @Test
        void updateBonusStageModeAdvancesCursorAndAppliesForcedInput() throws Exception {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withZoneAndAct(Sonic3kZoneIds.ZONE_GUMBALL, 0)
                    .build();
            AbstractPlayableSprite sprite = fixture.sprite();

            GameLoop loop = new GameLoop(new InputHandler());
            setCurrentGameMode(loop, GameMode.BONUS_STAGE);

            Bk2Movie movie = new Bk2MovieLoader().load(MOVIE_PATH);
            manager.startSession(movie, 0);

            assertEquals(0, manager.getCursorFrame(), "Session must start at the requested cursor frame");

            invokeSyncPlaybackInputBridge(loop);
            invokeUpdateBonusStageMode(loop);

            assertEquals(1, manager.getCursorFrame(), "First tick must advance the BK2 cursor by one frame");
            int expectedMask = movie.getFrame(0).p1InputMask()
                    & (AbstractPlayableSprite.INPUT_UP | AbstractPlayableSprite.INPUT_DOWN
                    | AbstractPlayableSprite.INPUT_LEFT | AbstractPlayableSprite.INPUT_RIGHT
                    | AbstractPlayableSprite.INPUT_JUMP);
            assertEquals(expectedMask, sprite.getForcedInputMask(),
                    "The forced input mask applied to the player must match the BK2 frame that was current"
                            + " when syncPlaybackInputBridge ran (frame 0, before the first cursor advance)");

            invokeSyncPlaybackInputBridge(loop);
            invokeUpdateBonusStageMode(loop);

            assertEquals(2, manager.getCursorFrame(), "Second tick must advance the BK2 cursor by another frame");
        }

        private void invokeUpdateBonusStageMode(GameLoop loop) throws Exception {
            GameLoopTestStep.invoke(loop, "updateBonusStageMode",
                    new Class<?>[] { boolean.class }, false);
        }

        private void invokeSyncPlaybackInputBridge(GameLoop loop) throws Exception {
            Method method = GameLoop.class.getDeclaredMethod("syncPlaybackInputBridge");
            method.setAccessible(true);
            method.invoke(loop);
        }
    }

    private static void setCurrentGameMode(GameLoop loop, GameMode mode) throws Exception {
        Field field = GameLoop.class.getDeclaredField("currentGameMode");
        field.setAccessible(true);
        field.set(loop, mode);
    }
}
