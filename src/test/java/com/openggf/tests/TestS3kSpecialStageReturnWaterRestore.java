package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.ShieldType;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.BigRingReturnState;
import com.openggf.level.LevelManager;
import com.openggf.level.WaterSystem;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kSpecialStageReturnWaterRestore {
    private static final int ZONE_HCZ = Sonic3kZoneIds.ZONE_HCZ;
    private static final int ACT_1 = 0;
    private static final int LATE_HCZ1_CAMERA_X = 0x3600;
    private static final int HCZ1_LATE_WATER_LEVEL = 0x06A0;

    @Test
    public void specialStageReturn_restoresSavedWaterLevelImmediately() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_HCZ, ACT_1)
                .build();

        LevelManager levelManager = GameServices.level();
        WaterSystem waterSystem = GameServices.water();
        Camera camera = fixture.camera();
        AbstractPlayableSprite player = fixture.sprite();

        camera.setX((short) LATE_HCZ1_CAMERA_X);

        waterSystem.updateDynamic(ZONE_HCZ, ACT_1, camera.getX(), camera.getY());
        waterSystem.update();

        assertEquals(HCZ1_LATE_WATER_LEVEL, waterSystem.getWaterLevelY(ZONE_HCZ, ACT_1),
                "Sanity check: HCZ1 water should already be at the later threshold before saving");

        levelManager.saveBigRingReturn(new BigRingReturnState(
                player.getCentreX(),
                player.getCentreY(),
                camera.getX(),
                camera.getY(),
                player.getRingCount(),
                player.getTopSolidBit(),
                player.getLrbSolidBit(),
                camera.getMaxY(),
                0,
                HCZ1_LATE_WATER_LEVEL));

        levelManager.loadCurrentLevel();

        levelManager.getBigRingReturn().restoreToPlayer(
                player, camera, levelManager.getLevelGamestate(),
                waterSystem, ZONE_HCZ, ACT_1);

        assertEquals(HCZ1_LATE_WATER_LEVEL, waterSystem.getWaterLevelY(ZONE_HCZ, ACT_1),
                "Special-stage return should restore the saved water level immediately");
    }

    @Test
    public void specialStageReturn_restoresSavedElementalShield() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_HCZ, ACT_1)
                .build();

        LevelManager levelManager = GameServices.level();
        AbstractPlayableSprite player = fixture.sprite();
        player.giveShield(ShieldType.FIRE);

        levelManager.saveBigRingReturn(new BigRingReturnState(
                player.getCentreX(),
                player.getCentreY(),
                fixture.camera().getX(),
                fixture.camera().getY(),
                player.getRingCount(),
                player.getTopSolidBit(),
                player.getLrbSolidBit(),
                fixture.camera().getMaxY(),
                0));

        levelManager.loadCurrentLevel();
        levelManager.getBigRingReturn().restoreToPlayer(
                player, fixture.camera(), levelManager.getLevelGamestate());

        assertEquals(ShieldType.FIRE, player.getShieldType(),
                "An elemental shield saved before the special stage should return with Sonic");
    }

    @Test
    public void specialStageReturn_doesNotRestoreBasicShieldBecauseRomMaskExcludesIt() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_HCZ, ACT_1)
                .build();

        LevelManager levelManager = GameServices.level();
        AbstractPlayableSprite player = fixture.sprite();
        player.giveShield(ShieldType.BASIC);

        levelManager.saveBigRingReturn(new BigRingReturnState(
                player.getCentreX(),
                player.getCentreY(),
                fixture.camera().getX(),
                fixture.camera().getY(),
                player.getRingCount(),
                player.getTopSolidBit(),
                player.getLrbSolidBit(),
                fixture.camera().getMaxY(),
                0));

        levelManager.loadCurrentLevel();
        levelManager.getBigRingReturn().restoreToPlayer(
                player, fixture.camera(), levelManager.getLevelGamestate());

        assertFalse(player.hasShield(),
                "The ROM restores Fire/Lightning/Bubble shields, but not the ordinary shield bit");
    }
}
