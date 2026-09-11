package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.camera.Camera;
import com.openggf.level.LevelManager;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzBumperObjectInstance {

    @Test
    void touchResponseProfileUsesDynamicMultiRegionStopPolicy() {
        CnzBumperObjectInstance bumper =
                new CnzBumperObjectInstance(new ObjectSpawn(0x0340, 0x06BC, 0x4A, 0x40, 0, false, 0));

        TouchResponseProfile singleRegion = bumper.getTouchResponseProfile(false);
        TouchResponseProfile multiRegion = bumper.getTouchResponseProfile(true);

        assertEquals(TouchCategoryDecodeMode.NORMAL, singleRegion.categoryDecodeMode());
        assertTrue(singleRegion.continuousCallbacks());
        assertTrue(singleRegion.requiresRenderFlagForTouch());
        assertFalse(singleRegion.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS,
                singleRegion.stopAfterFirstOverlapPolicy());
        assertTrue(multiRegion.continuousCallbacks());
        assertTrue(multiRegion.requiresRenderFlagForTouch());
        assertTrue(multiRegion.multiRegionSource());
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY,
                multiRegion.stopAfterFirstOverlapPolicy());
    }

    @Test
    void nonzeroSubtypeMovesOnSixtyFourPixelCircleFromSpawnOrigin() {
        CnzBumperObjectInstance bumper =
                new CnzBumperObjectInstance(new ObjectSpawn(0x0340, 0x06BC, 0x4A, 0x40, 0, false, 0));
        bumper.setServices(new TestObjectServices());

        bumper.update(0, null);

        int expectedAngle = 0x41;
        assertEquals(0x0340 + (TrigLookupTable.cosHex(expectedAngle) >> 2), bumper.getX());
        assertEquals(0x06BC + (TrigLookupTable.sinHex(expectedAngle) >> 2), bumper.getY());
    }

    @Test
    void orbitUsesLevelFrameCounterRatherThanVblankCounter() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        LevelManager levelManager = fixture.runtime().getLevelManager();
        setLevelFrameCounter(levelManager, 0x011C);

        CnzBumperObjectInstance bumper =
                new CnzBumperObjectInstance(new ObjectSpawn(0x03E8, 0x0630, 0x4A, 0x80, 0, false, 0));
        bumper.setServices(new TestObjectServices().withLevelManager(levelManager));

        bumper.update(0x0D4C, null);

        // LevelManager's counter is now the ROM-visible Level_frame_counter
        // during the object pass, so no adjustment is applied to the seed.
        int expectedAngle = (0x80 + (0x011C & 0xFF)) & 0xFF;
        assertEquals(0x03E8 + (TrigLookupTable.cosHex(expectedAngle) >> 2), bumper.getX());
        assertEquals(0x0630 + (TrigLookupTable.sinHex(expectedAngle) >> 2), bumper.getY());
    }

    @Test
    void stationarySubtypeDoesNotOrbit() {
        CnzBumperObjectInstance bumper =
                new CnzBumperObjectInstance(new ObjectSpawn(0x0340, 0x06BC, 0x4A, 0x00, 1, false, 0));
        bumper.setServices(new TestObjectServices());

        bumper.update(1, null);

        assertEquals(0x0340, bumper.getX());
        assertEquals(0x06BC, bumper.getY());
    }

    @Test
    void outOfRangeReferenceUsesOriginalAnchorForOrbitingBumper() {
        CnzBumperObjectInstance bumper =
                new CnzBumperObjectInstance(new ObjectSpawn(0x03E8, 0x0630, 0x4A, 0x80, 0, false, 0));
        bumper.setServices(new TestObjectServices());

        bumper.update(0x011E, null);

        assertTrue(bumper.getX() != 0x03E8);
        assertEquals(0x03E8, bumper.getOutOfRangeReferenceX());
    }

    @Test
    void touchVisibilityUsesRomCollisionListWindowFromAnchor() {
        CnzBumperObjectInstance bumper =
                new CnzBumperObjectInstance(new ObjectSpawn(0x0F00, 0x0488, 0x4A, 0x00, 0, false, 0));
        Camera camera = new Camera();
        camera.setX((short) 0x0D55);
        bumper.setServices(new TestObjectServices().withCamera(camera));

        assertTrue(bumper.isOnScreenForTouch(),
                "Obj_Bumper accepts (origin_x & $FF80) - Camera_X_pos_coarse_back == $280");

        camera.setX((short) 0x0CC0);

        assertFalse(bumper.isOnScreenForTouch(),
                "Obj_Bumper skips when the unsigned coarse-back delta exceeds $280");
    }

    @Test
    void contactAppliesRomRadialBounceAwayFromCurrentBumperPosition() {
        CnzBumperObjectInstance bumper =
                new CnzBumperObjectInstance(new ObjectSpawn(0x0340, 0x06BC, 0x4A, 0, 0, false, 0));
        bumper.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.setCentreX((short) 0x0340);
        player.setCentreY((short) 0x06A8);
        player.setAir(false);
        player.setRollingJump(true);
        player.setJumping(true);
        player.setPushing(true);
        player.setGSpeed((short) 0x0123);

        bumper.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0);
        bumper.update(0, null);

        int angle = TrigLookupTable.calcAngle((short) 0, (short) 0x14) & 0xFF;
        assertEquals((short) ((TrigLookupTable.cosHex(angle) * -0x700) >> 8), player.getXSpeed());
        assertEquals((short) ((TrigLookupTable.sinHex(angle) * -0x700) >> 8), player.getYSpeed());
        assertTrue(player.getAir());
        assertEquals(0x0123, player.getGSpeed());
        assertTrue(!player.getRollingJump());
        assertTrue(!player.isJumping());
        assertTrue(!player.getPushing());
    }

    @Test
    void traceContactUsesRomVisibleLevelFrameForBounceAngle() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        LevelManager levelManager = fixture.runtime().getLevelManager();
        setLevelFrameCounter(levelManager, 0x0124);

        CnzBumperObjectInstance bumper =
                new CnzBumperObjectInstance(new ObjectSpawn(0x03E8, 0x0630, 0x4A, 0x2B, 0, false, 0));
        bumper.setServices(new TestObjectServices().withLevelManager(levelManager));
        AbstractPlayableSprite player = fixture.sprite();
        player.setCentreX((short) 0x03C9);
        player.setCentreY((short) 0x0655);
        player.setAir(false);
        player.setRollingJump(true);
        player.setJumping(true);
        player.setPushing(true);

        bumper.update(0x0D53, null);

        int expectedVisibleAngle = (0x2B + (0x0124 & 0xFF)) & 0xFF;
        assertEquals(0x03E8 + (TrigLookupTable.cosHex(expectedVisibleAngle) >> 2), bumper.getX());
        assertEquals(0x0630 + (TrigLookupTable.sinHex(expectedVisibleAngle) >> 2), bumper.getY());

        bumper.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0x0D53);
        bumper.update(0x0D53, null);

        int visibleX = bumper.getX();
        int visibleY = bumper.getY();
        int dx = visibleX - player.getCentreX();
        int dy = visibleY - player.getCentreY();
        int visibleLevelFrame = 0x0124;
        int angle = (TrigLookupTable.calcAngle((short) dx, (short) dy)
                + ((visibleLevelFrame >>> 8) & 0x03)) & 0xFF;
        assertEquals((short) ((TrigLookupTable.cosHex(angle) * -0x700) >> 8), player.getXSpeed());
        assertEquals((short) ((TrigLookupTable.sinHex(angle) * -0x700) >> 8), player.getYSpeed());
    }

    @Test
    void movingBumperExposesCurrentOrbitPointForTouchResponseList() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        LevelManager levelManager = fixture.runtime().getLevelManager();
        CnzBumperObjectInstance bumper =
                new CnzBumperObjectInstance(new ObjectSpawn(0x03E8, 0x0630, 0x4A, 0x2B, 0, false, 0));
        bumper.setServices(new TestObjectServices().withLevelManager(levelManager));

        setLevelFrameCounter(levelManager, 0x015E);
        bumper.update(0x0D8D, null);

        setLevelFrameCounter(levelManager, 0x015F);
        bumper.update(0x0D8E, null);

        assertEquals(bumper.getX(), bumper.getMultiTouchRegions()[0].x(),
                "Obj_Bumper joins the collision-response list after orbiting, so the "
                        + "published touch point is the current visible orbit point");
        assertEquals(bumper.getY(), bumper.getMultiTouchRegions()[0].y());
    }

    @Test
    void stationaryTraceContactUsesLevelFrameHighByteForBounceAngle() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        LevelManager levelManager = fixture.runtime().getLevelManager();
        setLevelFrameCounter(levelManager, 0x0159);

        CnzBumperObjectInstance bumper =
                new CnzBumperObjectInstance(new ObjectSpawn(0x03E8, 0x0630, 0x4A, 0x00, 0, false, 0));
        bumper.setServices(new TestObjectServices().withLevelManager(levelManager));
        AbstractPlayableSprite player = fixture.sprite();
        player.setCentreX((short) 0x03F1);
        player.setCentreY((short) 0x061B);

        bumper.onTouchResponse(player, new TouchResponseResult(0x17, 8, 8, TouchCategory.SPECIAL), 0x015A);
        bumper.update(0x015A, null);

        int dx = 0x03E8 - 0x03F1;
        int dy = 0x0630 - 0x061B;
        int angle = (TrigLookupTable.calcAngle((short) dx, (short) dy)
                + ((0x0159 >>> 8) & 0x03)) & 0xFF;
        assertEquals((short) ((TrigLookupTable.cosHex(angle) * -0x700) >> 8), player.getXSpeed());
        assertEquals((short) ((TrigLookupTable.sinHex(angle) * -0x700) >> 8), player.getYSpeed());
    }

    @Test
    void updateDoesNotUseBroadPlayerBoundsOutsideRomTouchRegion() {
        CnzBumperObjectInstance bumper =
                new CnzBumperObjectInstance(new ObjectSpawn(0, 0, 0x4A, 0, 0, false, 0));
        bumper.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.applyRollingRadii(false);
        player.setCentreX((short) 12);
        player.setCentreY((short) 25);
        player.setYSpeed((short) 0x70);
        player.setXSpeed((short) 0x5E2);

        bumper.update(0, player);

        assertEquals((short) 0x5E2, player.getXSpeed());
        assertEquals((short) 0x70, player.getYSpeed());
    }

    private static void setLevelFrameCounter(LevelManager levelManager, int value) throws Exception {
        Field field = LevelManager.class.getDeclaredField("frameCounter");
        field.setAccessible(true);
        field.setInt(levelManager, value);
    }
}
