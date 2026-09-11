package com.openggf.tests;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.resources.CompressionType;
import com.openggf.tools.Sonic3kObjectProfile;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Object-registry assertions depend on the S3K zone set, which the registry
 * derives from the globally loaded level: {@code getCurrentZoneSet()} answers
 * S3KL only while no level is loaded, and any earlier test in the same reused
 * fork that left an MHZ-DDZ level behind flips it to SKL, at which point every
 * S3KL-only factory hands back a {@code PlaceholderObjectInstance}. A full
 * reset clears the session, so the zone set these tests assert against is
 * established rather than inherited.
 */
@FullReset
@ExtendWith(SingletonResetExtension.class)
class TestS3kFlybot767Badnik {

    @Test
    void registryCreatesFlybot767AndProfileMarksS3klSlotImplemented() {
        ObjectInstance instance = createFlybot();

        assertEquals("Flybot767BadnikInstance", instance.getClass().getSimpleName());
        assertTrue(new Sonic3kObjectProfile().getImplementedIds().contains(Sonic3kObjectIds.FLYBOT_767));
    }

    @Test
    void chaseStateSwitchesToAttackWindupWhenPlayerIsBelowWithinSixtyPixels() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance flybot = createFlybot();
        AbstractPlayableSprite player = playerAt(0x0230, 0x0140);
        setBoolean(flybot, "waitingForOnscreen", false);

        flybot.update(0, player);
        flybot.update(1, player);

