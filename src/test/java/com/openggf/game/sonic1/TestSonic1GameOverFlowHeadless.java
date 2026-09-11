package com.openggf.game.sonic1;

import com.openggf.game.DamageCause;
import com.openggf.game.GameOverExit;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.objects.Sonic1GameOverCardObjectInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractGameOverCardObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sonic 1 game over and time over from a live GHZ1: the death routine's zero-
 * life branch ({@code Sonic_HandleDeath}, docs/s1disasm/_incObj/01
 * Sonic.asm:2019-2049) loads the two {@code GameOverCard} objects at
 * {@code v_gameovertext1/2}, they slide in, and {@code Over_Wait} sends the
 * game on (docs/s1disasm/_incObj/39 Game Over.asm).
 */
@RequiresRom(SonicGame.SONIC_1)
class TestSonic1GameOverFlowHeadless {
    private static final int ZONE_GHZ_REGISTRY_INDEX = 0;
    private static final int ACT_1 = 0;
    private static final int MAX_FRAMES_TO_CROSS = 1200;
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private AbstractPlayableSprite sprite;

    @BeforeAll
    static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_GHZ_REGISTRY_INDEX, ACT_1);
    }

    @AfterAll
    static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        sprite = fixture.sprite();
    }

    @Test
    void gameOverArtIsResidentFromTheRom() {
        assertNotNull(GameServices.level().getObjectManager().getObjectServices()
                .renderManager().getRenderer(ObjectArtKeys.GAME_OVER),
                "Nem_GameOver + Map_Over must be loaded for the card");
    }

    @Test
    void lastLifeLoadsGameOverPairAndLeavesForTheTitleScreen() {
        GameStateManager gameState = GameServices.gameState();
        while (gameState.getLives() > 1) gameState.loseLife();
        LevelManager level = GameServices.level();

        killAndCross();

        assertEquals(0, gameState.getLives());
        assertEquals(0, sprite.getDeathCountdown(), "restartime is rewritten to zero");
        Sonic1GameOverCardObjectInstance word = cardAt(Sonic1Constants.SST_SLOT_GAME_OVER_WORD);
        Sonic1GameOverCardObjectInstance over = cardAt(Sonic1Constants.SST_SLOT_GAME_OVER_OVER);
        assertEquals(AbstractGameOverCardObjectInstance.FRAME_GAME, word.getMappingFrame());
        assertEquals(AbstractGameOverCardObjectInstance.FRAME_OVER_GAME, over.getMappingFrame());

        stepUntilConjoined(word, over);
        assertEquals(Sonic1GameOverCardObjectInstance.WAIT_FRAMES, word.getWaitTimer());
        assertNull(level.getGameOverExitRequested());
        assertFalse(level.isRespawnRequestedForRewind(), "a game over never restarts the level");

        // btnABC on controller 1 (Over_Wait :58-60)
        fixture.stepFrame(false, false, false, false, true);
        assertEquals(GameOverExit.TITLE_SCREEN, level.getGameOverExitRequested(),
                "no continues: id_Sega");
        assertFalse(level.isRespawnRequestedForRewind());
    }

    @Test
    void continueInHandRoutesToTheContinueScreen() {
        GameStateManager gameState = GameServices.gameState();
        while (gameState.getLives() > 1) gameState.loseLife();
        gameState.addContinue();
        LevelManager level = GameServices.level();

        killAndCross();
        Sonic1GameOverCardObjectInstance word = cardAt(Sonic1Constants.SST_SLOT_GAME_OVER_WORD);
        stepUntilConjoined(word, cardAt(Sonic1Constants.SST_SLOT_GAME_OVER_OVER));
        fixture.stepFrame(false, false, false, false, true);
        assertEquals(GameOverExit.CONTINUE_SCREEN, level.getGameOverExitRequested());
    }

    @Test
    void timeOverLoadsTimeOverPairAndRestartsTheLevel() {
        GameStateManager gameState = GameServices.gameState();
        LevelManager level = GameServices.level();
        int livesBefore = gameState.getLives();
        // TimeOver fires at 9:59; the HUD kills the player and raises f_timeover.
        level.getLevelGamestate().setTimerFrames(10L * 60 * 60);

        crossDeathRow();

        assertEquals(livesBefore - 1, gameState.getLives(), "a time over still costs a life");
        assertEquals(0, sprite.getDeathCountdown());
        Sonic1GameOverCardObjectInstance word = cardAt(Sonic1Constants.SST_SLOT_GAME_OVER_WORD);
        Sonic1GameOverCardObjectInstance over = cardAt(Sonic1Constants.SST_SLOT_GAME_OVER_OVER);
        assertEquals(AbstractGameOverCardObjectInstance.FRAME_TIME, word.getMappingFrame());
        assertEquals(AbstractGameOverCardObjectInstance.FRAME_OVER_TIME, over.getMappingFrame());

        stepUntilConjoined(word, over);
        fixture.stepFrame(false, false, false, false, true);
        assertTrue(level.isRespawnRequestedForRewind(), "f_timeover set: always restart");
        assertNull(level.getGameOverExitRequested());
    }

    private void killAndCross() {
        assertTrue(sprite.applyHurtOrDeath(sprite.getCentreX(), DamageCause.SPIKE, false));
        assertTrue(sprite.getDead());
        crossDeathRow();
    }

    private void crossDeathRow() {
        for (int frame = 0; frame < MAX_FRAMES_TO_CROSS; frame++) {
            if (sprite.isInDeathRestartRoutine()) {
                return;
            }
            fixture.stepIdleFrames(1);
        }
        throw new AssertionError("corpse never crossed v_limitbtm2 + $100");
    }

    private void stepUntilConjoined(AbstractGameOverCardObjectInstance word,
                                    AbstractGameOverCardObjectInstance over) {
        for (int frame = 0; frame < 600; frame++) {
            if (word.getRoutine() == 4 && over.getRoutine() == 4) {
                assertEquals(0x120, word.getVdpX());
                assertEquals(0x120, over.getVdpX());
                return;
            }
            fixture.stepIdleFrames(1);
        }
        throw new AssertionError("card never conjoined: word routine " + word.getRoutine()
                + " x=" + word.getVdpX() + ", over routine " + over.getRoutine());
    }

    private Sonic1GameOverCardObjectInstance cardAt(int slot) {
        for (ObjectInstance instance : GameServices.level().getObjectManager().getActiveObjects()) {
            if (instance instanceof Sonic1GameOverCardObjectInstance card && card.getSlotIndex() == slot) {
                return card;
            }
        }
        throw new AssertionError("no GameOverCard at v_objspace slot " + slot);
    }
}
