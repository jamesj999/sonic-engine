package com.openggf.sprites.playable;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.rules.GameRules;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the ROM-faithful Ctrl_1_locked latch on
 * {@link AbstractPlayableSprite#setLogicalInputState} is gated by
 * {@link GameRules#controlLockLatchesLogicalInput()}.
 *
 * <p>ROM ref (sonic3k.asm:21541-21545 {@code loc_10760}, S2 s2.asm:35933-35935
 * {@code Obj01_Control}):
 * <pre>
 *   tst.b   (Ctrl_1_locked).w     ; Control_Locked for S2
 *   bne.s   loc_10780             ; if locked, SKIP the copy
 *   move.w  (Ctrl_1).w,(Ctrl_1_logical).w
 * </pre>
 *
 * <p>The latch is active for S2 and S3K. S1 keeps the latch off because its
 * object-control call sites still publish the filtered zero state directly.
 */
class TestLogicalInputControlLockLatch {

    private Sonic2GameModule module;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        module = new Sonic2GameModule();
        GameModuleRegistry.setCurrent(module);
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void s3kFlagSetSkipsLogicalInputWriteSoPreviousValuePersists() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);

        // Frame N-1: no lock, RIGHT pressed -> logical input recorded as RIGHT.
        sprite.setControlLocked(false);
        sprite.setLogicalInputState(false, false, false, true, false);
        sprite.endOfTick();
        short historyBeforeLock = sprite.getInputHistory(0);
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, historyBeforeLock,
                "S3K frame N-1: unlocked RIGHT must record INPUT_RIGHT");

        // Frame N: lock engages, publishInputState passes filtered (zeroed) inputs.
        // Latch must skip the write so logicalInputState retains RIGHT.
        sprite.setControlLocked(true);
        sprite.setLogicalInputState(false, false, false, false, false);
        sprite.endOfTick();
        short historyDuringLock = sprite.getInputHistory(0);
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, historyDuringLock,
                "S3K frame N: while controlLocked + latch flag, logicalInputState must persist");
    }

    @Test
    void followerHistoryPreservesConsecutiveIndependentActionPresses() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);

        // B is pressed while DOWN is held.
        sprite.setLogicalInputState(false, true, false, false, true, true);
        sprite.endOfTick();
        // C is newly pressed on the next frame while B remains held. The
        // aggregate jump state never went low, but Ctrl_1_Press has another
        // action bit and Stat_table must retain that second press.
        sprite.setLogicalInputState(false, true, false, false, true, true);
        sprite.endOfTick();

        assertTrue(sprite.getJumpPressHistory(0));
        assertTrue(sprite.getJumpPressHistory(1));
    }

    @Test
    void s3kFlagSetClearedRestoresFreshWrites() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sprite.setGameRulesForTest(GameRules.SONIC_3K);

        // Seed RIGHT while unlocked.
        sprite.setLogicalInputState(false, false, false, true, false);
        sprite.endOfTick();
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, sprite.getInputHistory(0));

        // Lock + zero attempt: latched value persists.
        sprite.setControlLocked(true);
        sprite.setLogicalInputState(false, false, false, false, false);
        sprite.endOfTick();
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, sprite.getInputHistory(0));

        // Unlock: fresh writes resume normally.
        sprite.setControlLocked(false);
        sprite.setLogicalInputState(false, false, true, false, false);
        sprite.endOfTick();
        short afterUnlock = sprite.getInputHistory(0);
        assertEquals(AbstractPlayableSprite.INPUT_LEFT, afterUnlock,
                "S3K after unlock: logical input must update again");
        assertNotEquals(AbstractPlayableSprite.INPUT_RIGHT, afterUnlock);
    }

    @Test
    void s2FlagSetLatchesLogicalInput() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sprite.setGameRulesForTest(GameRules.SONIC_2);

        // Frame N-1: no lock, RIGHT pressed.
        sprite.setControlLocked(false);
        sprite.setLogicalInputState(false, false, false, true, false);
        sprite.endOfTick();
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, sprite.getInputHistory(0));

        // Frame N: lock engages, zero inputs pushed. S2 Obj01_Control skips
        // the Ctrl_1 -> Ctrl_1_Logical copy while Control_Locked, so the
        // follower history must keep the previous logical word.
        sprite.setControlLocked(true);
        sprite.setLogicalInputState(false, false, false, false, false);
        sprite.endOfTick();
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, sprite.getInputHistory(0),
                "S2 frame N: while controlLocked + latch flag, logicalInputState must persist");
    }

    @Test
    void s1FlagClearedDoesNotLatchLogicalInput() {
        TestablePlayableSprite sprite = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sprite.setGameRulesForTest(GameRules.SONIC_1);

        // Frame N-1: unlocked RIGHT.
        sprite.setControlLocked(false);
        sprite.setLogicalInputState(false, false, false, true, false);
        sprite.endOfTick();
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, sprite.getInputHistory(0));

        // Frame N: locked + zero inputs; S1 must NOT latch.
        sprite.setControlLocked(true);
        sprite.setLogicalInputState(false, false, false, false, false);
        sprite.endOfTick();
        assertEquals((short) 0, sprite.getInputHistory(0),
                "S1 frame N: latch flag is false, lock must zero logicalInputState");
    }
}
