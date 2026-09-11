package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.CnzBalloonInstance;
import com.openggf.game.sonic3k.objects.CnzHoverFanInstance;
import com.openggf.game.sonic3k.objects.CnzTrapDoorInstance;
import com.openggf.game.sonic3k.objects.CnzRisingPlatformInstance;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzLocalTraversalHeadless {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void balloonSubtypeZeroLaunchesWithoutSnappingPlayerX() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzBalloonInstance balloon = spawnBalloon(0x19C0, 0x05B0, 0x00);
        fixture.sprite().setCentreX((short) 0x19B8);
        fixture.sprite().setCentreY((short) 0x05A8);

        // ROM Obj_CNZBalloon (sonic3k.asm:66747) reads collision_property,
        // set externally by Touch_Process when the player intersects the
        // balloon's hitbox. The engine wires this through TouchResponseListener:
        // the level loop calls onTouchResponse before objectManager.update.
        triggerBalloonContact(balloon, fixture.sprite(), 0);
        balloon.update(0, fixture.sprite());

        assertTrue(fixture.sprite().getAir(), "CNZ balloon contact should launch the player into the air");
        assertEquals((short) -0x700, fixture.sprite().getYSpeed(),
                "Subtype 0x00 should use the standard launch speed");
        assertEquals(0x19B8, fixture.sprite().getCentreX(),
                "Subtype 0x00 must not snap the player to the balloon centre");
        assertTrue(invokeBooleanHook(balloon, "isPoppedForTest"),
                "Balloon should switch into its popped render state after launch");
        assertEquals(3, invokeIntHook(balloon, "getRenderFrameForTest"),
                "Subtype 0x00 should render the popped frame after launch");
    }

    @Test
    void balloonSubtype80DoesNotSnapPlayerWhenDryAndUsesLowerLaunchSpeed() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzBalloonInstance balloon = spawnBalloon(0x19C0, 0x05B0, 0x80);
        fixture.sprite().setCentreX((short) 0x19B8);
        fixture.sprite().setCentreY((short) 0x05A8);

        assertFalse(fixture.sprite().isInWater(),
                "The regression should be exercising the dry seam, not the underwater case");

        triggerBalloonContact(balloon, fixture.sprite(), 0);
        balloon.update(0, fixture.sprite());

        assertEquals(0x19B8, fixture.sprite().getCentreX(),
                "Dry negative subtype balloons should not snap the player horizontally");
        assertEquals(0x05A8, fixture.sprite().getCentreY(),
                "Dry negative subtype balloons should not snap the player vertically");
        assertEquals((short) -0x380, fixture.sprite().getYSpeed(),
                "Negative subtype balloons use the lower upward launch speed");
        assertTrue(invokeBooleanHook(balloon, "isPoppedForTest"));
    }

    @Test
    void poppedBalloonDoesNotLaunchAgainWithinSameFrameButKeepsPopAnimationRunning() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzBalloonInstance balloon = spawnBalloon(0x19C0, 0x05B0, 0x00);
        fixture.sprite().setCentreX((short) 0x19B8);
        fixture.sprite().setCentreY((short) 0x05A8);

        triggerBalloonContact(balloon, fixture.sprite(), 0);
        balloon.update(0, fixture.sprite());
        assertTrue(invokeBooleanHook(balloon, "isPoppedForTest"));

        fixture.sprite().setCentreX((short) 0x19B8);
        fixture.sprite().setCentreY((short) 0x05A8);
        fixture.sprite().setYSpeed((short) 0x120);
        // The engine guards launchPlayer with lastLaunchFrame so the same frame
        // cannot launch the player twice. ROM Obj_CNZBalloon (sonic3k.asm:66747)
        // moves the balloon offscreen ($7F00) once the pop animation hits $FB,
        // which removes it from the collision response list — so practical
        // re-touch on a later frame can't happen unless the player is still in
        // contact while the pop animation runs.
        triggerBalloonContact(balloon, fixture.sprite(), 0);
        balloon.update(0, fixture.sprite());

        assertEquals((short) 0x120, fixture.sprite().getYSpeed(),
                "Popped balloons should not relaunch within the same frame (lastLaunchFrame guard)");
        assertEquals(3, invokeIntHook(balloon, "getRenderFrameForTest"),
                "Popped balloons should keep the popped visual frame");
    }

    @Test
    void poppedBalloonLeavesThePlayfieldAfterItsPopAnimationCompletes() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzBalloonInstance balloon = spawnBalloon(0x19C0, 0x05B0, 0x00);
        fixture.sprite().setCentreX((short) 0x19B8);
        fixture.sprite().setCentreY((short) 0x05A8);

        triggerBalloonContact(balloon, fixture.sprite(), 0);
        balloon.update(0, fixture.sprite());
        assertTrue(invokeBooleanHook(balloon, "isPoppedForTest"));

        for (int frame = 1; frame <= 6; frame++) {
            balloon.update(frame, fixture.sprite());
        }

        assertTrue(balloon.isDestroyed(),
                "CNZ balloon pop animation should retire the object instead of leaving a frozen visible terminal frame");
    }

    @Test
    void risingPlatformCompressesWhileStandingThenSpringsBackToFloor() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzRisingPlatformInstance platform = spawnRisingPlatform(0x1D80, 0x06A0, 0x20);
        fixture.sprite().setCentreX((short) 0x1D80);
        fixture.sprite().setCentreY((short) 0x068C);
        fixture.sprite().setAir(false);
        fixture.sprite().setRolling(false);

        for (int frame = 0; frame < 60; frame++) {
            platform.onSolidContact(fixture.sprite(), new SolidContact(true, false, false, true, false), frame);
            platform.update(frame, fixture.sprite());
        }

        assertTrue(invokeBooleanHook(platform, "isArmedForTest"),
                "Standing on the platform should arm the rising state machine");
        int settledY = platform.getSpawn().y();
        assertTrue(invokeIntHook(platform, "getYSpeedForTest") > 0,
                "Floor contact should preserve the carried compression while still standing");
        assertEquals(2, invokeIntHook(platform, "getRenderFrameForTest"),
                "Floor contact while carried should switch the platform to the settled frame");

        for (int frame = 60; frame < 70; frame++) {
            platform.onSolidContact(fixture.sprite(), new SolidContact(true, false, false, true, false), frame);
            platform.update(frame, fixture.sprite());
            assertEquals(settledY, platform.getSpawn().y(),
                    "Continued standing should not let the platform tunnel through the floor");
            assertTrue(invokeIntHook(platform, "getYSpeedForTest") > 0,
                    "Settled platform should preserve its carried compression while standing");
            assertEquals(2, invokeIntHook(platform, "getRenderFrameForTest"),
                    "Settled platform should keep the resting frame while carried");
        }

        platform.update(60, fixture.sprite());
        assertTrue(invokeBooleanHook(platform, "isArmedForTest"),
                "ROM loc_31C6A swaps to loc_31BD2, so stepping off does not clear the armed latch");
        assertTrue(invokeIntHook(platform, "getYSpeedForTest") > 0,
                "ROM loc_31BD2 preserves the stored compression velocity instead of running loc_31C86");
        assertEquals(2, invokeIntHook(platform, "getRenderFrameForTest"),
                "Terminal routine should keep the settled frame");

        for (int frame = 61; frame < 120; frame++) {
            platform.update(frame, fixture.sprite());
            assertEquals(settledY, platform.getSpawn().y(),
                    "Terminal routine should keep the floor-snapped position");
        }

        assertTrue(invokeBooleanHook(platform, "isArmedForTest"),
                "Terminal routine should keep the armed latch");
        assertTrue(invokeIntHook(platform, "getYSpeedForTest") > 0,
                "Terminal routine should preserve the compressed velocity field");
        assertEquals(2, invokeIntHook(platform, "getRenderFrameForTest"),
                "Settled platforms should keep the resting frame");
        assertFalse(fixture.sprite().getAir(),
                "The test player should remain grounded while the platform is carrying them");
    }

    @Test
    void trapDoorStaysOpenWhileTheROMTriggerWindowRemainsOccupied() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzTrapDoorInstance trapDoor = spawnTrapDoor(0x2600, 0x0780, 0x00);
        fixture.sprite().setCentreX((short) 0x2600);
        fixture.sprite().setCentreY((short) 0x0788);

        trapDoor.update(0, fixture.sprite());

        assertTrue(invokeBooleanHook(trapDoor, "isOpenForTest"),
                "The trap door should open when the player enters the ROM trigger box");
        assertEquals(1, invokeIntHook(trapDoor, "getRenderFrameForTest"),
                "The first open frame should match the disassembly animation state");

        for (int frame = 1; frame < 24; frame++) {
            trapDoor.update(frame, fixture.sprite());
        }

        assertTrue(invokeBooleanHook(trapDoor, "isOpenForTest"),
                "The trap door should stay open while the ROM trigger box remains occupied");
        assertEquals(2, invokeIntHook(trapDoor, "getRenderFrameForTest"),
                "The open mapping frame should remain latched while the player is underneath");

        fixture.sprite().setCentreY((short) 0x0770);
        trapDoor.update(24, fixture.sprite());

        assertFalse(invokeBooleanHook(trapDoor, "isOpenForTest"),
                "The trap door should return to the closed state once the trigger box is clear");
        assertEquals(0, invokeIntHook(trapDoor, "getRenderFrameForTest"),
                "The closed mapping frame should be restored after the trigger clears");
    }

    @Test
    void trapDoorDoesNotOpenWhenPlayerCenterIsAboveTheHinge() {
        CnzTrapDoorInstance trapDoor = new CnzTrapDoorInstance(
                new ObjectSpawn(0x2600, 0x0780, Sonic3kObjectIds.CNZ_TRAP_DOOR, 0x00, 0, false, 0));
        trapDoor.setServices(new TestObjectServices());

        Sonic player = new Sonic("sonic", (short) 0x2600, (short) 0x0770);
        player.setCentreX((short) 0x2600);
        player.setCentreY((short) 0x0770);

        trapDoor.update(0, player);

        assertFalse(invokeBooleanHook(trapDoor, "isOpenForTest"),
                "An above-hinge player must not open the trap door");
        assertEquals(0, invokeIntHook(trapDoor, "getRenderFrameForTest"),
                "The closed frame should remain when the player stays above the hinge");
    }

    @Test
    void trapDoorChecksExtraEngineSidekickFromParticipationQuery() {
        CnzTrapDoorInstance trapDoor = new CnzTrapDoorInstance(
                new ObjectSpawn(0x2600, 0x0780, Sonic3kObjectIds.CNZ_TRAP_DOOR, 0x00, 0, false, 0));

        Sonic player = new Sonic("sonic", (short) 0x1200, (short) 0x0770);
        player.setCentreX((short) 0x1200);
        player.setCentreY((short) 0x0770);
        Tails nativeP2 = new Tails("tails", (short) 0x1200, (short) 0x0770);
        nativeP2.setCentreX((short) 0x1200);
        nativeP2.setCentreY((short) 0x0770);
        Tails extraSidekick = new Tails("tails-extra", (short) 0x2600, (short) 0x0788);
        extraSidekick.setCentreX((short) 0x2600);
        extraSidekick.setCentreY((short) 0x0788);
        trapDoor.setServices(new QueryOnlyPlayerServices(player, List.of(nativeP2, extraSidekick)));

        trapDoor.update(0, player);

        assertTrue(invokeBooleanHook(trapDoor, "isOpenForTest"),
                "The trap door should preserve engine-extended sidekick participation beyond native P2");
        assertEquals(1, invokeIntHook(trapDoor, "getRenderFrameForTest"),
                "The opening frame should still be the ROM opening frame");
    }

    @Test
    void hoverFanRaisesThePlayerWithoutStealingControlAndSeedsFlipMotion() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        // ROM sub_31E96 lift band (subtype 0x80 → $36=0x40, $38=0x70):
        //   d1 = osc + player.y + $36 - base.y; band valid when 0 <= d1 < $38.
        // Place player at fan centre (player.y == base.y) → d1=0x40, the
        // peak of the not/double mirror path. After loc_31EDE: -d1>>4 = -4.
        CnzHoverFanInstance fan = spawnHoverFan(0x2C00, 0x0900, 0x80);
        fixture.sprite().setCentreX((short) 0x2C00);
        fixture.sprite().setCentreY((short) 0x0900);
        fixture.sprite().setAir(false);
        fixture.sprite().setRolling(false);
        fixture.sprite().setObjectControlled(false);
        fixture.sprite().setControlLocked(false);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.sprite().setRollingJump(true);
        fixture.sprite().setJumping(true);
        fixture.sprite().setDoubleJumpFlag(2);
        fixture.sprite().setFlipAngle(0);
        fixture.sprite().setFlipSpeed(0);
        fixture.sprite().setFlipsRemaining(0);

        fan.update(0, fixture.sprite());

        assertFalse(fixture.sprite().isObjectControlled(),
                "CNZ hover fans push the player but do not take full object control");
        assertFalse(fixture.sprite().isControlLocked(),
                "CNZ hover fans do not lock the control bits");
        assertTrue(fixture.sprite().getAir(),
                "The fan should force the player airborne");
        assertEquals((short) 0, fixture.sprite().getYSpeed(),
                "The ROM routine zeroes vertical speed on capture");
        assertEquals((short) 1, fixture.sprite().getGSpeed(),
                "The ROM routine seeds ground_vel to 1 on the first capture frame");
        assertFalse(fixture.sprite().getRollingJump(),
                "The fan must clear the roll-jump state before seeding the flip");
        assertFalse(fixture.sprite().isJumping(),
                "The fan must clear the jumping bit before seeding the flip");
        assertEquals(0, fixture.sprite().getDoubleJumpFlag(),
                "The fan must clear the double-jump flag before seeding the flip");
        assertEquals(1, fixture.sprite().getFlipAngle(),
                "The first capture should seed the flip animation");
        assertEquals(8, fixture.sprite().getFlipSpeed(),
                "The first capture should seed the ROM flip speed");
        assertEquals(0x7F, fixture.sprite().getFlipsRemaining(),
                "The first capture should seed the ROM flip count");
        assertEquals(0x08FC, fixture.sprite().getCentreY(),
                "The lift should match the ROM-derived window-to-offset conversion (d1=$40 → -4)");
        assertEquals(0, invokeIntHook(fan, "getRenderFrameForTest"),
                "Subtype 0x80 should start on mapping frame 0");
    }

    @Test
    void hoverFanStopsOncePlayerLeavesTheRomLiftBandAndRetriggersOnReentry() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        // ROM band edges (subtype 0x80, osc=0): [0x08C0, 0x0930). Lift at
        //   d1=0x6F (player.y=0x092F) is +2 → ends at 0x0931, OUT of band.
        // Re-entry at fan centre (0x0900) gives d1=0x40 → lift -4 (upward).
        CnzHoverFanInstance fan = spawnHoverFan(0x2C00, 0x0900, 0x80);
        fixture.sprite().setCentreX((short) 0x2C00);
        fixture.sprite().setCentreY((short) 0x092F);
        fixture.sprite().setAir(false);
        fixture.sprite().setRolling(false);
        fixture.sprite().setObjectControlled(false);
        fixture.sprite().setControlLocked(false);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.sprite().setRollingJump(true);
        fixture.sprite().setJumping(true);
        fixture.sprite().setDoubleJumpFlag(2);
        fixture.sprite().setFlipAngle(0);
        fixture.sprite().setFlipSpeed(0);
        fixture.sprite().setFlipsRemaining(0);

        fan.update(0, fixture.sprite());
        int firstLiftY = fixture.sprite().getCentreY();

        fan.update(1, fixture.sprite());
        int secondFrameY = fixture.sprite().getCentreY();

        int reentryStartY = 0x0900;
        fixture.sprite().setCentreY((short) reentryStartY);
        fan.update(2, fixture.sprite());

        assertEquals(firstLiftY, secondFrameY,
                "CNZ hover fan should stop affecting the player immediately after they leave the ROM lift band");
        assertTrue(fixture.sprite().getCentreY() < reentryStartY,
                "CNZ hover fan should retrigger as soon as the player re-enters the valid force window");
    }

    @Test
    void hoverFanContinuesApplyingLiftAcrossGameplayFrames() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        ObjectManager objectManager = GameServices.level().getObjectManager();
        objectManager.addDynamicObject(new CnzHoverFanInstance(
                new ObjectSpawn(0x2C00, 0x0900, Sonic3kObjectIds.CNZ_HOVER_FAN, 0x80, 0, false, 0)));

        // Place the player at fan centre (player.y == base.y) so the ROM
        // sub_31E96 not/double mirror path returns the maximum upward lift
        // (-4 px). 0x08C0 is the very top edge of the force window where
        // d1=0 → asr.w #4 = 0 (no movement).
        int reentryY = 0x0900;
        fixture.sprite().setCentreX((short) 0x2C00);
        fixture.sprite().setCentreY((short) reentryY);
        fixture.sprite().setAir(false);
        fixture.sprite().setRolling(false);
        fixture.sprite().setObjectControlled(false);
        fixture.sprite().setControlLocked(false);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.sprite().setRollingJump(true);
        fixture.sprite().setJumping(true);
        fixture.sprite().setDoubleJumpFlag(2);
        fixture.sprite().setFlipAngle(0);
        fixture.sprite().setFlipSpeed(0);
        fixture.sprite().setFlipsRemaining(0);

        fixture.camera().updatePosition(true);

        int startY = fixture.sprite().getCentreY();
        fixture.stepFrame(false, false, false, false, false);
        int afterFirstFrame = fixture.sprite().getCentreY();
        fixture.sprite().setCentreY((short) reentryY);
        fixture.stepFrame(false, false, false, false, false);
        int afterSecondFrame = fixture.sprite().getCentreY();

        assertTrue(afterFirstFrame < startY,
                "CNZ hover fan should lift the player on the first gameplay frame inside the force window");
        assertTrue(afterSecondFrame < reentryY,
                "CNZ hover fan should retrigger on the next gameplay frame when the player re-enters the force window");
    }

    @Test
    void hoverFanSubtype90UsesItsOwnWindowAndInitialFrame() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzHoverFanInstance fan = spawnHoverFan(0x2C00, 0x0900, 0x90);
        fixture.sprite().setCentreX((short) 0x2C20);
        fixture.sprite().setCentreY((short) 0x08C0);
        fixture.sprite().setAir(false);
        fixture.sprite().setRolling(false);
        fixture.sprite().setObjectControlled(false);
        fixture.sprite().setControlLocked(false);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.sprite().setFlipAngle(0);
        fixture.sprite().setFlipSpeed(0);
        fixture.sprite().setFlipsRemaining(0);

        fan.update(0, fixture.sprite());

        assertTrue(fixture.sprite().getAir(),
                "Subtype 0x90 should still capture the player when the subtype-derived X band matches");
        assertEquals((short) 0, fixture.sprite().getYSpeed(),
                "The ROM routine still clears Y speed on subtype-specific captures");
        assertEquals((short) 1, fixture.sprite().getGSpeed(),
                "The ROM routine still seeds ground_vel on subtype-specific captures");
        assertEquals(1, fixture.sprite().getFlipAngle(),
                "The first capture should seed the flip animation");
        assertEquals(1, invokeIntHook(fan, "getRenderFrameForTest"),
                "Subtype 0x90 should start on mapping frame 1, not the subtype-0 frame");
    }

    @Test
    void hoverFanIgnoresHurtPlayersLikeTheRomRoutineGate() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();

        CnzHoverFanInstance fan = spawnHoverFan(0x2C00, 0x0900, 0x80);
        fixture.sprite().setCentreX((short) 0x2C00);
        fixture.sprite().setCentreY((short) 0x08C0);
        fixture.sprite().setAir(false);
        fixture.sprite().setRolling(false);
        fixture.sprite().setObjectControlled(false);
        fixture.sprite().setControlLocked(false);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.sprite().setRollingJump(true);
        fixture.sprite().setJumping(true);
        fixture.sprite().setDoubleJumpFlag(2);
        fixture.sprite().setFlipAngle(0);
        fixture.sprite().setFlipSpeed(0);
        fixture.sprite().setFlipsRemaining(0);
        fixture.sprite().setHurt(true);

        int startY = fixture.sprite().getCentreY();
        fan.update(0, fixture.sprite());

        assertEquals(startY, fixture.sprite().getCentreY(),
                "CNZ hover fan should ignore hurt players, matching the ROM routine >= 4 gate");
        assertFalse(fixture.sprite().getAir(),
                "CNZ hover fan should not force hurt players airborne");
        assertEquals((short) 0, fixture.sprite().getYSpeed(),
                "CNZ hover fan should not change hurt-player Y speed");
        assertEquals((short) 0, fixture.sprite().getGSpeed(),
                "CNZ hover fan should not seed ground velocity for hurt players");
        assertEquals(0, fixture.sprite().getFlipAngle(),
                "CNZ hover fan should not seed flip motion for hurt players");
    }

    /**
     * Simulates the ROM's Touch_Process bit-set for the given balloon and player.
     * Mirrors the engine's normal flow (level loop calls
     * {@link com.openggf.level.objects.ObjectManager#runTouchResponsesForPlayer}
     * before the per-object update). Tests use this helper because they call
     * {@code balloon.update()} directly and bypass the touch pipeline.
     */
    private static void triggerBalloonContact(CnzBalloonInstance balloon,
                                              PlayableEntity player, int frameCounter) {
        TouchResponseResult result = new TouchResponseResult(0, 0x10, 0x20, TouchCategory.SPECIAL);
        balloon.onTouchResponse(player, result, frameCounter);
    }

    private static CnzBalloonInstance spawnBalloon(int x, int y, int subtype) {
        CnzBalloonInstance object = new CnzBalloonInstance(
                new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_BALLOON, subtype, 0, false, 0));
        object.setServices(TestEnvironment.objectServices());
        return object;
    }

    private static CnzTrapDoorInstance spawnTrapDoor(int x, int y, int subtype) {
        CnzTrapDoorInstance object = new CnzTrapDoorInstance(
                new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_TRAP_DOOR, subtype, 0, false, 0));
        object.setServices(TestEnvironment.objectServices());
        return object;
    }

    private static CnzHoverFanInstance spawnHoverFan(int x, int y, int subtype) {
        CnzHoverFanInstance object = new CnzHoverFanInstance(
                new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_HOVER_FAN, subtype, 0, false, 0));
        object.setServices(TestEnvironment.objectServices());
        return object;
    }

    private static CnzRisingPlatformInstance spawnRisingPlatform(int x, int y, int subtype) {
        CnzRisingPlatformInstance object = new CnzRisingPlatformInstance(
                new ObjectSpawn(x, y, Sonic3kObjectIds.CNZ_RISING_PLATFORM, subtype, 0, false, 0));
        object.setServices(TestEnvironment.objectServices());
        return object;
    }

    private static boolean invokeBooleanHook(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return (boolean) method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read hook " + methodName, e);
        }
    }

    private static int invokeIntHook(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return (int) method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read hook " + methodName, e);
        }
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this.main = main;
            this.queriedSidekicks = List.copyOf(queriedSidekicks);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }
    }
}
