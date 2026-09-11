package com.openggf.game.sonic2.specialstage;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.sonic2.debug.Sonic2SpecialStageSpriteDebug;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Sonic2SpecialStageSwapFlagTest {
    @TempDir
    Path tempDir;

    @Test
    void playerConstructionCannotOmitTheSharedSwapOwner() {
        assertThrows(NoSuchMethodException.class, () ->
                Sonic2SpecialStagePlayer.class.getDeclaredConstructor(
                        Sonic2SpecialStagePlayer.PlayerType.class, boolean.class));
    }

    @Test
    void requiredOwnerHandlesBothPlayerGetterAndSetter() {
        Sonic2SpecialStageManager owner = new Sonic2SpecialStageManager();
        Sonic2SpecialStagePlayer player = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true, owner);

        assertFalse(player.getSwapPositionsFlag());
        player.setSwapPositionsFlag(true);
        assertTrue(player.getSwapPositionsFlag());
        assertEquals(0xFF, owner.getSwapPositionsFlag());
    }

    @Test
    void eitherPlayerToggleIsObservedByBothPlayersSwapLogic() {
        Sonic2SpecialStageManager manager = teamManager();
        Sonic2SpecialStagePlayer sonic = manager.getSonicPlayer();
        Sonic2SpecialStagePlayer tails = manager.getTailsPlayer();

        sonic.toggleSwapPositionsFlag();

        assertTrue(sonic.getSwapPositionsFlag());
        assertTrue(tails.getSwapPositionsFlag(),
                "SS_Swap_Positions_Flag is one global byte read by both players");

        sonic.update(0, 0);
        tails.update(0, 0);
        assertEquals(0x6F, sonic.getSSZPos(), "flag set moves Sonic toward the rear depth");
        assertEquals(0x7F, tails.getSSZPos(), "the same flag moves Tails toward the front depth");

        tails.toggleSwapPositionsFlag();
        assertFalse(sonic.getSwapPositionsFlag());
        assertFalse(tails.getSwapPositionsFlag());

        tails.toggleSwapPositionsFlag();
        assertTrue(sonic.getSwapPositionsFlag(), "either player toggles the same shared byte");
        assertTrue(tails.getSwapPositionsFlag());
    }

    @Test
    void rewindRestoresSharedFlagWithoutChangingPlayerTopology() {
        Sonic2SpecialStageManager manager = teamManager();
        Sonic2SpecialStagePlayer sonic = manager.getSonicPlayer();
        Sonic2SpecialStagePlayer tails = manager.getTailsPlayer();
        sonic.toggleSwapPositionsFlag();
        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();

        tails.toggleSwapPositionsFlag();
        manager.restoreRewindSnapshot(snapshot);

        assertEquals(0xFF, manager.getSwapPositionsFlag());
        assertTrue(sonic.getSwapPositionsFlag());
        assertTrue(tails.getSwapPositionsFlag());
        assertEquals(2, manager.getPlayers().size());
        assertSame(tails, sonic.getOtherPlayerForRewind());
        assertSame(sonic, tails.getOtherPlayerForRewind());
    }

    private Sonic2SpecialStageManager teamManager() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager(
                new Sonic2SpecialStageSpriteDebug(), config, null);
        manager.setupPlayersForTest();
        return manager;
    }
}
