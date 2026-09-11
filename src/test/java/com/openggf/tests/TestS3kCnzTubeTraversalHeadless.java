package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.CnzSpiralTubeInstance;
import com.openggf.game.sonic3k.objects.CnzVacuumTubeInstance;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzTubeTraversalHeadless {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void tubeSourceDescriptionsReflectTheVerifiedVacuumAndSpiralPreconditions() {
        assertEquals(Sonic3kObjectIds.CNZ_VACUUM_TUBE, invokeStaticInt("vacuumObjectId"));
        assertEquals(Sonic3kObjectIds.CNZ_SPIRAL_TUBE, invokeStaticInt("spiralObjectId"));
        assertEquals(4, invokeStaticInt("spiralPathCount"));
        assertEquals(0x0C, invokeStaticInt("spiralPayloadLengthBytes"));

        String vacuumSource = invokeStaticString("describeVacuumSource");
        String spiralSource = invokeStaticString("describeSpiralSource");

        assertTrue(vacuumSource.contains("inline"),
                "Vacuum Tube should document its inline controller flow rather than a fake external route table");
        assertTrue(vacuumSource.contains("S&K"),
                "Vacuum Tube should explicitly record that the verified controller logic comes from the S&K half");
        assertTrue(spiralSource.contains("off_33320"),
                "Spiral Tube should document the verified off_33320 table family");
        assertTrue(spiralSource.contains("0x000C"),
                "Spiral Tube should document the verified 0x000C payload length");
        assertTrue(spiralSource.contains("S&K"),
                "Spiral Tube should explicitly record that its route tables come from the S&K half");
    }

    @Test
    void spiralTubeRouteSelectionMatchesTheVerifiedSubtypeFamilies() {
        assertEquals("word_33328",
                invokeSpiralRouteLabel(0x00, 0x13C0, 0x13BF),
                "Subtype 0x00 with the player on the left should use word_33328");
        assertEquals("word_33336",
                invokeSpiralRouteLabel(0x00, 0x13C0, 0x13C1),
                "Subtype 0x00 with the player on the right should use word_33336");
        assertEquals("word_33344",
                invokeSpiralRouteLabel(0x02, 0x20C0, 0x20BF),
                "Subtype 0x02 with the player on the left should use word_33344");
        assertEquals("word_33352",
                invokeSpiralRouteLabel(0x02, 0x20C0, 0x20C1),
                "Subtype 0x02 with the player on the right should use word_33352");
    }

    @Test
    void vacuumTubeSubtypeZeroUsesFacingBitForHorizontalBoostWithoutObjectControl() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzVacuumTubeInstance tube = spawnVacuumTube(0x3EC0, 0x07F0, 0x00, 0x01);
        AbstractPlayableSprite player = fixture.sprite();
        player.setCentreX((short) 0x3EA0);
        player.setCentreY((short) 0x07D0);
        player.setAir(false);
        player.setJumping(false);
        player.setControlLocked(false);
        player.setObjectControlled(false);
        player.setXSpeed((short) 0);
        player.setGSpeed((short) 0);

        tube.update(0, player);

        assertFalse(player.isControlLocked(),
                "Subtype 0x00 should keep player input free because sub_31F62 never sets object_control");
        assertFalse(player.isObjectControlled(),
                "Subtype 0x00 should behave like the inline booster field, not a captured path object");
        assertEquals(0x1000, player.getXSpeed(),
                "Facing/status bit 0 should decide the horizontal boost direction for subtype 0x00");
        assertEquals(0x1000, player.getGSpeed(),
                "Subtype 0x00 should mirror the boost into ground_vel as well as x_vel");
    }

    @Test
    void vacuumTubeHorizontalDragPreservesPlayerSubpixelsLikeWordWrites() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzVacuumTubeInstance tube = spawnVacuumTube(0x3EC0, 0x07F0, 0x00, 0x01);
        AbstractPlayableSprite player = fixture.sprite();
        player.setCentreX((short) 0x3ED0);
        player.setCentreY((short) 0x07D0);
        player.setSubpixelRaw(0xDB00, 0x3200);
        player.setAir(false);
        player.setObjectControlled(false);

        tube.update(0, player);

        assertEquals(0xDB00, player.getXSubpixelRaw(),
                "sub_31F62 changes x_pos with word arithmetic and must not clear x_sub");
        assertEquals(0x3200, player.getYSubpixelRaw(),
                "Horizontal vacuum drag should leave y_sub untouched");
    }

    @Test
    void vacuumTubeProcessesEngineSidekicksThroughParticipationPolicy() {
        CnzVacuumTubeInstance tube = new CnzVacuumTubeInstance(
                new ObjectSpawn(0x3EC0, 0x07F0, Sonic3kObjectIds.CNZ_VACUUM_TUBE, 0x00, 0x01, false, 0));
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x3EA0, (short) 0x07D0);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x3EA0, (short) 0x07D0);
        TestablePlayableSprite extraSidekick = new TestablePlayableSprite("knuckles", (short) 0x3EA0, (short) 0x07D0);
        tube.setServices(new TestObjectServices().withSidekicks(List.of(sidekick, extraSidekick)));

        tube.update(0, main);

        assertEquals(0x1000, main.getXSpeed(),
                "The direct update player remains the main participant when services cannot resolve main");
        assertEquals(0x1000, sidekick.getXSpeed(),
                "Vacuum Tube intentionally extends ROM P2 participation to engine sidekicks");
        assertEquals(0x1000, extraSidekick.getXSpeed(),
                "Extra engine sidekicks should keep the existing multi-sidekick extension");
    }

    @Test
    void vacuumTubeLiftPreservesPlayerSubpixelsLikeWordWrites() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzVacuumTubeInstance tube = spawnVacuumTube(0x4740, 0x0828, 0x10, 0);
        AbstractPlayableSprite player = fixture.sprite();
        player.setCentreX((short) 0x4748);
        player.setCentreY((short) 0x0800);
        player.setSubpixelRaw(0xABCD, 0x4321);
        player.setAir(false);
        player.setObjectControlled(false);

        tube.update(0, player);

        assertEquals(0xABCD, player.getXSubpixelRaw(),
                "sub_32010 nudges x_pos toward the tube centre with word writes");
        assertEquals(0x4321, player.getYSubpixelRaw(),
                "sub_32010 raises y_pos with word writes and must preserve y_sub");
    }

    @Test
    void vacuumTubeLiftSubtypesUseSubtypeScaledTimersThenReleaseUpwardForStockPlacements() {
        verifyLiftSubtype(0x10);
        verifyLiftSubtype(0x20);
        verifyLiftSubtype(0x30);
    }

    private void verifyLiftSubtype(int subtype) {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzVacuumTubeInstance tube = spawnVacuumTube(0x4740, 0x0828, subtype, 0);
        AbstractPlayableSprite player = fixture.sprite();
        player.setCentreX((short) 0x4748);
        player.setCentreY((short) 0x0800);
        player.setAir(false);
        player.setJumping(true);
        player.setControlLocked(false);
        player.setObjectControlled(false);
        player.setXSpeed((short) 0x120);
        player.setYSpeed((short) 0x0100);
        player.setGSpeed((short) 0x0200);

        int initialY = player.getCentreY();
        int configuredLiftFrames = subtype * 2;

        tube.update(0, player);

        assertFalse(player.isControlLocked(),
                "Lift subtypes should keep player control free because sub_32010 never sets object_control");
        assertFalse(player.isObjectControlled(),
                "Lift subtypes should use the inline carry timer instead of object ownership");
        assertTrue(player.getAir(),
                "Lift capture should force the player airborne on the first capture frame");
        assertTrue(player.getCentreY() < initialY,
                "Lift capture should immediately raise the player toward the tube centre");
        assertEquals(0x0F, player.getAnimationId(),
                "Lift capture should force animation $0F from sub_32010");

        for (int frame = 1; frame < configuredLiftFrames; frame++) {
            short previousY = player.getCentreY();
            tube.update(frame, player);
            assertEquals(previousY - 8, player.getCentreY(),
                    "Active lift frames should rise by 8 pixels each frame for subtype 0x"
                            + Integer.toHexString(subtype));
            assertEquals(0, player.getXSpeed(),
                    "Active lift frames should zero x_vel while the player is being carried");
            assertEquals(0, player.getGSpeed(),
                    "Active lift frames should zero ground_vel while the player is being carried");
            assertEquals(0, player.getYSpeed(),
                    "Active lift frames should keep y_vel cleared until the release frame");
        }

        short releaseY = player.getCentreY();
        tube.update(configuredLiftFrames, player);

        assertEquals(releaseY, player.getCentreY(),
                "The release frame should stop the forced 8px lift and hand off to the launch velocity");
        assertEquals(-0x800, player.getYSpeed(),
                "When the subtype-scaled timer expires, the tube should launch upward with y_vel=-$800");
        assertFalse(player.isObjectControlled(),
                "Release should leave object_control clear because the vacuum tube never claimed it");
    }

    @Test
    void spiralTubeUsesVerifiedRouteReleaseAndDoesNotRewritePlayerRadii() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzSpiralTubeInstance tube = spawnSpiralTube(0x13C0, 0x02D0, 0x00);
        AbstractPlayableSprite player = fixture.sprite();
        player.setRingCount(1); // Odd rings drive the left-hand route family after the sway/descent phases.
        player.setCentreX((short) 0x13D0);
        player.setCentreY((short) 0x02D0);
        player.setAir(false);
        player.setJumping(true);
        player.setPushing(true);
        player.setRolling(false);
        player.setControlLocked(false);
        player.setObjectControlled(false);
        player.setXSpeed((short) 0x111);
        player.setYSpeed((short) 0x222);
        player.setGSpeed((short) 0x333);
        player.setSubpixelRaw(0x5400, 0xA700);

        int initialXRadius = player.getXRadius();
        int initialYRadius = player.getYRadius();

        tube.update(0, player);

        assertTrue(player.isObjectControlled(),
                "Spiral Tube capture should set object_control=$81 via the engine's object-control flag");
        assertFalse(player.isObjectControlAllowsCpu(),
                "object_control=$81 should not leave CPU movement enabled");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "object_control=$81 should suppress normal movement while the tube owns traversal");
        assertTrue(player.isTouchResponseSuppressedByObjectControl(),
                "object_control=$81 should suppress normal touch responses while the tube owns traversal");
        assertTrue(tube.isPersistent(),
                "An active Spiral Tube must survive object-window unloading while it controls the player");
        assertTrue(player.isControlLocked(),
                "Spiral Tube capture should lock control for the captured player");
        assertFalse(player.getRolling(),
                "Spiral Tube should not force Status_Roll when the ROM only writes anim=2");
        assertEquals(initialXRadius, player.getXRadius(),
                "Spiral Tube must not rewrite x_radius");
        assertEquals(initialYRadius, player.getYRadius(),
                "Spiral Tube must not rewrite y_radius");
        assertEquals(2, player.getAnimationId(),
                "Spiral Tube capture should force animation 2");
        assertEquals(0x0800, player.getGSpeed(),
                "Spiral Tube capture should seed ground_vel with $0800");
        assertEquals(0, player.getXSpeed(),
                "Spiral Tube capture should clear x_vel");
        assertEquals(0, player.getYSpeed(),
                "Spiral Tube capture should clear y_vel");
        assertFalse(player.getPushing(),
                "Spiral Tube capture should clear the push flag");
        assertTrue(player.getAir(),
                "Spiral Tube capture should force the player airborne");
        assertTrue(player.isJumping(),
                "Spiral Tube capture should leave jumping alone until the final release");
        assertEquals(0x13F0, player.getCentreX(),
                "A player captured from the right should be repositioned to objectX+$30");
        assertEquals(0x02D0, player.getCentreY(),
                "Capture should align the player to the tube centre Y");
        assertEquals(0x5400, player.getXSubpixelRaw(),
                "ROM word writes to x_pos must preserve the low subpixel word");
        assertEquals(0xA700, player.getYSubpixelRaw(),
                "ROM word writes to y_pos must preserve the low subpixel word");

        int expectedTravelFrames = invokeSpiralHookInt(tube, "getExpectedTravelFramesForTest");
        int expectedExitX = invokeSpiralExitCoordinate(tube, "centerX");
        int expectedExitY = invokeSpiralExitCoordinate(tube, "centerY");

        assertEquals(0x1230, expectedExitX,
                "Odd-ring subtype 0x00 traversal should select word_33328's left exit");
        assertEquals(0x030C, expectedExitY,
                "Release should keep the final y_vel for the same-frame last move");

        for (int frame = 1; frame <= expectedTravelFrames; frame++) {
            tube.update(frame, player);
        }

        assertFalse(player.isControlLocked(),
                "Spiral Tube should release control lock at the final route point");
        assertFalse(player.isObjectControlled(),
                "Spiral Tube should release object control at the final route point");
        assertFalse(player.isObjectControlAllowsCpu(),
                "Spiral Tube release should clear the CPU movement allowance bit with object control");
        assertFalse(player.isObjectControlSuppressesMovement(),
                "Spiral Tube release should clear movement suppression with object control");
        assertFalse(player.isTouchResponseSuppressedByObjectControl(),
                "Spiral Tube release should clear touch-response suppression with object control");
        assertFalse(tube.isPersistent(),
                "After release the controller can return to the normal out-of-range unload path");
        assertFalse(player.isJumping(),
                "Spiral Tube should clear jumping on release");
        assertEquals(expectedExitX, player.getCentreX(),
                "Spiral Tube should land at the verified same-frame final position");
        assertEquals(expectedExitY, player.getCentreY(),
                "Spiral Tube should keep the final move after releasing on the last point");
        assertEquals(0, player.getXSpeed(),
                "word_33328 exits vertically, so the preserved x_vel should stay zero");
        assertEquals(0x0C00, player.getYSpeed(),
                "Release should preserve the last segment's dominant-axis y_vel");
    }

    @Test
    void spiralTubeProcessesOnlyNativeP2ThroughPlayerQuery() {
        CnzSpiralTubeInstance tube = new CnzSpiralTubeInstance(
                new ObjectSpawn(0x13C0, 0x02D0, Sonic3kObjectIds.CNZ_SPIRAL_TUBE, 0x00, 0, false, 0));
        TestablePlayableSprite main = new TestablePlayableSprite("sonic", (short) 0x13D0, (short) 0x02D0);
        TestablePlayableSprite nativeP2 = new TestablePlayableSprite("tails", (short) 0x13D0, (short) 0x02D0);
        TestablePlayableSprite extraSidekick =
                new TestablePlayableSprite("knuckles", (short) 0x13D0, (short) 0x02D0);
        tube.setServices(new TestObjectServices().withSidekicks(List.of(nativeP2, extraSidekick)));

        tube.update(0, main);

        assertTrue(main.isObjectControlled(),
                "The direct update player should remain the P1 fallback when services cannot resolve main");
        assertTrue(nativeP2.isObjectControlled(),
                "CNZ Spiral Tube has one ROM Player_2 state block and should process the native sidekick");
        assertFalse(extraSidekick.isObjectControlled(),
                "Route carrier state must not be extended to extra engine sidekicks without dedicated state blocks");
    }

    private static CnzVacuumTubeInstance spawnVacuumTube(int x, int y, int subtype, int renderFlags) {
        CnzVacuumTubeInstance object = new CnzVacuumTubeInstance(
                new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_VACUUM_TUBE, subtype, renderFlags, false, 0));
        object.setServices(TestEnvironment.objectServices());
        return object;
    }

    private static CnzSpiralTubeInstance spawnSpiralTube(int x, int y, int subtype) {
        CnzSpiralTubeInstance object = new CnzSpiralTubeInstance(
                new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_SPIRAL_TUBE, subtype, 0, false, 0));
        object.setServices(TestEnvironment.objectServices());
        return object;
    }

    private static int invokeStaticInt(String methodName) {
        try {
            return (int) tubePathTablesMethod(methodName).invoke(null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Failed to invoke CnzTubePathTables." + methodName, e);
        }
    }

    private static String invokeStaticString(String methodName) {
        try {
            return (String) tubePathTablesMethod(methodName).invoke(null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError("Failed to invoke CnzTubePathTables." + methodName, e);
        }
    }

    private static String invokeSpiralRouteLabel(int subtype, int objectX, int playerX) {
        try {
            Object path = tubePathTablesMethod("spiralPathForEntry", int.class, int.class, int.class)
                    .invoke(null, subtype, objectX, playerX);
            Method labelMethod = path.getClass().getDeclaredMethod("label");
            labelMethod.setAccessible(true);
            return (String) labelMethod.invoke(path);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to resolve the verified Spiral Tube route label", e);
        }
    }

    private static int invokeSpiralHookInt(CnzSpiralTubeInstance tube, String methodName) {
        try {
            Method method = CnzSpiralTubeInstance.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            return (int) method.invoke(tube);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke CNZ spiral tube hook " + methodName, e);
        }
    }

    private static int invokeSpiralExitCoordinate(CnzSpiralTubeInstance tube, String accessor) {
        try {
            Method hook = CnzSpiralTubeInstance.class.getDeclaredMethod("getExpectedExitPointForTest");
            hook.setAccessible(true);
            Object point = hook.invoke(tube);
            Method accessorMethod = point.getClass().getDeclaredMethod(accessor);
            accessorMethod.setAccessible(true);
            return (int) accessorMethod.invoke(point);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to resolve CNZ spiral tube exit coordinate " + accessor, e);
        }
    }

    private static Method tubePathTablesMethod(String methodName, Class<?>... parameterTypes) {
        try {
            Class<?> type = Class.forName("com.openggf.game.sonic3k.objects.CnzTubePathTables");
            Method method = type.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("CnzTubePathTables should expose " + methodName, e);
        }
    }
}
