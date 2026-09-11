package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.GameMode;
import com.openggf.game.session.SessionManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_1)
class TestS1VisualPlaybackControlLock {

    private final PlaybackDebugManager playback = PlaybackDebugManager.getInstance();

    @AfterEach
    void tearDown() {
        playback.endSession();
        SessionManager.clear();
    }

    @Test
    void signpostForcedRightRejectsRecordedLeftJumpForMovement() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        InputHandler input = new InputHandler();
        GameLoop loop = new GameLoop(input);
        loop.changeGameModeWithoutRewindBoundary(GameMode.LEVEL);
        playback.startSession(leftJumpMovie(), 0);

        AbstractPlayableSprite sonic = fixture.sprite();
        sonic.setForceInputRight(true);
        sonic.setControlLocked(true);

        invokeSyncPlaybackInputBridge(loop);
        invokeUpdateLevelMode(loop);
        invokeUpdateLevelMode(loop);

        assertTrue((input.logical().player1().heldMask()
                        & AbstractPlayableSprite.INPUT_LEFT) != 0,
                "the BK2 snapshot remains the raw Left controller word");
        assertEquals(0x04, input.logical().player1().actionPressedMask(),
                "the raw C-button edge remains available to controller readers");
        assertTrue(sonic.isRawControllerJumpJustPressed(),
                "objects retain the raw action edge during Sign_SonicRun");
        assertTrue((sonic.getLogicalInputState()
                        & AbstractPlayableSprite.INPUT_RIGHT) != 0,
                "the signpost remains owner of the ROM logical Right word");
        assertFalse((sonic.getLogicalInputState()
                        & AbstractPlayableSprite.INPUT_LEFT) != 0,
                "recorded Left must not overwrite signpost movement");
        assertFalse(sonic.getAir(),
                "recorded C must not jump while the signpost locks control");
        assertFalse(sonic.isForcedJumpPress(),
                "visual playback must leave no direct sprite latch behind");
    }

    private static Bk2Movie leftJumpMovie() {
        int held = AbstractPlayableSprite.INPUT_LEFT
                | AbstractPlayableSprite.INPUT_JUMP;
        return new Bk2Movie(
                Path.of("s1-signpost-left-jump.bk2"),
                "logkey",
                Map.of(),
                List.of(
                        new Bk2FrameInput(0, held, 0x04, false, "Left+C"),
                        new Bk2FrameInput(1, held, 0x04, false, "hold")),
                1);
    }

    private static void invokeSyncPlaybackInputBridge(GameLoop loop) throws Exception {
        Method method = GameLoop.class.getDeclaredMethod("syncPlaybackInputBridge");
        method.setAccessible(true);
        method.invoke(loop);
    }

    private static void invokeUpdateLevelMode(GameLoop loop) throws Exception {
        GameLoopTestStep.invoke(loop, "updateLevelMode",
                new Class<?>[] { boolean.class }, false);
    }
}
