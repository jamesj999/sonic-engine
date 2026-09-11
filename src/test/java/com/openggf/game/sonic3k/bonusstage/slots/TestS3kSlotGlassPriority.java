package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.RequiresRomCondition;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
@ExtendWith(RequiresRomCondition.class)
class TestS3kSlotGlassPriority {

    @Test
    void runtimePromotesCentralSlotMachineGlassAndKeepsSlotPlayerLowPriority() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        HeadlessTestFixture.builder()
                .withZoneAndAct(0x15, 0)
                .startPosition(S3kSlotRomData.SLOT_BONUS_PLAYER_START_X,
                        S3kSlotRomData.SLOT_BONUS_PLAYER_START_Y)
                .startPositionIsCentre()
                .build();

        LevelManager level = GameServices.level();
        assertFalse(hasAnyHighPriorityGlassTile(level, false),
                "The loaded slot machine glass descriptors are low-priority in source level data");

        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();

        assertTrue(runtime.isInitialized());
        int liveHighPriorityCellsAfterBootstrap = countHighPriorityGlassTiles(level, true);
        assertTrue(liveHighPriorityCellsAfterBootstrap > 20,
                "Bootstrap should promote and retain high-priority glass cells in the live foreground tilemap");
        int promotedCells = runtime.ensureForegroundGlassPriority();
        assertFalse(promotedCells > 0,
                "Foreground glass promotion should be idempotent after bootstrap"
                        + " (secondPassPromoted=" + promotedCells + ")");
        int liveHighPriorityCells = countHighPriorityGlassTiles(level, true);
        assertTrue(liveHighPriorityCells > 20,
                "The promoted live foreground tilemap should retain high-priority glass cells"
                        + " (promoted=" + promotedCells + ", liveHigh=" + liveHighPriorityCells + ")");

        assertTrue(GameServices.sprites().getSprite("sonic") instanceof S3kSlotBonusPlayer);
        AbstractPlayableSprite slotPlayer = (AbstractPlayableSprite) GameServices.sprites().getSprite("sonic");
        assertFalse(slotPlayer.isHighPriority(),
                "ROM Obj_Sonic_RotatingSlotBonus keeps the slot player low-priority for foreground/layout layering");

        runtime.update(0);
        assertTrue(hasVisibleNonMachineCell(runtime.activeVisibleCellsForTest()),
                "The visible cage/walls should also be available through the slot layout pass");
    }

    private static boolean hasVisibleNonMachineCell(S3kSlotRenderBuffers.VisibleCells cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.cellIdAt(i) != 0x09) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyHighPriorityGlassTile(LevelManager level, boolean liveTilemap) {
        return countHighPriorityGlassTiles(level, liveTilemap) > 0;
    }

    private static int countHighPriorityGlassTiles(LevelManager level, boolean liveTilemap) {
        int highPriorityTiles = 0;
        for (int y = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y - 0x30;
             y <= S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y + 0x30;
             y += 8) {
            for (int x = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X - 0x30;
                 x <= S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X + 0x30;
                 x += 8) {
                int descriptor = liveTilemap
                        ? level.getForegroundTileDescriptorFromTilemapAtWorld(x, y)
                        : level.getForegroundTileDescriptorAtWorld(x, y);
                if ((descriptor & 0x7FF) == 0) {
                    continue;
                }
                if ((descriptor & 0x8000) != 0) {
                    highPriorityTiles++;
                }
            }
        }
        return highPriorityTiles;
    }
}
