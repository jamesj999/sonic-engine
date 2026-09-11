package com.openggf.game.sonic3k.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link Sonic3kSpecialStageManager#captureComparisonState()} produces a pure,
 * field-accurate read-only snapshot suitable for trace replay comparison (addition #3).
 */
class TestSonic3kSpecialStageComparisonState {

    @Test
    void captureComparisonStateMirrorsAllGettersAndIsPureRead() {
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        seedManagerWithDistinctScalars(manager);
        Sonic3kSpecialStagePlayer player = manager.getPlayer();
        seedPlayerWithDistinctScalars(player);

        Sonic3kSpecialStageComparisonState state = manager.captureComparisonState();

        assertEquals(player.getXPos(), state.playerX());
        assertEquals(player.getYPos(), state.playerY());
        assertEquals(player.getAngle(), state.angle());
        assertEquals(player.getVelocity(), state.velocity());
        assertEquals(player.getTurning(), state.turning());
        assertEquals(player.getJumping(), state.jumping());
        assertEquals(player.getFadeTimer(), state.fadeTimer());
        assertEquals(player.isStarted(), state.started());
        assertEquals(manager.getSpheresLeft(), state.spheresLeft());
        assertEquals(manager.getRingsCollected(), state.ringsCollected());
        assertEquals(manager.getRingsLeft(), state.ringsLeft());
        assertEquals(manager.getFrameCounter(), state.frameCounter());
        assertEquals(manager.getClearRoutine(), state.clearRoutine());
        assertEquals(manager.getClearTimer(), state.clearTimer());
        assertEquals(manager.isFinished(), state.finished());
        assertEquals(manager.hasEmeraldCollected(), state.emeraldCollected());

        Sonic3kSpecialStageComparisonState second = manager.captureComparisonState();
        assertEquals(state, second);

        // Second pass: flip started/finished and re-capture so every pairwise combination
        // of the three boolean fields (started/finished/emeraldCollected) differs somewhere
        // across the two passes. Pass 1 above (started=true, finished=false,
        // emeraldCollected=true) alone leaves started and emeraldCollected seeded identically
        // in every pass, so a getter swapped between those two would go undetected; keeping
        // emeraldCollected=true here (instead of also negating it) breaks that tie while still
        // covering the started/finished and finished/emeraldCollected pairs.
        set(manager, "finished", true);
        set(manager, "emeraldCollected", true);
        set(player, "started", false);

        Sonic3kSpecialStageComparisonState state2 = manager.captureComparisonState();

        assertEquals(player.isStarted(), state2.started());
        assertEquals(manager.isFinished(), state2.finished());
        assertEquals(manager.hasEmeraldCollected(), state2.emeraldCollected());
    }

    private static void seedManagerWithDistinctScalars(Sonic3kSpecialStageManager manager) {
        set(manager, "ringsCollected", 17);
        set(manager, "spheresLeft", 88);
        set(manager, "ringsLeft", 5);
        set(manager, "frameCounter", 1234);
        set(manager, "clearRoutine", 3);
        set(manager, "clearTimer", 9);
        set(manager, "finished", false);
        set(manager, "emeraldCollected", true);
    }

    private static void seedPlayerWithDistinctScalars(Sonic3kSpecialStagePlayer player) {
        set(player, "xPos", 0x1234);
        set(player, "yPos", 0x5678);
        set(player, "angle", 0x40);
        set(player, "velocity", 0x0300);
        set(player, "turning", 2);
        set(player, "jumping", 0x80);
        set(player, "fadeTimer", 11);
        set(player, "started", true);
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
