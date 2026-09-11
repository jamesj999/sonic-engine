package com.openggf.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestLevelLoadContext {

    @Test
    public void contextStartsEmpty() {
        var ctx = new LevelLoadContext();
        assertNull(ctx.getRom());
        assertEquals(-1, ctx.getLevelIndex());
        assertEquals(-1, ctx.getZone());
        assertEquals(-1, ctx.getAct());
        assertNull(ctx.getLevel());
        assertNull(ctx.getGameModule());
    }

    @Test
    public void contextAccumulatesState() {
        var ctx = new LevelLoadContext();
        ctx.setLevelIndex(5);
        assertEquals(5, ctx.getLevelIndex());
        ctx.setZone(3);
        assertEquals(3, ctx.getZone());
        ctx.setAct(1);
        assertEquals(1, ctx.getAct());
    }

    @Test
    public void defaultValues() {
        var ctx = new LevelLoadContext();
        assertTrue(ctx.isShowTitleCard());
        assertFalse(ctx.isIncludePostLoadAssembly());
        assertEquals(-1, ctx.getSpawnY());
        assertFalse(ctx.hasCheckpoint());
        assertFalse(ctx.hasWaterState());
        assertNull(ctx.getLevelData());
        assertEquals(-1, ctx.getCheckpointIndex());
    }

    @Test
    public void snapshotCheckpoint_nullSafe() {
        var ctx = new LevelLoadContext();
        ctx.snapshotCheckpoint(null);
        assertFalse(ctx.hasCheckpoint());
    }

    @Test
    public void snapshotCheckpoint_inactiveRespawnState() {
        var ctx = new LevelLoadContext();
        CheckpointState state = new CheckpointState();
        ctx.snapshotCheckpoint(state);
        assertFalse(ctx.hasCheckpoint());
    }

    @Test
    public void snapshotCheckpoint_copiesAllFields() {
        var ctx = new LevelLoadContext();
        CheckpointState state = new CheckpointState();
        state.restoreFromSaved(0x500, 0x200, 0x400, 0x180, 3);

        ctx.snapshotCheckpoint(state);

        assertTrue(ctx.hasCheckpoint());
        assertEquals(0x500, ctx.getCheckpointX());
        assertEquals(0x200, ctx.getCheckpointY());
        assertEquals(0x400, ctx.getCheckpointCameraX());
        assertEquals(0x180, ctx.getCheckpointCameraY());
        assertEquals(3, ctx.getCheckpointIndex());
        assertFalse(ctx.hasWaterState());
    }

    @Test
    public void snapshotCheckpoint_withWaterState() {
        var ctx = new LevelLoadContext();
        CheckpointState state = new CheckpointState();
        state.restoreFromSaved(0x100, 0x200, 0x80, 0x100, 1);
        state.saveWaterState(0x300, 2);

        ctx.snapshotCheckpoint(state);

        assertTrue(ctx.hasCheckpoint());
        assertTrue(ctx.hasWaterState());
        assertEquals(0x300, ctx.getCheckpointWaterLevel());
        assertEquals(2, ctx.getCheckpointWaterRoutine());
    }

    @Test
    public void snapshotCheckpoint_clearsStaleDataOnInactiveState() {
        var ctx = new LevelLoadContext();
        // First: snapshot an active checkpoint with water state
        CheckpointState active = new CheckpointState();
        active.restoreFromSaved(0x500, 0x200, 0x400, 0x180, 3);
        active.saveWaterState(0x300, 2);
        ctx.snapshotCheckpoint(active);
        assertTrue(ctx.hasCheckpoint());
        assertTrue(ctx.hasWaterState());

        // Second: snapshot an inactive state â€” must clear all stale data
        CheckpointState inactive = new CheckpointState();
        ctx.snapshotCheckpoint(inactive);
        assertFalse(ctx.hasCheckpoint());
        assertFalse(ctx.hasWaterState());
        assertEquals(0, ctx.getCheckpointX());
        assertEquals(0, ctx.getCheckpointWaterLevel());
        assertEquals(-1, ctx.getCheckpointIndex());
    }

    @Test
    public void snapshotCheckpoint_clearsWaterWhenActiveButNoWater() {
        var ctx = new LevelLoadContext();
        // First: snapshot with water
        CheckpointState withWater = new CheckpointState();
        withWater.restoreFromSaved(0x100, 0x200, 0x80, 0x100, 1);
        withWater.saveWaterState(0x300, 2);
        ctx.snapshotCheckpoint(withWater);
        assertTrue(ctx.hasWaterState());

        // Second: snapshot active but without water â€” water fields must clear
        CheckpointState noWater = new CheckpointState();
        noWater.restoreFromSaved(0x400, 0x500, 0x300, 0x400, 5);
        ctx.snapshotCheckpoint(noWater);
        assertTrue(ctx.hasCheckpoint());
        assertFalse(ctx.hasWaterState());
        assertEquals(0, ctx.getCheckpointWaterLevel());
    }
}


