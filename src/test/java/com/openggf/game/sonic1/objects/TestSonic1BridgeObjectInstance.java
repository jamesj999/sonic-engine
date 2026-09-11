package com.openggf.game.sonic1.objects;

import com.openggf.data.RomByteReader;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSonic1BridgeObjectInstance {

    @Test
    void queryProvidedSidekickCanLatchBridgeBendingWhenRawSidekickListIsEmpty() throws Exception {
        TestPlayableSprite main = new TestPlayableSprite();
        TestPlayableSprite sidekick = new TestPlayableSprite();
        sidekick.setCpuControlled(true);
        sidekick.setCentreX((short) 0x0100);

        TestableSonic1BridgeObjectInstance bridge = new TestableSonic1BridgeObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, 0x11, 0x08, 0, false, 0));
        bridge.setCheckpointBatch(new SolidCheckpointBatch(bridge, Map.of(
                main, noContact(),
                sidekick, standingContact()
        )));
        bridge.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick)));

        bridge.update(1, main);

        assertTrue((boolean) fieldValue(bridge, "playerOnBridge"),
                "S1 Bridge should consume ObjectPlayerQuery participants for sidekick standing state");
        assertEquals(4, fieldValue(bridge, "playerLogIndex"));
    }

    @Test
    void queryProvidedSidekickCanRefreshBridgeBendingWhileAlreadyRiding() throws Exception {
        TestPlayableSprite main = new TestPlayableSprite();
        TestPlayableSprite sidekick = new TestPlayableSprite();
        sidekick.setCpuControlled(true);
        sidekick.setCentreX((short) 0x0100);

        TestableSonic1BridgeObjectInstance bridge = new TestableSonic1BridgeObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, 0x11, 0x08, 0, false, 0));
        ObjectManager objectManager = mock(ObjectManager.class);
        when(objectManager.getRidingObject(sidekick)).thenReturn(bridge);
        bridge.setServices(new QueryOnlyPlayerServices(main, List.of(sidekick), objectManager));

        bridge.setCheckpointBatch(new SolidCheckpointBatch(bridge, Map.of(
                main, noContact(),
                sidekick, standingContact()
        )));
        bridge.update(1, main);
        assertEquals(4, fieldValue(bridge, "playerLogIndex"));

        sidekick.setCentreX((short) 0x0120);
        bridge.setCheckpointBatch(new SolidCheckpointBatch(bridge, Map.of(
                main, noContact(),
                sidekick, noContact()
        )));

        bridge.update(2, main);

        assertEquals(6, fieldValue(bridge, "playerLogIndex"),
                "Already-riding query-only sidekick should drive Bri_WalkOff-equivalent log tracking");
    }

    @Test
    void freshAirborneFlushCatchDefersCheckpointUntilNextObjectPass() throws Exception {
        TestPlayableSprite main = new TestPlayableSprite();
        main.setAir(true);
        main.setYSpeed((short) 0x0100);
        main.setCentreX((short) 0x0100);
        // Platform3 d0 = (obY - 8) - (SonicY + obHeight + 4) == 0.
        main.setCentreY((short) (0x0100 - 8 - main.getYRadius() - 4));

        TestableSonic1BridgeObjectInstance bridge = new TestableSonic1BridgeObjectInstance(
                new ObjectSpawn(0x0100, 0x0100, 0x11, 0x08, 0, false, 0));
        bridge.setCheckpointBatch(new SolidCheckpointBatch(bridge, Map.of(
                main, standingContact()
        )));
        bridge.setServices(new QueryOnlyPlayerServices(main, List.of()));

        bridge.update(1, main);

        assertEquals(0, bridge.checkpointRequests,
                "S1 Obj11 fresh airborne catch should stage Platform3 this pass, not apply it");
        assertTrue((boolean) fieldValue(bridge, "landingDeferred"));
        assertFalse((boolean) fieldValue(bridge, "playerOnBridge"));

        bridge.update(2, main);

        assertEquals(1, bridge.checkpointRequests);
        assertFalse((boolean) fieldValue(bridge, "landingDeferred"));
        assertTrue((boolean) fieldValue(bridge, "playerOnBridge"));
    }

    @Test
    void bendDataLoaderReadsYMaxAndAlignTablesFromRomOffsets() {
        byte[] rom = new byte[Sonic1Constants.BRIDGE_BEND_ALIGN_ADDR + 0x100];
        rom[Sonic1Constants.BRIDGE_BEND_Y_MAX_ADDR + (16 * 16) + 15] = 0x22;
        rom[Sonic1Constants.BRIDGE_BEND_ALIGN_ADDR + (15 * 16) + 15] = (byte) 0xFE;

        Sonic1BridgeObjectInstance.BridgeBendData data =
                Sonic1BridgeObjectInstance.loadBendData(new RomByteReader(rom));

        assertEquals(0x22, data.yMax(16, 15));
        assertEquals(0xFE, data.align(15, 15));
    }

    private static Object fieldValue(Sonic1BridgeObjectInstance bridge, String name) throws Exception {
        Field field = Sonic1BridgeObjectInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(bridge);
    }

    private static PlayerSolidContactResult noContact() {
        return new PlayerSolidContactResult(ContactKind.NONE, false, false, false, false,
                null, null, 0);
    }

    private static PlayerSolidContactResult standingContact() {
        return new PlayerSolidContactResult(ContactKind.TOP, true, false, false, false,
                null, null, 0);
    }

    private static final class TestableSonic1BridgeObjectInstance extends Sonic1BridgeObjectInstance {
        private SolidCheckpointBatch checkpointBatch;
        private int checkpointRequests;

        private TestableSonic1BridgeObjectInstance(ObjectSpawn spawn) {
            super(spawn, testBendData());
        }

        private void setCheckpointBatch(SolidCheckpointBatch checkpointBatch) {
            this.checkpointBatch = checkpointBatch;
        }

        @Override
        protected SolidCheckpointBatch checkpointAll() {
            checkpointRequests++;
            return checkpointBatch;
        }
    }

    private static Sonic1BridgeObjectInstance.BridgeBendData testBendData() {
        byte[] yMax = new byte[17 * 16];
        byte[] align = new byte[16 * 16];
        Arrays.fill(align, (byte) 0xFF);
        return new Sonic1BridgeObjectInstance.BridgeBendData(yMax, align);
    }

    private static final class QueryOnlyPlayerServices extends TestObjectServices {
        private final PlayableEntity main;
        private final List<? extends PlayableEntity> queriedSidekicks;
        private final ObjectManager objectManager;

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks) {
            this(main, queriedSidekicks, null);
        }

        private QueryOnlyPlayerServices(PlayableEntity main, List<? extends PlayableEntity> queriedSidekicks,
                ObjectManager objectManager) {
            this.main = main;
            this.queriedSidekicks = queriedSidekicks;
            this.objectManager = objectManager;
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> main, () -> queriedSidekicks);
        }

        @Override
        public List<PlayableEntity> sidekicks() {
            return List.of();
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }
    }
}
