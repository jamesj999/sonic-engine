package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.LevelManager;
import com.openggf.physics.Direction;
import org.mockito.ArgumentCaptor;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.DrowningController;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestBreathingBubbleOwnership {
    @Test
    void controllersSpawnAtTheirOwnPlayersMouths() {
        LevelManager level = mock(LevelManager.class);
        ObjectManager objects = mock(ObjectManager.class);
        ObjectRenderManager art = mock(ObjectRenderManager.class);
        when(level.getObjectManager()).thenReturn(objects);
        when(level.getObjectRenderManager()).thenReturn(art);
        when(art.getRenderer(ObjectArtKeys.BUBBLES)).thenReturn(mock(PatternSpriteRenderer.class));
        AbstractPlayableSprite sonic = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite tails = mock(AbstractPlayableSprite.class);
        when(sonic.currentLevelManagerIfAvailable()).thenReturn(level);
        when(tails.currentLevelManagerIfAvailable()).thenReturn(level);
        when(sonic.getCentreX()).thenReturn((short) 80);
        when(sonic.getCentreY()).thenReturn((short) 140);
        when(tails.getCentreX()).thenReturn((short) 220);
        when(tails.getCentreY()).thenReturn((short) 180);
        when(sonic.getDirection()).thenReturn(Direction.RIGHT);
        when(tails.getDirection()).thenReturn(Direction.LEFT);
        new DrowningController(sonic).spawnFixedCountdownBubble(4);
        new DrowningController(tails).spawnFixedCountdownBubble(2);
        ArgumentCaptor<ObjectInstance> bubbles = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objects, times(2)).addDynamicObjectNextFrame(bubbles.capture());
        assertEquals(86, bubbles.getAllValues().get(0).getX());
        assertEquals(140, bubbles.getAllValues().get(0).getY());
        assertEquals(214, bubbles.getAllValues().get(1).getX());
        assertEquals(180, bubbles.getAllValues().get(1).getY());
    }

    @Test
    void separatedPlayersKeepSeparateNumbersAndRecoverIndependently() {
        Camera camera = mock(Camera.class);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(ObjectArtKeys.BUBBLES)).thenReturn(renderer);
        StubObjectServices services = new StubObjectServices() {
            @Override public Camera camera() { return camera; }
            @Override public ObjectRenderManager renderManager() { return renderManager; }
        };
        AbstractPlayableSprite sonic = mock(AbstractPlayableSprite.class);
        AbstractPlayableSprite tails = mock(AbstractPlayableSprite.class);
        DrowningController sonicAir = mock(DrowningController.class);
        DrowningController tailsAir = mock(DrowningController.class);
        when(sonic.getDrowningController()).thenReturn(sonicAir);
        when(tails.getDrowningController()).thenReturn(tailsAir);
        when(sonicAir.getRemainingAir()).thenReturn(9);
        when(tailsAir.getRemainingAir()).thenReturn(5);
        BreathingBubbleInstance sonicNumber = bubble(80, 140, 4, sonic, services);
        BreathingBubbleInstance tailsNumber = bubble(220, 180, 2, tails, services);
        for (int i = 0; i < 15; i++) {
            // The object dispatcher supplies P1 even when the bubble belongs to P2.
            sonicNumber.update(i, sonic);
            tailsNumber.update(i, sonic);
        }
        int sonicX = sonicNumber.getX();
        int sonicY = sonicNumber.getY();
        int tailsX = tailsNumber.getX();
        int tailsY = tailsNumber.getY();
        assertEquals(140, tailsX - sonicX);
        assertEquals(40, tailsY - sonicY);

        // Camera movement after RunObjects must not drag screen-space digits.
        when(camera.getX()).thenReturn((short) 24);
        when(camera.getY()).thenReturn((short) 12);
        sonicNumber.appendRenderCommands(new ArrayList<>());
        tailsNumber.appendRenderCommands(new ArrayList<>());
        verify(renderer).drawFrameIndex(12, sonicX + 24, sonicY + 12, false, false);
        verify(renderer).drawFrameIndex(10, tailsX + 24, tailsY + 12, false, false);

        when(sonicAir.getRemainingAir()).thenReturn(30);
        sonicNumber.update(16, sonic);
        tailsNumber.update(16, sonic);
        assertTrue(sonicNumber.isDestroyed());
        assertFalse(tailsNumber.isDestroyed(), "Sonic recovering air must not remove Tails' number");
        when(tailsAir.getRemainingAir()).thenReturn(30);
        tailsNumber.update(17, sonic);
        assertTrue(tailsNumber.isDestroyed());
    }

    private static BreathingBubbleInstance bubble(int x, int y, int number,
            AbstractPlayableSprite owner, ObjectServices services) {
        BreathingBubbleInstance bubble = new BreathingBubbleInstance(x, y, false, number,
                ObjectArtKeys.BUBBLES, new int[] {8, 9, 10, 11, 12, 13}, 3, -0x88, false, owner);
        bubble.setServices(services);
        return bubble;
    }
}
