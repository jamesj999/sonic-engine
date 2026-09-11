package com.openggf.tests;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.objects.badniks.PenguinatorBadnikInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

@FullReset
@ExtendWith(SingletonResetExtension.class)
class TestS3kPenguinatorBadnik {

    @Test
    void registryCreatesPenguinatorInstance() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.PENGUINATOR, 0, 0, false, 0));

        assertInstanceOf(PenguinatorBadnikInstance.class, instance);
    }

    @Test
    void exposesRomCollisionFlagsAndPriority() {
        PenguinatorBadnikInstance penguinator = create(0);

        assertEquals(0x1A, penguinator.getCollisionFlags());
        assertEquals(5, penguinator.getPriorityBucket());
    }

    @Test
    void firstPatrolTickAcceleratesInSpawnFacingDirection() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        PenguinatorBadnikInstance penguinator = create(0);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 1));

            penguinator.update(0, player);
            assertEquals(0, readInt(penguinator, "xVelocity"),
                    "ROM init frame sets $40 but does not move yet");

            penguinator.update(1, player);
        }

        assertEquals(-2, readInt(penguinator, "xVelocity"),
                "render_flags bit 0 clear initializes $40 to -2");
        assertEquals(1, readInt(penguinator, "mappingFrame"),
                "byte_8BE0A first Animate_RawGetFaster tick selects frame 1");
    }

    @Test
    void flippedSpawnAcceleratesRightOnFirstPatrolTick() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        PenguinatorBadnikInstance penguinator = create(1);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 1));

            penguinator.update(0, player);
            penguinator.update(1, player);
        }

        assertEquals(2, readInt(penguinator, "xVelocity"),
                "render_flags bit 0 set initializes $40 to +2");
    }

    @Test
    void waitOffscreenStartsWhenPlaceholderRenderBoundsOverlapViewport() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 447, 223, 0);
        PenguinatorBadnikInstance penguinator = new PenguinatorBadnikInstance(
                new ObjectSpawn(478, 100, Sonic3kObjectIds.PENGUINATOR, 0, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);

        penguinator.update(0, player);

        assertEquals("PATROL", readEnumName(penguinator, "state"),
                "Obj_WaitOffscreen uses the $20 placeholder half extents, not a centre-point X gate");
        assertEquals(478, penguinator.getX(), "activation frame initializes without moving");
    }

    @Test
    void slideWaitExpiresIntoSlideRecoveryInsteadOfRestartingPatrol() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        PenguinatorBadnikInstance penguinator = create(0);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);

        setEnum(penguinator, "state", "SLIDE_WAIT");
        setInt(penguinator, "routineTimer", 0);
        setInt(penguinator, "yRadius", 0x0B);
        setInt(penguinator, "mappingFrame", 8);
        setInt(penguinator, "xVelocity", -0x200);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 1));

            penguinator.update(0, player);
        }

        assertEquals("SLIDE_RECOVER", readEnumName(penguinator, "state"),
                "loc_8BC6C Obj_Wait callback must run loc_8BC94, not loc_8BB24");
        assertEquals(4, readInt(penguinator, "animFrame"),
                "sub_8BD9C updates mapping_frame before loc_8BC94 seeds anim_frame to 8 - mapping_frame");
    }

    @Test
    void slideRecoveryPublishesFinalFrameThreeBeforeDecelerating() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        PenguinatorBadnikInstance penguinator = create(0);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        setEnum(penguinator, "state", "SLIDE_RECOVER");
        setInt(penguinator, "yRadius", 0x0B);
        setInt(penguinator, "animFrame", 4);
        setInt(penguinator, "animTimer", 0);
        setInt(penguinator, "mappingFrame", 4);
        setInt(penguinator, "xVelocity", -0x200);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 1));

            for (int frame = 0; frame < 8; frame++) {
                penguinator.update(frame, player);
            }
            assertEquals("SLIDE_RECOVER", readEnumName(penguinator, "state"));
            assertEquals(3, readInt(penguinator, "mappingFrame"),
                    "byte_8BE16 publishes its final frame 3 for a full delay interval");

            penguinator.update(8, player);
        }

        assertEquals("DECELERATE", readEnumName(penguinator, "state"),
                "the F4 callback runs only after the final frame-3 interval");
    }

    @Test
    void hopEntryPreservesRawAnimatorPhase() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        PenguinatorBadnikInstance penguinator = create(0);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        setEnum(penguinator, "state", "WAIT");
        setEnum(penguinator, "waitCallback", "JUMP");
        setInt(penguinator, "routineTimer", 0);
        setInt(penguinator, "animFrame", 2);
        setInt(penguinator, "animTimer", 2);
        setInt(penguinator, "mappingFrame", 1);

        penguinator.update(0, player);

        assertEquals("HOP", readEnumName(penguinator, "state"));
        assertEquals(2, readInt(penguinator, "animFrame"));
        assertEquals(2, readInt(penguinator, "animTimer"));
        assertEquals(1, readInt(penguinator, "mappingFrame"),
                "loc_8BBAC changes the raw script pointer without resetting its phase");
    }

    @Test
    void groundAngleDecisionConsumesFindFloorFlipTransform() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        PenguinatorBadnikInstance penguinator = create(1);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        setEnum(penguinator, "state", "PATROL");
        setBoolean(penguinator, "rawGetFasterPrimed", true);
        setInt(penguinator, "rawDelay", 2);
        setInt(penguinator, "animFrame", 0);
        setInt(penguinator, "animTimer", 0);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDistWithFlipAwareAngle(0x0200, 0x0100, 0x0F))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0x04, 1));

            penguinator.update(0, player);
        }

        assertEquals("HOP", readEnumName(penguinator, "state"),
                "ObjCheckFloorDist publishes the chunk-flip-adjusted angle before the facing-bit test");
    }

    private static PenguinatorBadnikInstance create(int renderFlags) {
        return new PenguinatorBadnikInstance(
                new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.PENGUINATOR, 0, renderFlags, false, 0));
    }

    private static int readInt(PenguinatorBadnikInstance penguinator, String fieldName) {
        try {
            Class<?> type = penguinator.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.getInt(penguinator);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            throw new AssertionError("Missing field " + fieldName);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to read " + fieldName, e);
        }
    }

    private static String readEnumName(PenguinatorBadnikInstance penguinator, String fieldName) {
        Object value = readField(penguinator, fieldName);
        return ((Enum<?>) value).name();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setEnum(PenguinatorBadnikInstance penguinator, String fieldName, String valueName) {
        Field field = findField(penguinator, fieldName);
        try {
            field.setAccessible(true);
            field.set(penguinator, Enum.valueOf((Class<Enum>) field.getType(), valueName));
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to write " + fieldName, e);
        }
    }

    private static void setInt(PenguinatorBadnikInstance penguinator, String fieldName, int value) {
        Field field = findField(penguinator, fieldName);
        try {
            field.setAccessible(true);
            field.setInt(penguinator, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to write " + fieldName, e);
        }
    }

    private static void setBoolean(PenguinatorBadnikInstance penguinator, String fieldName, boolean value) {
        Field field = findField(penguinator, fieldName);
        try {
            field.setAccessible(true);
            field.setBoolean(penguinator, value);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to write " + fieldName, e);
        }
    }

    private static Object readField(PenguinatorBadnikInstance penguinator, String fieldName) {
        Field field = findField(penguinator, fieldName);
        try {
            field.setAccessible(true);
            return field.get(penguinator);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to read " + fieldName, e);
        }
    }

    private static Field findField(PenguinatorBadnikInstance penguinator, String fieldName) {
        Class<?> type = penguinator.getClass();
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
