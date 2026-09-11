package com.openggf.game.sonic3k.specialstage;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for the real {@link S3kSpecialStageResultsScreen} tally mechanics.
 * Validates ring bonus, time bonus, continue threshold, and element visibility
 * by constructing the production object and reading its computed outputs.
 * No ROM or OpenGL required: the screen's art load swallows a missing ROM, and
 * the tally/visibility outputs are derived from constructor inputs.
 *
 * ROM reference: sonic3k.asm Obj_SpecialStage_Results (lines 63296-64164),
 * bonus calculation (63320-63327), continue threshold (54000/63400),
 * emerald check (54019-54025/63441-63444).
 */
@ExtendWith(SingletonResetExtension.class)
class TestS3kSpecialStageResultsTally {

    @Test
    void mappingTilesReuseOneMutableDescriptor() {
        S3kSpecialStageResultsScreen screen = new S3kSpecialStageResultsScreen(
                0, false, 0, 0, PlayerCharacter.SONIC_AND_TAILS);

        var first = screen.configureReusablePatternDesc(0xA123);
        var second = screen.configureReusablePatternDesc(0x4ABC);

        assertSame(first, second);
        assertEquals(0x4ABC & 0x7FF, second.getPatternIndex());
        assertEquals((0x4ABC >>> 13) & 3, second.getPaletteIndex());
    }

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        GameStateManager gs = GameServices.gameState();
        gs.resetSession();
        gs.configureSpecialStageProgress(7, 7);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    private static S3kSpecialStageResultsScreen screen(int rings, boolean gotEmerald,
                                                       int totalEmeraldCount) {
        return new S3kSpecialStageResultsScreen(
                rings, gotEmerald, 0, totalEmeraldCount, PlayerCharacter.SONIC_AND_TAILS);
    }

    // ---- Ring bonus: rings x 10 (ROM line 63321) ----

    @Test
    void ringBonus_0rings_returns0() {
        assertEquals(0, screen(0, false, 0).ringBonusForTest());
    }

    @Test
    void ringBonus_50rings_returns500() {
        assertEquals(500, screen(50, true, 1).ringBonusForTest());
    }

    @Test
    void ringBonus_100rings_returns1000() {
        assertEquals(1000, screen(100, true, 1).ringBonusForTest());
    }

    @Test
    void ringBonus_255rings_returns2550() {
        assertEquals(2550, screen(255, true, 1).ringBonusForTest());
    }

    // ---- Time bonus: 5000 if perfect (emerald earned), else 0 (ROM 63323-63326) ----

    @Test
    void timeBonus_perfect_returns5000() {
        assertEquals(5000, screen(50, true, 1).timeBonusForTest());
    }

    @Test
    void timeBonus_notPerfect_returns0() {
        assertEquals(0, screen(50, false, 0).timeBonusForTest());
    }

    // ---- Continue label threshold: >= 50 rings (ROM line 54000/63400, element 5) ----

    @Test
    void continueLabel_49rings_notShown() {
        assertFalse(screen(49, false, 0).continueLabelVisibleForTest());
    }

    @Test
    void continueLabel_50rings_shown() {
        assertTrue(screen(50, false, 0).continueLabelVisibleForTest());
    }

    @Test
    void continueLabel_100rings_shown() {
        assertTrue(screen(100, true, 1).continueLabelVisibleForTest());
    }

    // ---- Element visibility: failure vs success ----

    @Test
    void failMessage_visible_whenNotGotEmerald() {
        assertTrue(screen(30, false, 0).failMessageVisibleForTest());
    }

    @Test
    void failMessage_hidden_whenGotEmerald() {
        assertFalse(screen(30, true, 1).failMessageVisibleForTest());
    }

    @Test
    void charName_hidden_whenNotGotEmerald() {
        assertFalse(screen(30, false, 0).charNameVisibleForTest());
    }

    @Test
    void charName_visible_whenGotEmerald() {
        assertTrue(screen(30, true, 1).charNameVisibleForTest());
    }

    // ---- "SUPER SONIC" visibility: only if succeeded + all 7 (ROM 64016-64021/54255-54260) ----

    @Test
    void superText_hidden_whenNotAllEmeralds() {
        assertFalse(screen(50, true, 6).superTextVisibleForTest());
    }

    @Test
    void superText_visible_whenAllEmeralds() {
        assertTrue(screen(50, true, 7).superTextVisibleForTest());
    }

    @Test
    void superText_hidden_whenFailed() {
        assertFalse(screen(50, false, 7).superTextVisibleForTest());
    }

    // ---- Tally decrement: ring + time bonuses count down by 10/frame together ----
    // ROM routine 2 (updatePreTally): after the 360-frame wait, each frame
    // subtracts up to 10 from time bonus then up to 10 from ring bonus.

    @Test
    void tallyDecrement_perfectStage_drainsBothBonusesToZero() {
        S3kSpecialStageResultsScreen screen = screen(50, true, 1);
        assertEquals(500, screen.ringBonusForTest());
        assertEquals(5000, screen.timeBonusForTest());

        // 360-frame pre-tally wait + 500 frames to drain the larger (time) bonus.
        for (int frame = 0; frame < 360 + 600; frame++) {
            screen.update(frame, null);
        }

        assertEquals(0, screen.ringBonusForTest(),
                "Ring bonus must drain to 0 during the tally countdown");
        assertEquals(0, screen.timeBonusForTest(),
                "Time bonus must drain to 0 during the tally countdown");
    }

    @Test
    void tallyDecrement_ringBonusDecreasesByTenPerTallyFrame() {
        S3kSpecialStageResultsScreen screen = screen(50, false, 0);
        // Step exactly through the pre-tally wait, then one tally frame.
        for (int frame = 0; frame <= 360; frame++) {
            screen.update(frame, null);
        }
        assertEquals(490, screen.ringBonusForTest(),
                "First tally frame removes 10 from the 500 ring bonus");
    }
}
