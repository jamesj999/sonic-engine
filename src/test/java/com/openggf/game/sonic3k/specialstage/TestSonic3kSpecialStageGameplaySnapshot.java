package com.openggf.game.sonic3k.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class TestSonic3kSpecialStageGameplaySnapshot {
    @Test
    void gridSnapshotClonesAndRestoresBuffer() {
        Sonic3kSpecialStageGrid grid = new Sonic3kSpecialStageGrid();
        grid.setCellByIndex(0x20, Sonic3kSpecialStageConstants.CELL_BLUE);
        grid.setCellByIndex(0x21, Sonic3kSpecialStageConstants.CELL_RING);

        Sonic3kSpecialStageSnapshot.GridSnapshot snapshot = grid.captureRewindSnapshot();
        grid.setCellByIndex(0x20, Sonic3kSpecialStageConstants.CELL_RED);
        grid.setCellByIndex(0x21, Sonic3kSpecialStageConstants.CELL_EMPTY);

        grid.restoreRewindSnapshot(snapshot);

        assertEquals(Sonic3kSpecialStageConstants.CELL_BLUE, grid.getCellByIndex(0x20));
        assertEquals(Sonic3kSpecialStageConstants.CELL_RING, grid.getCellByIndex(0x21));
        assertNotSame(snapshot.buffer(), liveIntArray(grid, "buffer"));
    }

    @Test
    void playerSnapshotRestoresMovementJumpAnimationAndLatchFields() throws Exception {
        Sonic3kSpecialStagePlayer player = new Sonic3kSpecialStagePlayer();
        player.initialize(0x4000, 0x1234, 0x2345, false);
        set(player, "velocity", 0x1800);
        set(player, "rate", 0x1C00);
        set(player, "rateTimer", 11);
        set(player, "turning", Sonic3kSpecialStageConstants.TURN_LEFT);
        set(player, "turnLock", true);
        set(player, "advancing", true);
        set(player, "started", true);
        set(player, "bumperLock", true);
        set(player, "bumperInteractIndex", 0x155);
        set(player, "jumping", Sonic3kSpecialStageConstants.JUMP_SPRING);
        set(player, "jumpHeight", -0x120000L);
        set(player, "jumpVelocity", -0x20000L);
        set(player, "animFrameTimer", 0x4500);
        set(player, "mappingFrame", 7);
        set(player, "prevMappingFrame", 6);
        set(player, "failed", true);
        set(player, "clearRoutineActive", true);
        set(player, "fadeTimer", 33);
        set(player, "blueSphereMode", true);
        set(player, "rateJustIncreased", true);

        Sonic3kSpecialStageSnapshot.PlayerSnapshot snapshot = player.captureRewindSnapshot();

        player.initialize(0, 0, 0, false);
        player.restoreRewindSnapshot(snapshot);

        assertEquals(0x1234, player.getXPos());
        assertEquals(0x2345, player.getYPos());
        assertEquals(0x40, player.getAngle());
        assertEquals(0x1800, player.getVelocity());
        assertEquals(Sonic3kSpecialStageConstants.JUMP_SPRING, player.getJumping());
        assertEquals(-0x120000L, player.getJumpHeight());
        assertEquals(7, player.getMappingFrame());
        assertEquals(6, player.getPrevMappingFrame());
        assertEquals(true, get(player, "rateJustIncreased"));
    }

    @Test
    void tailsAiSnapshotRestoresDelayBuffersAndIdleState() throws Exception {
        Sonic3kSpecialStageTailsAI ai = new Sonic3kSpecialStageTailsAI();
        ai.initialize();
        ai.update(0x11, 0x80, 0);
        ai.update(0x22, 0, 0x10);
        set(ai, "cpuIdleTimer", 77);
        set(ai, "lastP2Input", 0x10);

        Sonic3kSpecialStageSnapshot.TailsAiSnapshot snapshot = ai.captureRewindSnapshot();

        ai.initialize();
        ai.restoreRewindSnapshot(snapshot);

        assertEquals(2, get(ai, "posTableIndex"));
        assertEquals(77, get(ai, "cpuIdleTimer"));
        assertEquals(0x10, get(ai, "lastP2Input"));
        assertArrayEquals(snapshot.posTableInput(), liveIntArray(ai, "posTableInput"));
        assertArrayEquals(snapshot.posTableJump(), liveIntArray(ai, "posTableJump"));
        assertNotSame(snapshot.posTableInput(), liveIntArray(ai, "posTableInput"));
    }

    @Test
    void collisionQueueSnapshotRestoresAllQueueArrays() throws Exception {
        Sonic3kSpecialStageCollisionQueue queue = new Sonic3kSpecialStageCollisionQueue();
        queue.addRing(0x22);
        queue.addBlueSphere(0x44);

        Sonic3kSpecialStageSnapshot.CollisionQueueSnapshot snapshot = queue.captureRewindSnapshot();
        queue.clear();
        queue.restoreRewindSnapshot(snapshot);

        assertArrayEquals(snapshot.types(), liveIntArray(queue, "types"));
        assertArrayEquals(snapshot.timers(), liveIntArray(queue, "timers"));
        assertArrayEquals(snapshot.frames(), liveIntArray(queue, "frames"));
        assertArrayEquals(snapshot.gridIndices(), liveIntArray(queue, "gridIndices"));
    }

    private static int[] liveIntArray(Object target, String field) {
        return (int[]) get(target, field);
    }

    private static Object get(Object target, String field) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
