package com.openggf.game.sonic1.objects;

import com.openggf.game.GameStateManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the time-attack giant-ring softlock: touching the ring
 * normally hides and control-locks the player (via the spawned Ring Flash
 * object), then the results-screen sequence advances the zone/act — all well
 * before GameLoop's special-stage chokepoint is ever reached. {@link
 * Sonic1GiantRingObjectInstance#onTouchResponse} must check
 * {@code GameStateManager.isTimeAttackActive()} and skip the reaction
 * entirely — the ring stays inert and the player passes through untouched.
 */
class TestSonic1GiantRingTimeAttackGate {

    private static final int RING_X = 160;
    private static final int RING_Y = 112;

    private GameStateManager gameState;
    private CapturingObjectServices services;

    @BeforeEach
    void setUp() {
        gameState = new GameStateManager();
        gameState.resetSession();
        services = (CapturingObjectServices) new CapturingObjectServices().withGameState(gameState);
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @AfterEach
    void tearDown() {
        clearConstructionContext();
    }

    @Test
    void ringStaysInertWhenTimeAttackActive() {
        gameState.setTimeAttackActive(true);

        Sonic1GiantRingObjectInstance ring = createRing();
        AbstractPlayableSprite player = createMockPlayer();

        ring.update(1, player); // INIT -> ACTIVE (>=50 rings, <6 emeralds, on-screen)
        ring.onTouchResponse(player, specialTouch(), 1);
        // ReactToItem queues collection; GRing_Collect runs on the object dispatch.
        ring.update(2, player);

        assertFalse(services.sfxPlayed, "Giant ring SFX must not play while a time attack is active");
        verify(player, never()).setHidden(anyBoolean());
    }

    @Test
    void ringCollectsNormallyWhenTimeAttackInactive() {
        Sonic1GiantRingObjectInstance ring = createRing();
        AbstractPlayableSprite player = createMockPlayer();

        ring.update(1, player);
        ring.onTouchResponse(player, specialTouch(), 1);
        // ReactToItem queues collection; GRing_Collect runs on the object dispatch.
        ring.update(2, player);

        assertTrue(services.sfxPlayed, "Precondition: normal touch (no time attack) still plays the giant-ring SFX");
    }

    private TouchResponseResult specialTouch() {
        return new TouchResponseResult(0, 0, 0, TouchCategory.SPECIAL);
    }

    private AbstractPlayableSprite createMockPlayer() {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) RING_X);
        when(player.getCentreY()).thenReturn((short) RING_Y);
        when(player.getRingCount()).thenReturn(50);
        when(player.getInvulnerableFrames()).thenReturn(0);
        return player;
    }

    private Sonic1GiantRingObjectInstance createRing() {
        setConstructionContext(services);
        try {
            ObjectSpawn spawn = new ObjectSpawn(RING_X, RING_Y, 0x4B, 0, 0, false, 0);
            Sonic1GiantRingObjectInstance ring = new Sonic1GiantRingObjectInstance(spawn);
            ring.setServices(services);
            return ring;
        } finally {
            clearConstructionContext();
        }
    }

    private static class CapturingObjectServices extends TestObjectServices {
        boolean sfxPlayed;

        @Override
        public CapturingObjectServices withGameState(GameStateManager gameState) {
            super.withGameState(gameState);
            return this;
        }

        @Override
        public void playSfx(int soundId) {
            sfxPlayed = true;
        }
    }

    @SuppressWarnings("unchecked")
    private static void setConstructionContext(com.openggf.level.objects.ObjectServices svc) {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).set(svc);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearConstructionContext() {
        try {
            Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
            field.setAccessible(true);
            ((ThreadLocal<Object>) field.get(null)).remove();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
