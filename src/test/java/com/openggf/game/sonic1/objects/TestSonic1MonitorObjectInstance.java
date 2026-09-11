package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidRoutineKind;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSonic1MonitorObjectInstance {

    private GraphicsManager graphicsManager;

    @BeforeEach
    void setUp() {
        graphicsManager = GraphicsManager.getInstance();
        graphicsManager.initHeadless();
    }

    @AfterEach
    void tearDown() {
        graphicsManager.resetState();
    }

    @Test
    void breakingMonitorSpawnsSeparatePowerUpBeforeExplosion() {
        ObjectManager[] holder = new ObjectManager[1];
        ObjectRenderManager renderManager = buildRenderManager();
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }

            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        };

        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);

        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null, graphicsManager, camera, services);
        holder[0] = manager;

        manager.addDynamicObjectAtSlot(new BlockingObject(new ObjectSpawn(0x0200, 0x0100, 0x01, 0, 0, false, 0)), 32);

        Sonic1MonitorObjectInstance monitor =
                new Sonic1MonitorObjectInstance(new ObjectSpawn(0x0248, 0x034E, 0x26, 0x06, 0, false, 0));
        manager.addDynamicObjectAtSlot(monitor, 33);

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getRolling()).thenReturn(true);
        when(player.getAnimationId()).thenReturn(Sonic1AnimationIds.ROLL.id());
        when(player.getYSpeed()).thenReturn((short) 0x0200);
        when(player.getCentreX()).thenReturn((short) 0x0248);
        when(player.getCentreY()).thenReturn((short) 0x0330);

        // ROM ReactToItem (the touch pass) only bounces the player and queues the
        // break; the FindFreeObj spawns of the PowerUp + ExplosionItem happen in the
        // monitor's own routine 4 (Mon_BreakOpen), which the engine runs from
        // update(). docs/s1disasm/_incObj/26, 2E Monitors and Power-Ups.asm:181-198.
        monitor.onTouchResponse(player, new TouchResponseResult(0x46, 0x0E, 0x0E, TouchCategory.SPECIAL), 0);
        monitor.update(0, player);

        AbstractObjectInstance powerUp = findByObjectId(manager.getActiveObjects(), 0x2E);
        AbstractObjectInstance explosion = findByObjectId(manager.getActiveObjects(), 0x27);

        assertNotNull(powerUp, "S1 monitor break should allocate a separate PowerUp child object");
        assertNotNull(explosion, "S1 monitor break should allocate an ExplosionItem child object");
        assertEquals(34, powerUp.getSlotIndex(), "PowerUp should consume the next free SST slot after the monitor");
        assertEquals(35, explosion.getSlotIndex(), "ExplosionItem should allocate after the PowerUp child");
        assertTrue(explosion.getSlotIndex() > powerUp.getSlotIndex(),
                "ExplosionItem must allocate after PowerUp to preserve ROM child ordering");
    }

    @Test
    void exposesSonic1MonitorSolidRoutineProfile() {
        Sonic1MonitorObjectInstance monitor =
                new Sonic1MonitorObjectInstance(new ObjectSpawn(0x0248, 0x034E, 0x26, 0x06, 0, false, 0));

        SolidRoutineProfile profile = monitor.getSolidRoutineProfile();

        assertEquals(SolidRoutineKind.MONITOR_SOLID, profile.kind());
        assertTrue(profile.monitorSolidity());
        assertEquals(0, profile.monitorVerticalOffset());
        assertFalse(profile.stickyContactBuffer());
        assertTrue(profile.inclusiveRightEdge(),
                "Mon_SolidSides retains the exact BHI right edge");
    }

    private static AbstractObjectInstance findByObjectId(Collection<ObjectInstance> activeObjects, int objectId) {
        return activeObjects.stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(instance -> instance.getSpawn() != null && instance.getSpawn().objectId() == objectId)
                .findFirst()
                .orElse(null);
    }

    private static ObjectRenderManager buildRenderManager() {
        ObjectArtProvider provider = mock(ObjectArtProvider.class);
        when(provider.getRenderer(ObjectArtKeys.EXPLOSION)).thenReturn(mock(PatternSpriteRenderer.class));
        when(provider.getRendererKeys()).thenReturn(List.of());
        when(provider.isReady()).thenReturn(true);
        return new ObjectRenderManager(provider);
    }

    private static final class BlockingObject extends AbstractObjectInstance {
        private BlockingObject(ObjectSpawn spawn) {
            super(spawn, "BlockingObject");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }
}
