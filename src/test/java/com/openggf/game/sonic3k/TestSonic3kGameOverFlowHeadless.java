package com.openggf.game.sonic3k;

import com.openggf.game.DamageCause;
import com.openggf.game.GameOverExit;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.objects.S3kGameOverCardObjectInstance;
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
 * Sonic 3&amp;K game over and time over from a live AIZ1: {@code loc_12432}
 * (docs/skdisasm/sonic3k.asm:24588-24616) loads {@code Obj_GameOver} into
 * {@code Reserved_object_3} and the first dynamic slot, they slide in, and
 * {@code loc_2D666} sends the game on (docs/skdisasm/sonic3k.asm:62089-62101).
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kGameOverFlowHeadless {
    private static final int ZONE_AIZ_REGISTRY_INDEX = 0;
    private static final int ACT_1 = 0;
    private static final int MAX_FRAMES_TO_CROSS = 1200;
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private AbstractPlayableSprite sprite;

    @BeforeAll
    static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ_REGISTRY_INDEX, ACT_1);
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
                "ArtNem_GameOver + Map_GameOver must be loaded for the card");
    }

    @Test
    void lastLifeLoadsGameOverPairAndLeavesForTheTitleScreen() {
        GameStateManager gameState = GameServices.gameState();
        while (gameState.getLives() > 1) gameState.loseLife();
        LevelManager level = GameServices.level();

        killAndCross();

        assertEquals(0, gameState.getLives());
        assertEquals(0, sprite.getDeathCountdown(), "restartime is rewritten to zero");
        S3kGameOverCardObjectInstance word = cardAt(Sonic3kConstants.SST_SLOT_GAME_OVER_WORD);
        S3kGameOverCardObjectInstance over = cardAt(Sonic3kConstants.SST_SLOT_GAME_OVER_OVER);
        assertEquals(AbstractGameOverCardObjectInstance.FRAME_GAME, word.getMappingFrame());
        assertEquals(AbstractGameOverCardObjectInstance.FRAME_OVER_GAME, over.getMappingFrame());

        stepUntilConjoined(word, over);
        assertEquals(S3kGameOverCardObjectInstance.WAIT_FRAMES, word.getWaitTimer());
        assertNull(level.getGameOverExitRequested());
        assertFalse(level.isRespawnRequestedForRewind(), "a game over never restarts the level");

        // A/B/C/Start on either controller (loc_2D638 :62069-62072)
        fixture.stepFrame(false, false, false, false, true);
        assertEquals(GameOverExit.TITLE_SCREEN, level.getGameOverExitRequested(),
                "no continues: Game_mode 0 (Sega screen)");
        assertFalse(level.isRespawnRequestedForRewind());
    }

    @Test
    void continueInHandRoutesToTheContinueScreen() {
        GameStateManager gameState = GameServices.gameState();
        while (gameState.getLives() > 1) gameState.loseLife();
        gameState.addContinue();
        LevelManager level = GameServices.level();

        killAndCross();
        S3kGameOverCardObjectInstance word = cardAt(Sonic3kConstants.SST_SLOT_GAME_OVER_WORD);
        stepUntilConjoined(word, cardAt(Sonic3kConstants.SST_SLOT_GAME_OVER_OVER));
        fixture.stepFrame(false, false, false, false, true);
        assertEquals(GameOverExit.CONTINUE_SCREEN, level.getGameOverExitRequested());
    }

    @Test
    void timeOverLoadsTimeOverPairAndRestartsTheLevel() {
        GameStateManager gameState = GameServices.gameState();
        LevelManager level = GameServices.level();
        int livesBefore = gameState.getLives();
        // The AIZ1 intro holds the player under object control, and a
        // controlled corpse never falls; a time over only happens in play.
        waitForPlayerControl();
        // TimeOver fires at 9:59; the HUD kills the player and raises Time_over_flag.
        level.getLevelGamestate().setTimerFrames(10L * 60 * 60);

        crossDeathRow();

        assertEquals(livesBefore - 1, gameState.getLives(), "a time over still costs a life");
        assertEquals(0, sprite.getDeathCountdown());
        S3kGameOverCardObjectInstance word = cardAt(Sonic3kConstants.SST_SLOT_GAME_OVER_WORD);
        S3kGameOverCardObjectInstance over = cardAt(Sonic3kConstants.SST_SLOT_GAME_OVER_OVER);
        assertEquals(AbstractGameOverCardObjectInstance.FRAME_TIME, word.getMappingFrame());
        assertEquals(AbstractGameOverCardObjectInstance.FRAME_OVER_TIME, over.getMappingFrame());

        stepUntilConjoined(word, over);
        fixture.stepFrame(false, false, false, false, true);
        assertTrue(level.isRespawnRequestedForRewind(), "Time_over_flag set: loc_2D680 restarts");
        assertNull(level.getGameOverExitRequested());
    }

    private void waitForPlayerControl() {
        for (int frame = 0; frame < 3000; frame++) {
            fixture.stepIdleFrames(1);
            if (!sprite.isObjectControlled()) {
                return;
            }
        }
        throw new AssertionError("AIZ1 intro never released the player");
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
        throw new AssertionError("corpse never crossed Camera_Y_pos + $100");
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

    private S3kGameOverCardObjectInstance cardAt(int slot) {
        for (ObjectInstance instance : GameServices.level().getObjectManager().getActiveObjects()) {
            if (instance instanceof S3kGameOverCardObjectInstance card && card.getSlotIndex() == slot) {
                return card;
            }
        }
        throw new AssertionError("no Obj_GameOver at Object_RAM slot " + slot);
    }
}
