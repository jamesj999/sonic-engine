package com.openggf.game.sonic1.objects;

import com.openggf.game.GameStateManager;
import com.openggf.audio.GameMusic;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSonic1GiantRingObjectInstance {

    @Test
    void touchDefersCollectBodyUntilGiantRingsOwnObjectSlot() throws Exception {
        Sonic1GiantRingObjectInstance ring = new Sonic1GiantRingObjectInstance(
                new ObjectSpawn(0x1FAC, 0x0350, 0x4B, 0, 0, false, 0));
        ObjectManager objectManager = mock(ObjectManager.class);
        ring.setServices(new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });
        setState(ring, "ACTIVE");

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getInvulnerableFrames()).thenReturn(0);
        when(player.getCentreX()).thenReturn((short) 0x1FA0);

        ring.onTouchResponse(player,
                new TouchResponseResult(0x12, 8, 16, TouchCategory.SPECIAL), 0);

        verify(objectManager, never()).addDynamicObject(any());

        ring.update(1, player);

        verify(objectManager).addDynamicObject(any(Sonic1RingFlashObjectInstance.class));
    }

    @Test
    void flashFrameThreeAppliesRomWritesWithoutTakingMovementOwnership() {
        Sonic1RingFlashObjectInstance flash = new Sonic1RingFlashObjectInstance(
                null, 0x1FAC, 0x0350, false);
        GameStateManager gameState = mock(GameStateManager.class);
        flash.setServices(new StubObjectServices() {
            @Override
            public GameStateManager gameState() {
                return gameState;
            }
        });
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);

        for (int frame = 0; frame < 7; frame++) {
            flash.update(frame, player);
        }

        verify(gameState).setBigRingCollected(true);
        verify(player).setHidden(true);
        verify(player).setAnimationId(Sonic1AnimationIds.NULL.id());
        verify(player).setForcedAnimationId(Sonic1AnimationIds.NULL.id());
        verify(player).clearPowerUps();
        verify(player, never()).applyObjectControlState(any(ObjectControlState.class));

        reset(player);
        flash.update(7, player);
        verify(player).setAnimationId(Sonic1AnimationIds.NULL.id());
        verify(player).setForcedAnimationId(Sonic1AnimationIds.NULL.id());
    }

    @Test
    void flashCompletionRepresentsDeletedPlayerBySuppressingFurtherMovement() {
        Sonic1RingFlashObjectInstance flash = new Sonic1RingFlashObjectInstance(
                null, 0x1FAC, 0x0350, false);
        flash.setServices(new StubObjectServices() {
            @Override
            public GameStateManager gameState() {
                return mock(GameStateManager.class);
            }
        });
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);

        for (int frame = 0; frame < 17; frame++) {
            flash.update(frame, player);
        }

        verify(player).setNativeSlotPresent(false);
        verify(player).applyObjectControlState(ObjectControlState.NATIVE_BIT_7_FULL_CONTROL);
    }

    @Test
    void flashCompletionLeavesGotThroughActAndEndCardCreationToSignpost() {
        Sonic1RingFlashObjectInstance flash = new Sonic1RingFlashObjectInstance(
                null, 0x1FAC, 0x0350, false);
        ObjectServices services = mock(ObjectServices.class);
        GameStateManager gameState = mock(GameStateManager.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(services.gameState()).thenReturn(gameState);
        when(services.objectManager()).thenReturn(objectManager);
        when(objectManager.getActiveObjects()).thenReturn(List.of());
        flash.setServices(services);

        for (int frame = 0; frame < 17; frame++) {
            flash.update(frame, mock(AbstractPlayableSprite.class));
        }

        verify(objectManager, never()).addDynamicObject(any());
        verify(services, never()).playMusic(GameMusic.ACT_CLEAR);
    }

    @Test
    void flashCompletionReusesEndCardAlreadyStartedBySignpost() throws Exception {
        Sonic1RingFlashObjectInstance flash = new Sonic1RingFlashObjectInstance(
                null, 0x1FAC, 0x0350, false);
        Sonic1ResultsScreenObjectInstance existingResults =
                new Sonic1ResultsScreenObjectInstance(60, 66, 2);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getActiveObjects()).thenReturn(List.of(existingResults));
        flash.setServices(new StubObjectServices() {
            @Override
            public GameStateManager gameState() {
                return mock(GameStateManager.class);
            }

            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });

        for (int frame = 0; frame < 17; frame++) {
            flash.update(frame, mock(AbstractPlayableSprite.class));
        }

        verify(objectManager, never()).addDynamicObject(any());
        Field specialStageAfter = Sonic1ResultsScreenObjectInstance.class
                .getDeclaredField("specialStageAfter");
        specialStageAfter.setAccessible(true);
        assertTrue(specialStageAfter.getBoolean(existingResults));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setState(Sonic1GiantRingObjectInstance ring, String value) throws Exception {
        Field state = Sonic1GiantRingObjectInstance.class.getDeclaredField("state");
        state.setAccessible(true);
        Class<? extends Enum> type = (Class<? extends Enum>) state.getType();
        state.set(ring, Enum.valueOf(type, value));
    }
}