        assertEquals("ATTACK_WINDUP", readEnumName(flybot, "state"));
        assertEquals(0, readInt(flybot, "yVelocity"),
                "loc_8C9EC clears y_vel before the windup animation");
        assertEquals(flybot.getY(), readInt(flybot, "originY"),
                "loc_8C9EC snapshots y_pos in objoff_44 for the rebound limit");
    }

    @Test
    void reboundAboveOriginStopsAndWaitsBeforeReturningToChase() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance flybot = createFlybot();
        AbstractPlayableSprite player = playerAt(0x0200, 0x0140);
        setEnum(flybot, "state", "REBOUND");
        setInt(flybot, "originY", 0x0120);
        setInt(flybot, "yVelocity", -0x200);
        setInt(flybot, "currentY", 0x0120);
        setBoolean(flybot, "waitingForOnscreen", false);

        flybot.update(0, player);

        assertEquals("WAIT_RETURN", readEnumName(flybot, "state"));
        assertEquals(0, readInt(flybot, "xVelocity"));
        assertEquals(0, readInt(flybot, "yVelocity"));
        assertEquals(0x1F, readInt(flybot, "waitTimer"));
    }

    @Test
    void restoredRoutineContinuesMovementDuringBriefViewportExit() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x0140, 0x0300, 0);
        AbstractObjectInstance flybot = createFlybotAt(0x0160, 0x0100);
        AbstractPlayableSprite player = playerAt(0x0160, 0x0140);
        setEnum(flybot, "state", "DIVE");
        setInt(flybot, "xVelocity", 0x200);
        setInt(flybot, "yVelocity", 0x200);
        setInt(flybot, "waitTimer", 0x20);
        setBoolean(flybot, "waitingForOnscreen", false);

        flybot.update(0, player);

        assertEquals(0x0162, flybot.getX(),
                "Sprite_CheckDeleteTouchSlotted runs the restored routine before its deletion check");
        assertEquals(0x0102, flybot.getY());
        assertFalse(flybot.isDestroyed(),
                "the native coarse-X window extends beyond the exact viewport");
        assertTrue(flybot.publishesTouchResponseListEntryThisFrame());
    }

    @Test
    void restoredRoutineDeletesAfterMovementOutsideNativeCoarseRange() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x0140, 0x0300, 0);
        AbstractObjectInstance flybot = createFlybotAt(0x0400, 0x0100);
        AbstractPlayableSprite player = playerAt(0x0400, 0x0140);
        setEnum(flybot, "state", "DIVE");
        setInt(flybot, "xVelocity", 0x200);
        setInt(flybot, "yVelocity", 0x200);
        setInt(flybot, "waitTimer", 0x20);
        setBoolean(flybot, "waitingForOnscreen", false);

        flybot.update(0, player);

        assertEquals(0x0402, flybot.getX(),
                "Sprite_CheckDeleteTouchSlotted runs movement before its coarse-X check");
        assertEquals(0x0102, flybot.getY());
        assertTrue(flybot.isDestroyed());
        assertTrue(flybot.isDestroyedRespawnable());
        assertFalse(flybot.publishesTouchResponseListEntryThisFrame());
    }

    @Test
    void traceFrame410KeepsFlybotOnePixelColumnBeforeTouchOverlap() {
        AbstractObjectInstance.updateCameraBounds(0, 0x0500, 0x0400, 0x0700, 0);
        AbstractObjectInstance flybot = createFlybotAt(0x0406, 0x05CC);
        AbstractPlayableSprite player = playerAt(0x0346, 0x062C);

        // The ROM allocates the Obj_WaitOffscreen placeholder during row 307; that
        // pass draws it, row 308's pass observes the published flag and restores,
        // and the first Obj_Flybot767 pass is row 309 (aux object_appeared for slot
        // 7 records 0x00085AD2 at 307 and 0x0008C96C at 308).
        for (int frame = 307; frame <= 410; frame++) {
            flybot.update(frame, player);
            flybot.refreshPostCameraRenderState();
        }

        assertEquals("DIVE", readEnumName(flybot, "state"),
                "ROM Obj_Flybot767 is in routine $06 at LBZ1 trace frame 410.");
        assertEquals(0x035B, flybot.getX(),
                "Obj_WaitOffscreen plus Animate_Raw byte_8CB36/byte_8CB3F keep x_pos at the ROM frame-410 value.");
        assertEquals(0x0617, flybot.getY(),
                "Animate_Raw skips the first script entry after clr.b anim_frame, so y_pos must match ROM frame 410.");
    }

    @Test
    void firstOnscreenWaitPassDoesNotPublishTouchResponseListEntry() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        AbstractObjectInstance flybot = createFlybot();
        AbstractPlayableSprite player = playerAt(0x0200, 0x0140);

        flybot.update(0, player);

        assertEquals("INIT", readEnumName(flybot, "state"),
                "Obj_WaitOffscreen returns before Obj_Flybot767 dispatches its routine.");
        assertFalse(flybot.publishesTouchResponseListEntryThisFrame(),
                "The wait helper returns before Sprite_CheckDeleteTouchSlotted can add Flybot to the S3K touch list.");
        assertFalse(flybot.usesCurrentTouchResponseState(),
                "an immediately visible alarm-spawned Flybot retains the frame-start touch phase");
    }

    @Test
    void waitOffscreenRequiresPlaceholderToEnterVerticalRenderBounds() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x0140, 0x00E0, 0);
        AbstractObjectInstance flybot = createFlybotAt(0x0100, 0x0120);
        AbstractPlayableSprite player = playerAt(0x0100, 0x0140);

        flybot.update(0, player);
        flybot.update(1, player);
        flybot.update(2, player);

        assertEquals("INIT", readEnumName(flybot, "state"));
        assertFalse(flybot.publishesTouchResponseListEntryThisFrame(),
                "Obj_WaitOffscreen tests Render_Sprites bit 7, including the placeholder's Y bounds");
    }

    @Test
    void layoutPlacementPublishesLiveSstCoordinateAfterWaitOffscreenRestore() {
        AbstractObjectInstance.updateCameraBounds(0x0700, 0, 0x0900, 0x0300, 0);
        AbstractObjectInstance flybot = createPlacedFlybotAt(0x0800, 0x0100);
        AbstractPlayableSprite player = playerAt(0x0800, 0x0140);

        flybot.update(0, player);
        flybot.refreshPostCameraRenderState();
        flybot.update(1, player);
        flybot.update(2, player);

        assertTrue(flybot.usesCurrentTouchResponseState(),
                "a layout slot exposes the live SST coordinate published after its object pass");
        assertEquals("CHASE", readEnumName(flybot, "state"));
        assertTrue(flybot.publishesTouchResponseListEntryThisFrame());
    }

    @Test
    void layoutPlacementRestoresOnFirstVisibleDispatchAfterOffscreenWait() {
        AbstractObjectInstance.updateCameraBounds(0x0700, 0, 0x0900, 0x00E0, 0);
        AbstractObjectInstance flybot = createPlacedFlybotAt(0x0800, 0x0120);
        AbstractPlayableSprite player = playerAt(0x0800, 0x0140);

        flybot.update(0, player);
        flybot.refreshPostCameraRenderState();
        AbstractObjectInstance.updateCameraBounds(0x0700, 0x0100, 0x0900, 0x0300, 0);
        flybot.update(1, player);
        flybot.refreshPostCameraRenderState();
        flybot.update(2, player);
        assertEquals("INIT", readEnumName(flybot, "state"),
                "Obj_WaitOffscreen restores the saved Flybot operation and returns");
        flybot.update(3, player);

        assertEquals("CHASE", readEnumName(flybot, "state"));
        assertTrue(flybot.publishesTouchResponseListEntryThisFrame());
    }

    @Test
    void layoutWaitReadsRenderFlagPublishedAfterHorizontalCameraStep() {
        AbstractObjectInstance.updateCameraBounds(0x0700, 0x0100, 0x0900, 0x0300, 0);
        AbstractObjectInstance flybot = createPlacedFlybotAt(0x0980, 0x0120);
        AbstractPlayableSprite player = playerAt(0x0980, 0x0140);

        flybot.update(0, player);
        AbstractObjectInstance.updateCameraBounds(0x0800, 0x0100, 0x0A00, 0x0300, 0);
        flybot.refreshPostCameraRenderState();
        flybot.update(1, player);
        assertEquals("INIT", readEnumName(flybot, "state"),
                "loc_85AD2 restores Obj_Flybot767 from the prior post-camera render flag");
        flybot.update(2, player);

        assertEquals("CHASE", readEnumName(flybot, "state"));
        assertTrue(flybot.publishesTouchResponseListEntryThisFrame());
    }

    @Test
    void alarmSpawnedWaitDoesNotRestoreInItsOwnCreationFrame() {
        AbstractObjectInstance.updateCameraBounds(0x0700, 0, 0x0900, 0x0300, 0);
        AbstractObjectInstance flybot = createFlybotAt(0x0800, 0x0120);
        AbstractPlayableSprite player = playerAt(0x0800, 0x0140);

        // loc_85AD2 reads render_flags bit 7, which only a PRECEDING Render_Sprites
        // pass can have set, so the creation-frame pass can only draw.
        flybot.update(0, player);
        assertEquals("INIT", readEnumName(flybot, "state"),
                "the creation-frame pass draws the placeholder; it cannot observe its own flag");
        assertTrue(readBoolean(flybot, "waitingForOnscreen"),
                "Obj_WaitOffscreen has not reached loc_85B02 yet");

        flybot.refreshPostCameraRenderState();
        flybot.update(1, player);
        assertEquals("INIT", readEnumName(flybot, "state"),
                "loc_85B02 restores the saved operation and rts -- it does not run it");
        assertFalse(readBoolean(flybot, "waitingForOnscreen"));

        flybot.update(2, player);
        assertEquals("CHASE", readEnumName(flybot, "state"),
                "Obj_Flybot767 dispatch resumes on the pass after the restore");
    }

    @Test
    void alarmSpawnedAndLayoutWaitsWakeOnTheSamePhase() {
        AbstractObjectInstance.updateCameraBounds(0x0700, 0, 0x0900, 0x0300, 0);
        AbstractObjectInstance dynamic = createFlybotAt(0x0800, 0x0120);
        AbstractObjectInstance placed = createPlacedFlybotAt(0x0800, 0x0120);
        AbstractPlayableSprite player = playerAt(0x0800, 0x0140);

        for (int frame = 0; frame <= 2; frame++) {
            dynamic.update(frame, player);
            dynamic.refreshPostCameraRenderState();
            placed.update(frame, player);
            placed.refreshPostCameraRenderState();
        }

        assertEquals(readEnumName(placed, "state"), readEnumName(dynamic, "state"),
                "both spawn routes read the same published render flag, so they wake together");
        assertEquals(placed.getX(), dynamic.getX());
        assertEquals(placed.getY(), dynamic.getY());
    }

    @Test
    void lbzArtPlanRegistersUncompressedDplcFlybotSheet() {
        Sonic3kPlcArtRegistry.StandaloneArtEntry entry =
                Sonic3kPlcArtRegistry.getPlan(0x06, 0).standaloneArt().stream()
                        .filter(e -> Sonic3kObjectArtKeys.FLYBOT_767.equals(e.key()))
                        .findFirst()
                        .orElseThrow();

        assertEquals(Sonic3kConstants.ART_UNC_FLYBOT_767_ADDR, entry.artAddr());
        assertEquals(Sonic3kConstants.ART_UNC_FLYBOT_767_SIZE, entry.artSize());
        assertEquals(Sonic3kConstants.MAP_FLYBOT_767_ADDR, entry.mappingAddr());
        assertEquals(Sonic3kConstants.DPLC_FLYBOT_767_ADDR, entry.dplcAddr());
        assertEquals(CompressionType.UNCOMPRESSED, entry.compression());
        assertEquals(1, entry.palette());
    }

    private static AbstractObjectInstance createFlybot() {
        return createFlybotAt(0x0200, 0x0100);
    }

    private static AbstractObjectInstance createFlybotAt(int x, int y) {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(x, y, Sonic3kObjectIds.FLYBOT_767, 0, 0, false, 0));
        assertTrue(instance instanceof AbstractObjectInstance,
                "Flybot767 registry entry should create an object instance");
        return (AbstractObjectInstance) instance;
    }

    private static AbstractObjectInstance createPlacedFlybotAt(int x, int y) {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(x, y, Sonic3kObjectIds.FLYBOT_767, 0, 0, false, 0, 123));
        assertTrue(instance instanceof AbstractObjectInstance,
                "Flybot767 registry entry should create an object instance");
        return (AbstractObjectInstance) instance;
    }

    private static AbstractPlayableSprite playerAt(int x, int y) {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn(Short.valueOf((short) x));
        when(player.getCentreY()).thenReturn(Short.valueOf((short) y));
        when(player.getDead()).thenReturn(false);
        return player;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setEnum(Object target, String fieldName, String valueName) {
        Field field = findField(target, fieldName);
        try {
            field.setAccessible(true);
            field.set(target, Enum.valueOf((Class<Enum>) field.getType(), valueName));
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to write " + fieldName, e);
        }
    }

    private static void setInt(Object target, String fieldName, int value) {
        Field field = findField(target, fieldName);
        try {
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to write " + fieldName, e);
        }
    }

    private static void setBoolean(Object target, String fieldName, boolean value) {
        Field field = findField(target, fieldName);
        try {
            field.setAccessible(true);
            field.setBoolean(target, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to write " + fieldName, e);
        }
    }

    private static int readInt(Object target, String fieldName) {
        Field field = findField(target, fieldName);
        try {
            field.setAccessible(true);
            return field.getInt(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to read " + fieldName, e);
        }
    }

    private static boolean readBoolean(Object target, String fieldName) {
        try {
            Field field = findField(target, fieldName);
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static String readEnumName(Object target, String fieldName) {
        Field field = findField(target, fieldName);
        try {
            field.setAccessible(true);
            return ((Enum<?>) field.get(target)).name();
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to read " + fieldName, e);
        }
    }

    private static Field findField(Object target, String fieldName) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                return type.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new AssertionError("Missing field " + fieldName);
    }
}
