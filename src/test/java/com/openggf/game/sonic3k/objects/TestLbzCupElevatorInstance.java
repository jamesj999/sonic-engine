package com.openggf.game.sonic3k.objects;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.S3kSpriteDataLoader;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.RomTestUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestLbzCupElevatorInstance {

    @Test
    void registryRoutesS3klSlot18ToLbzCupElevator() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);

        ObjectInstance elevator = registry.create(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0, 0, false, 0));

        assertFalse(elevator instanceof PlaceholderObjectInstance,
                "S3KL slot $18 is Obj_LBZCupElevator and must not remain a placeholder");
        assertEquals("LBZCupElevator", elevator.getName());
        assertInstanceOf(SolidObjectProvider.class, elevator,
                "Obj_LBZCupElevator calls SolidObjectFull2_1P while near upright");
        assertEquals(SolidExecutionMode.MANUAL_CHECKPOINT,
                ((LbzCupElevatorInstance) elevator).solidExecutionMode(),
                "Obj18 must resolve SolidObjectFull before its same-slot player-control tail");
        assertTrue(((LbzCupElevatorInstance) elevator).bypassesOffscreenSolidGate(),
                "SolidObjectFull2_1P must run its P2 pass without the regular render-flag gate");
    }

    @Test
    void activeCupUsesLiveCpuSidekickPositionAndInclusiveRightEdge() throws Exception {
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0, 0, false, 0));
        Tails sidekick = new Tails("tails", (short) 0x1800, (short) 0x0600);
        sidekick.setCpuControlled(true);

        assertEquals(0, elevator.getFullSolidPlayerPositionHistoryFrames(sidekick));
        setPrivateInt(elevator, "activationFlag", 1);

        assertEquals(0, elevator.getFullSolidPlayerPositionHistoryFrames(sidekick),
                "Obj18's Full2 contact consumes Player 2's live position");
        assertTrue(elevator.usesInclusiveRightEdge(),
                "SolidObject_cont accepts the exact right-hand boundary via `bhi`");
        assertTrue(elevator.usesInstanceSolidStateLatchKey(),
                "the moving cup's native status bits belong to its live SST instance");
        assertFalse(elevator.airborneStaleStandingBitReturnsNoContact(sidekick),
                "a provisional engine P2 bit must not hide the active cup's live Full2 contact");
    }

    @Test
    void registryRoutesS3klSlot19ToCupElevatorPole() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);

        ObjectInstance pole = registry.create(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR_POLE, 0x3F, 0, false, 0));

        assertFalse(pole instanceof PlaceholderObjectInstance,
                "S3KL slot $19 is Obj_LBZCupElevatorPole and must render the long pole variant");
        assertEquals("LBZCupElevatorPole", pole.getName());
        assertEquals(0x60, assertInstanceOf(AbstractObjectInstance.class, pole).getOnScreenHalfHeight(),
                "Pole subtype bits 0-5 select mapping_frame 4 and height_pixels=$60");
    }

    @Test
    void subtypeLowNibbleSelectsRomTravelDistanceInMultiplesOf60() {
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0x03, 0, false, 0));

        assertEquals(0x120, elevator.getTravelDistanceForTest(),
                "Obj_LBZCupElevator computes (subtype & $F) * $60 into objoff_38");
        assertEquals(0x17C0, elevator.getX(),
                "Right-facing init shifts x_pos left by $40 after saving the anchor x in objoff_30");
        assertEquals(0x0600, elevator.getY());
        assertEquals(0x8080, elevator.getAngleForTest(),
                "Right-facing init sets angle word to $8080");
    }

    @Test
    void balanceUsesWidthPixelsWithoutSolidObjectSidePadding() {
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0, 0, false, 0));

        assertEquals(0x20, elevator.getBalanceWidthPixels(),
                "Player_Move reads Obj18 width_pixels, not SolidObjectFull's d1=$20+$0B reach");
        assertEquals(0x2B, elevator.getSolidParams().halfWidth(),
                "the independent full-solid contact extension must remain intact");
    }

    @Test
    void movingDownSubtypeStartsAtBottomAndUsesRoutine6() {
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0x31, 1, false, 0));

        assertEquals(0x60, elevator.getTravelDistanceForTest());
        assertEquals(0x60, elevator.getTravelProgressForTest());
        assertEquals(0x0660, elevator.getAnchorYForTest(),
                "Subtypes with bits 5-4 set start at the lower anchor: objoff_32 += travel distance");
        assertEquals(6, elevator.getRoutineForTest(),
                "ROM stores routine selector $36=6 for a downward-starting elevator");
        assertEquals(0x80, elevator.getAngleForTest() & 0xFF,
                "Odd subtype bit 0 adds $80 to angle+1 for the starting phase");
    }

    @Test
    void lbzPlanIncludesCupElevatorLevelArt() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(Sonic3kZoneIds.ZONE_LBZ, 0);

        Sonic3kPlcArtRegistry.LevelArtEntry elevator = plan.levelArt().stream()
                .filter(e -> e.key().equals(Sonic3kObjectArtKeys.LBZ_CUP_ELEVATOR))
                .findFirst().orElse(null);

        assertNotNull(elevator, "Obj_LBZCupElevator uses resident LBZ misc art");
        assertEquals(Sonic3kConstants.MAP_LBZ_CUP_ELEVATOR_ADDR, elevator.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_LBZ_MISC + 0x4A, elevator.artTileBase());
        assertEquals(2, elevator.palette());
    }

    @Test
    void lbzCupElevatorMappingsMatchRomShape() throws Exception {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            RomByteReader reader = RomByteReader.fromRom(rom);
            var frames = S3kSpriteDataLoader.loadMappingFrames(reader,
                    Sonic3kConstants.MAP_LBZ_CUP_ELEVATOR_ADDR);

            assertEquals(5, frames.size(), "Map_LBZCupElevator has cup, attach, base, short pole, long pole");
            assertEquals(2, frames.get(0).pieces().size());
            assertEquals(1, frames.get(1).pieces().size());
            assertEquals(2, frames.get(2).pieces().size());
            assertEquals(3, frames.get(3).pieces().size());
            assertEquals(6, frames.get(4).pieces().size());
        }
    }

    @Test
    void flingSpinReachesFlingRoutineWithSmoothMaxSpeedSteps() throws Exception {
        // Variant $83: bit7 set => fling at end, low nibble 3 => travel $120, flip set => flies right.
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0x83, 1, false, 0));

        // Drop the cup straight into LBZCupElev_Spin1 with $2E=$600, matching the MoveUp->Spin1 handoff.
        setPrivateInt(elevator, "routine", 0x0C);
        setPrivateInt(elevator, "spinSpeed", 0x600);
        setPrivateInt(elevator, "angleWord", 0x7F00);

        Method updateAction = LbzCupElevatorInstance.class.getDeclaredMethod("updateAction");
        updateAction.setAccessible(true);

        int maxStep = 0;
        int previousAngle = elevator.getAngleForTest() & 0xFF;
        int flingRoutine = -1;
        for (int frame = 0; frame < 600; frame++) {
            updateAction.invoke(elevator);
            int routine = elevator.getRoutineForTest();
            if (routine != 0x0C) {
                flingRoutine = routine;
                break;
            }
            int angle = elevator.getAngleForTest() & 0xFF;
            int step = (angle - previousAngle) & 0xFF;
            maxStep = Math.max(maxStep, step);
            previousAngle = angle;
        }

        assertEquals(0x10, flingRoutine,
                "LBZCupElev_Spin1 flip branch does addq #4 -> $36=$10 (LBZCupElev_Fling2, flings right); "
                        + "the cup must leave the spin instead of freezing");
        assertTrue(maxStep <= 0x10,
                "Per-frame angle advance is move.b angle+$2E high byte (<=$10); reading the low byte"
                        + " produces wild steps up to $F0 and freezes at $1000 (angle was stepping by " + maxStep + ")");
    }

    @Test
    void flungCupArcsAwayUnderGravityInsteadOfSlidingFlat() throws Exception {
        // Variant $83 + flip lands in LBZCupElev_Fling2; place the anchor at the fling target so
        // updateAction hands straight off to Obj_LBZElevatorCupFlicker.
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0x83, 1, false, 0));
        setPrivateInt(elevator, "routine", 0x10);
        setPrivateInt(elevator, "anchorX", 0x2AE0);

        Method updateAction = LbzCupElevatorInstance.class.getDeclaredMethod("updateAction");
        updateAction.setAccessible(true);
        updateAction.invoke(elevator);

        assertTrue(getPrivateBoolean(elevator, "flickerMode"),
                "LBZCupElev_Fling2 switches the cup to Obj_LBZElevatorCupFlicker");
        assertEquals(0x200, getPrivateInt(elevator, "xVel"),
                "Fling2 launches the cup to the right (x_vel #$200)");
        assertEquals(0, getPrivateInt(elevator, "yVel"),
                "Fling2 starts the flicker with y_vel 0; gravity builds from there");

        Method updateFlicker = LbzCupElevatorInstance.class.getDeclaredMethod("updateFlicker");
        updateFlicker.setAccessible(true);
        int startY = elevator.getY();
        for (int frame = 1; frame <= 8; frame++) {
            updateFlicker.invoke(elevator);
            assertEquals(frame * SubpixelMotion.S3K_GRAVITY, getPrivateInt(elevator, "yVel"),
                    "MoveSprite adds gravity ($38) to y_vel every flicker frame");
        }
        assertTrue(elevator.getY() > startY,
                "Gravity must drag the cup downward so it falls off-screen, not slide flat");
    }

    @Test
    void flungCupOffscreenExitRemainsPlacementRespawnable() throws Exception {
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0x83, 1, false, 0));
        setPrivateInt(elevator, "x", 0x1800);
        setPrivateInt(elevator, "y", 0x0600);
        Field flicker = LbzCupElevatorInstance.class.getDeclaredField("flickerMode");
        flicker.setAccessible(true);
        flicker.setBoolean(elevator, true);

        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        try {
            elevator.update(1, null);
        } finally {
            AbstractObjectInstance.resetCameraBoundsForTests();
        }

        assertTrue(elevator.isDestroyed());
        assertTrue(elevator.isDestroyedRespawnable(),
                "Obj_LBZElevatorCupFlicker exits through Sprite_OnScreen_Test, which clears the respawn bit");
    }

    @Test
    void flungCupPutsRidersInNativeHitRoutine() throws Exception {
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0x83, 1, false, 0));
        Sonic player = new Sonic("sonic", (short) 0x1800, (short) 0x0600);
        Object state = getPrivateField(elevator, "p1");
        setPlayerStateInside(state, true);

        Class<?> stateClass = Class.forName(
                "com.openggf.game.sonic3k.objects.LbzCupElevatorInstance$PlayerState");
        Method flingPlayer = LbzCupElevatorInstance.class.getDeclaredMethod("flingPlayer",
                stateClass, com.openggf.sprites.playable.AbstractPlayableSprite.class, int.class);
        flingPlayer.setAccessible(true);
        flingPlayer.invoke(elevator, state, player, -0x300);

        assertTrue(player.isHurt(), "sub_26E08 writes player routine=4 before launching the rider");
        assertEquals((short) -0x300, player.getYSpeed());
        assertEquals((short) 0x200, player.getXSpeed());
        assertEquals(com.openggf.physics.Direction.LEFT, player.getDirection(),
                "the flipped branch negates x_vel and sets Status_Facing");
        assertEquals(0x1A, player.getAnimationId());
    }

    @Test
    void attachChildUsesRomFrameAndPositionFormula() {
        int anchorX = 0x1800;

        assertEquals(1, LbzCupElevatorInstance.ATTACH_MAPPING_FRAME,
                "Obj_LBZCupElevatorAttach initializes mapping_frame(a1)=1");
        assertEquals(0x1818, LbzCupElevatorInstance.attachXForTest(anchorX, true, 0),
                "Obj_LBZCupElevatorAttach caches original x_pos in $30 before the +/-$18 spawn offset");
        assertEquals(0x17E8, LbzCupElevatorInstance.attachXForTest(anchorX, false, 0x80),
                "Runtime attach x_pos ignores orientation and uses original x + ((cos(angle)*3)>>5)");
    }

    @Test
    void heldPlayerUsesRomCupTwistFrameAndClearsItOnRelease() throws Exception {
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0, 0, false, 0));
        setPrivateInt(elevator, "angleWord", 0x4000);
        Sonic player = new Sonic("sonic", (short) 0x1800, (short) 0x0600);

        invokeHoldPlayer(elevator, player);

        assertEquals(0x5B, player.getMappingFrame(),
                "loc_32610 maps angle $40 through PlayerTwistFrames index 3");
        assertFalse(player.getRenderHFlip(),
                "loc_32610 uses PlayerTwistFlip index 3 = no horizontal flip");
        assertTrue(player.isObjectMappingFrameControl(),
                "object_control=3 suppresses normal player animation while the cup writes raw mapping frames");
        assertFalse(player.isControlLocked(),
                "Obj18 writes object_control=$03 but never writes the separate Ctrl_1_locked byte");

        Object p1State = getPrivateField(elevator, "p1");
        invokeReleasePlayer(elevator, player, p1State);

        assertFalse(player.isObjectMappingFrameControl(),
                "raw mapping-frame control must reset when the player leaves the cup");
    }

    @Test
    void heldPlayerTwistFlipDoesNotOverwriteGameplayFacing() throws Exception {
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0, 0, false, 0));
        setPrivateInt(elevator, "angleWord", 0x1600);
        Sonic player = new Sonic("sonic", (short) 0x1800, (short) 0x0600);
        player.setDirection(com.openggf.physics.Direction.LEFT);

        invokeHoldPlayer(elevator, player);

        assertTrue(player.getRenderHFlip(),
                "angle $16 selects PlayerTwistFlip index 1 for the visual frame");
        assertEquals(com.openggf.physics.Direction.LEFT, player.getDirection(),
                "loc_32610 writes render_flags but must preserve Status_Facing");
    }

    @Test
    void knucklesCutsceneGateBlocksCupJumpReleaseUntilExitClearsIt() throws Exception {
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0, 0, false, 0));
        LbzZoneRuntimeState runtimeState = new LbzZoneRuntimeState(0, PlayerCharacter.SONIC_ALONE);
        runtimeState.setLbz1KnucklesCutsceneControlLocked(true);
        ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
        registry.install(runtimeState);
        elevator.setServices(new TestObjectServices().withZoneRuntimeRegistry(registry));
        Sonic player = new Sonic("sonic", (short) 0x1800, (short) 0x0600);
        Object p1State = getPrivateField(elevator, "p1");
        setPlayerStateInside(p1State, true);
        player.setJumpInputPressed(true, true);

        elevator.update(0, player);

        assertTrue((boolean) getPrivateField(p1State, "inside"),
                "ROM LBZCupElevator_PlayerControl branches to hold while _unkFAA9 is set.");
        assertTrue(player.isObjectControlled(),
                "The cup must keep owning the player during the Knuckles cutscene gate.");
        assertTrue(player.isObjectMappingFrameControl(),
                "Pressed jump must not leak through as a normal jump animation while the cup owns mapping frames.");
        int heldY = player.getCentreY();

        runtimeState.setLbz1KnucklesCutsceneControlLocked(false);
        player.setJumpInputPressed(true, true);

        elevator.update(1, player);

        assertFalse((boolean) getPrivateField(p1State, "inside"),
                "After Knuckles clears _unkFAA9, the same jump press should release the player from the cup.");
        assertFalse(player.isObjectControlled());
        assertEquals(heldY, player.getCentreY(),
                "Obj18 changes rolling radii/status on release without changing the native y_pos word");
        assertFalse(elevator.isSolidFor(player),
                "the per-player $12 release cooldown returns before SolidObjectFull can recollide with the rider");
        assertEquals(2, player.getAnimationId(),
                "Allowed cup release uses the ROM jump animation, not the held twist frame.");
    }

    @Test
    void activeElevatorDoesNotRecaptureReleasedAirbornePlayer() {
        LbzCupElevatorInstance elevator = new LbzCupElevatorInstance(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0, 0, false, 0));
        Sonic player = new Sonic("sonic", (short) 0x1900, (short) 0x0500);
        player.setAir(true);
        int startX = player.getCentreX();

        setPrivateIntUnchecked(elevator, "activationFlag", 1);

        elevator.update(0, player);

        assertFalse(player.isObjectControlled(),
                "LBZCupElevator_PlayerControl still requires the SolidObject standing bit before capture; "
                        + "$34 only bypasses the center-position check");
        assertEquals(startX, player.getCentreX(),
                "Released airborne Sonic must not be snapped back to the moving cup");
    }

    private static final class ZoneForTestRegistry extends Sonic3kObjectRegistry {
        private final int zoneId;

        private ZoneForTestRegistry(int zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        protected int currentRomZoneId() {
            return zoneId;
        }
    }

    private static void setPrivateInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setPrivateIntUnchecked(Object target, String fieldName, int value) {
        try {
            setPrivateInt(target, fieldName, value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static int getPrivateInt(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static boolean getPrivateBoolean(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static Object getPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setPlayerStateInside(Object state, boolean inside) throws Exception {
        Field field = state.getClass().getDeclaredField("inside");
        field.setAccessible(true);
        field.setBoolean(state, inside);
    }

    private static void invokeHoldPlayer(LbzCupElevatorInstance elevator, Sonic player) throws Exception {
        Method method = LbzCupElevatorInstance.class.getDeclaredMethod("holdPlayer",
                com.openggf.sprites.playable.AbstractPlayableSprite.class);
        method.setAccessible(true);
        method.invoke(elevator, player);
    }

    private static void invokeReleasePlayer(LbzCupElevatorInstance elevator, Sonic player, Object state)
            throws Exception {
        Class<?> stateClass = Class.forName("com.openggf.game.sonic3k.objects.LbzCupElevatorInstance$PlayerState");
        Method method = LbzCupElevatorInstance.class.getDeclaredMethod("releasePlayer",
                com.openggf.sprites.playable.AbstractPlayableSprite.class,
                stateClass,
                int.class,
                boolean.class);
        method.setAccessible(true);
        method.invoke(elevator, player, state, 0, true);
    }
}
