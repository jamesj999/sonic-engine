package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.OscillationManager;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1GlassBlockObjectInstance {

    @BeforeEach
    void setUp() {
        OscillationManager.resetForSonic1();
    }

    @Test
    void movingGlassBlockResolvesCheckpointAgainstUpdatedY() throws Exception {
        ProbeGlassBlock block = new ProbeGlassBlock(new ObjectSpawn(0x0B60, 0x0630, 0x30, 0x01, 0, false, 0));
        ObjectManager manager = buildManager(block);
        TestPlayableSprite player = new TestPlayableSprite();

        setPrivateInt(block, "glassDist", 0x20);
        setPrivateInt(block, "y", 0x0610);

        int expectedUpdatedY = 0x0630;

        manager.update(0, player, List.of(), 0, false, true, false);

        assertEquals(expectedUpdatedY, block.checkpointY,
                "Glass block should run checkpoint collision against the post-Glass_Types Y");
        assertEquals(expectedUpdatedY, block.getY(),
                "Glass block update should still end at the moved Y");
    }

    @Test
    void movingGlassBlockKeepsNativeSolidObjectLatchGeometry() {
        Sonic1GlassBlockObjectInstance block = new Sonic1GlassBlockObjectInstance(
                new ObjectSpawn(0x0D60, 0x03E0, 0x30, 0x01, 0, false, 0));

        assertTrue(block.usesInstanceSolidStateLatchKey(),
                "Obj30 status belongs to the live SST while its dynamic position changes");
        assertTrue(block.usesInclusiveRightEdge(),
                "Obj30 retains SolidObject's inclusive right bound");
    }

    @Test
    void exactRightEdgeRemainsAFullSolidContact() {
        ProbeGlassBlock block = new ProbeGlassBlock(
                new ObjectSpawn(0x0D60, 0x047C, 0x30, 0x00, 0, false, 0));
        ObjectManager manager = buildManager(block);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x0D8B);
        player.setCentreY((short) 0x03EC);
        player.setAir(false);

        manager.update(0, player, List.of(), 0, false, true, false);

        assertTrue(block.checkpointBatch.perPlayer().get(player).kind() != ContactKind.NONE,
                "SolidObject's `bhi` bound includes relX == d1*2");
    }

    private static void setPrivateInt(Object instance, String fieldName, int value) throws Exception {
        Field field = instance.getClass().getSuperclass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(instance, value);
    }

    private static ObjectManager buildManager(ProbeGlassBlock block) {
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

        Camera camera = new Camera();
        camera.setX((short) 0x0CFA);
        camera.setY((short) 0x0340);

        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null, null, camera, services);
        holder[0] = manager;
        manager.addDynamicObject(block);
        return manager;
    }

    private static final class ProbeGlassBlock extends Sonic1GlassBlockObjectInstance {
        private int checkpointY = Integer.MIN_VALUE;
        private SolidCheckpointBatch checkpointBatch;

        private ProbeGlassBlock(ObjectSpawn spawn) {
            super(spawn);
        }

        @Override
        protected SolidCheckpointBatch checkpointAll() {
            checkpointY = getY();
            checkpointBatch = super.checkpointAll();
            return checkpointBatch;
        }
    }
}
