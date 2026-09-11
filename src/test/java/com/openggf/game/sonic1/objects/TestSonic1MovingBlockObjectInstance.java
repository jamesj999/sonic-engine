package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.session.EngineContext;
import com.openggf.game.OscillationManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.tests.TestEnvironment;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSonic1MovingBlockObjectInstance {

    @BeforeEach
    void setUp() {
        SessionManager.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        OscillationManager.resetForSonic1();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void movingBlockResolvesCheckpointAgainstUpdatedPosition() throws Exception {
        ProbeMovingBlock block = new ProbeMovingBlock(new ObjectSpawn(0x1110, 0x0458, 0x52, 0x01, 0, false, 0));
        ObjectManager manager = buildManager(block);
        TestPlayableSprite player = new TestPlayableSprite();

        setPrivateInt(block, "x", 0x1140);

        int expectedUpdatedX = 0x1110;

        manager.update(0, player, List.of(), 0, false, true, false);

        assertEquals(expectedUpdatedX, block.checkpointX,
                "Moving block should run checkpoint collision against the post-MBlock_Move X");
        assertEquals(expectedUpdatedX, block.getX(),
                "Moving block update should still end at the moved X");
    }

    @Test
    void movingBlockCarriesPlayerOnJumpOffFrame() {
        Sonic1MovingBlockObjectInstance block =
                new Sonic1MovingBlockObjectInstance(new ObjectSpawn(0x107D, 0x0408, 0x52, 0x13, 0, false, 0));
        ObjectManager manager = buildManager(block);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x1090);
        player.setCentreY((short) (0x0408 - 9 - player.getYRadius()));
        player.setAir(false);
        player.setOnObject(false);

        manager.update(0, player, List.of(), 0, false, true, false);
        assertEquals(0x107E, block.getX(), "type 03 block should advance one pixel while ridden");
        assertEquals(0x1090, player.getCentreX() & 0xFFFF,
                "initial landing frame only establishes the riding object");
        assertEquals(block, manager.getRidingObject(player));

        manager.clearRidingObjectForJump(player);
        player.setAir(true);
        player.setOnObject(false);

        manager.update(0, player, List.of(), 0, false, true, false);

        assertEquals(0x107F, block.getX(), "block should keep sliding on the jump-off frame");
        assertEquals(0x1091, player.getCentreX() & 0xFFFF,
                "S1 Obj52 MBlock_StandOn applies MvSonicOnPtfm2 after ExitPlatform");
        assertEquals(0x0408 - 9 - player.getYRadius(), player.getCentreY() & 0xFFFF,
                "MvSonicOnPtfm2 snaps to obY - 9 - y_radius using centre-coordinate semantics");
        assertEquals(null, manager.getRidingObject(player));
        assertEquals(false, player.isOnObject());
        assertEquals(true, player.getAir());
    }

    private static void setPrivateInt(Object instance, String fieldName, int value) throws Exception {
        Field field = instance.getClass().getSuperclass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(instance, value);
    }

    private static ObjectManager buildManager(Sonic1MovingBlockObjectInstance block) {
        ObjectManager[] holder = new ObjectManager[1];
        SolidExecutionRegistry solidExecutionRegistry = new DefaultSolidExecutionRegistry();
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }

            @Override
            public SolidExecutionRegistry solidExecutionRegistry() {
                return solidExecutionRegistry;
            }
        };

        Camera camera = mock(Camera.class);
        // Place the camera near the block (origX ~0x107D) so it is in the
        // out_of_range window — in-game the camera follows the player riding it.
        // ObjectManager.update() syncs cameraBounds from this camera each frame,
        // and the width-driven out_of_range check reads those bounds.
        when(camera.getX()).thenReturn((short) 0x1000);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);

        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null, null, camera, services);
        holder[0] = manager;
        manager.addDynamicObject(block);
        return manager;
    }

    private static final class ProbeMovingBlock extends Sonic1MovingBlockObjectInstance {
        private int checkpointX = Integer.MIN_VALUE;

        private ProbeMovingBlock(ObjectSpawn spawn) {
            super(spawn);
        }

        @Override
        protected SolidCheckpointBatch checkpointAll() {
            checkpointX = getX();
            return new SolidCheckpointBatch(this, Map.of());
        }
    }
}
