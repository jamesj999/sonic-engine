package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameModule;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestSonic1EggPrisonObjectInstance {

    @Test
    void capsuleBalanceUsesNativeWidthWithoutSolidPadding() {
        Sonic1EggPrisonObjectInstance prison = new Sonic1EggPrisonObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic1ObjectIds.EGG_PRISON, 0, 0, false, 0));

        assertEquals(0x2B, prison.getSolidParams().halfWidth(),
                "Pri_BodyMain adds Sonic's $B width for SolidObject");
        assertEquals(0x20, prison.getBalanceWidthPixels(),
                "Sonic_Move reads Obj3E's unpadded obActWid");
    }

    @Test
    void endActChecksForAnimalsImmediatelyWithoutPrototypeDelay() throws Exception {
        Sonic1EggPrisonObjectInstance prison = new Sonic1EggPrisonObjectInstance(
                new ObjectSpawn(0x100, 0x100, Sonic1ObjectIds.EGG_PRISON, 0, 0, false, 0));
        GameModule module = mock(GameModule.class);
        ObjectManager[] objectManager = new ObjectManager[1];
        ObjectServices services = new StubObjectServices() {
            @Override
            public GameModule gameModule() {
                return module;
            }

            @Override
            public ObjectManager objectManager() {
                return objectManager[0];
            }
        };
        objectManager[0] = new ObjectManager(
                List.of(), null, 0, null, null, null, new Camera(), services);
        prison.setServices(services);

        Field state = Sonic1EggPrisonObjectInstance.class.getDeclaredField("state");
        state.setAccessible(true);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object endAct = Enum.valueOf((Class<? extends Enum>) state.getType(), "END_ACT");
        state.set(prison, endAct);
        Field timer = Sonic1EggPrisonObjectInstance.class.getDeclaredField("timer");
        timer.setAccessible(true);
        timer.setInt(prison, 180);

        prison.update(1, new TestablePlayableSprite("sonic", (short) 0, (short) 0));

        Field resultsTriggered = Sonic1EggPrisonObjectInstance.class.getDeclaredField("resultsTriggered");
        resultsTriggered.setAccessible(true);
        assertEquals(true, resultsTriggered.getBoolean(prison),
                "Released S1 Pri_EndAct scans immediately; its 180-frame prototype timer is unused");
    }

    @Test
    void releasedEndActBugScansOnlyNativeSlotsOneThroughSixtyThree() {
        assertTrue(Sonic1EggPrisonObjectInstance.releasedEndActScansSlot(32));
        assertTrue(Sonic1EggPrisonObjectInstance.releasedEndActScansSlot(63));
        assertFalse(Sonic1EggPrisonObjectInstance.releasedEndActScansSlot(64));
        assertFalse(Sonic1EggPrisonObjectInstance.releasedEndActScansSlot(127));
    }
}
