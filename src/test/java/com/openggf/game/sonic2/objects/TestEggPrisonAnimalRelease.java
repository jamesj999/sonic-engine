package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.EggPrisonAnimalInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for "Egg Prison opens but releases no (visible) animals".
 *
 * <p>The animals ARE spawned (the object count rises), but each
 * {@link EggPrisonAnimalInstance} captures its sprite renderer at construction via
 * {@code getRenderManager()}. The egg prison created them through raw
 * {@code new ... + addDynamicObject(...)}, which does NOT set the
 * {@code CONSTRUCTION_CONTEXT}, so after the DI migration made
 * {@code getRenderManager()} resolve through per-instance services
 * ({@code tryServices()} returns null during context-less construction) the
 * captured renderer was {@code null} and every released animal was invisible.
 *
 * <p>The fix routes the spawn through {@code spawnFreeChild(...)} (which sets the
 * construction context, exactly like every other child spawn), so the animal's
 * constructor resolves the render manager.
 */
@ExtendWith(SingletonResetExtension.class)
@FullReset
public class TestEggPrisonAnimalRelease {

    @BeforeEach
    public void setUp() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    public void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    public void releasedAnimalsCaptureAUsableRenderer() throws Exception {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0x100);
        when(camera.getY()).thenReturn((short) 0x100);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);

        // A render manager IS available at gameplay time — the animal just has to
        // be able to reach it from its constructor.
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer animalRenderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getAnimalRenderer()).thenReturn(animalRenderer);
        when(renderManager.getAnimalTypeA()).thenReturn(0);
        when(renderManager.getAnimalTypeB()).thenReturn(1);

        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public ObjectRenderManager renderManager() { return renderManager; }
        };
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        ObjectSpawn spawn = new ObjectSpawn(0x180, 0x180, 0x3E, 0, 0, false, 0);
        EggPrisonObjectInstance prison =
                manager.createDynamicObject(() -> new EggPrisonObjectInstance(spawn, "EggPrison"));
        prison.onButtonTriggered();

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getX()).thenReturn((short) -10000);
        when(player.getY()).thenReturn((short) -10000);

        EggPrisonAnimalInstance animal = null;
        for (int frame = 0; frame < 400 && animal == null; frame++) {
            manager.update(0x100, player, List.of(), frame);
            animal = firstAnimal(manager);
        }

        assertNotNull(animal, "the egg prison should spawn at least one animal");

        Field rendererField = EggPrisonAnimalInstance.class.getDeclaredField("renderer");
        rendererField.setAccessible(true);
        Object captured = rendererField.get(animal);

        assertNotNull(captured,
                "Regression: released egg-prison animals captured a null renderer at construction "
                        + "(getRenderManager() returned null because the spawn did not set the "
                        + "construction context), so every animal was invisible.");
    }

    private static EggPrisonAnimalInstance firstAnimal(ObjectManager manager) {
        for (ObjectInstance obj : manager.getActiveObjects()) {
            if (obj instanceof EggPrisonAnimalInstance animal && !animal.isDestroyed()) {
                return animal;
            }
        }
        return null;
    }
}
