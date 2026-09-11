package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TouchResponseProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestObjectManagerProviderIndexes {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void solidProviderIndexTracksAddRemoveCyclesInSlotOrder() {
        ObjectManager manager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        SolidProbeObject first = new SolidProbeObject(0x100, 0x100);
        PlainProbeObject plain = new PlainProbeObject(0x110, 0x100);
        SolidProbeObject second = new SolidProbeObject(0x120, 0x100);

        manager.addDynamicObjectAtSlot(second, 42);
        manager.addDynamicObjectAtSlot(plain, 41);
        manager.addDynamicObjectAtSlot(first, 40);

        assertEquals(List.of(first, second), providerObjects(manager, "getSolidProviderObjects"));

        manager.removeDynamicObject(first);
        assertEquals(List.of(second), providerObjects(manager, "getSolidProviderObjects"));

        SolidProbeObject replacement = new SolidProbeObject(0x0F0, 0x100);
        manager.addDynamicObjectAtSlot(replacement, 39);
        assertEquals(List.of(replacement, second), providerObjects(manager, "getSolidProviderObjects"));
    }

    @Test
    void touchProviderIndexTracksAddRemoveCyclesInSlotOrder() {
        ObjectManager manager = new ObjectManager(List.of(), new NoOpObjectRegistry(), 0, null, null);
        TouchProbeObject first = new TouchProbeObject(0x100, 0x100);
        PlainProbeObject plain = new PlainProbeObject(0x110, 0x100);
        TouchProbeObject second = new TouchProbeObject(0x120, 0x100);

        manager.addDynamicObjectAtSlot(second, 45);
        manager.addDynamicObjectAtSlot(plain, 44);
        manager.addDynamicObjectAtSlot(first, 43);

        assertEquals(List.of(first, second), providerObjects(manager, "getTouchResponseObjects"));

        manager.removeDynamicObject(second);
        assertEquals(List.of(first), providerObjects(manager, "getTouchResponseObjects"));

        TouchProbeObject replacement = new TouchProbeObject(0x130, 0x100);
        manager.addDynamicObjectAtSlot(replacement, 46);
        assertEquals(List.of(first, replacement), providerObjects(manager, "getTouchResponseObjects"));
    }

    @SuppressWarnings("unchecked")
    private static List<ObjectInstance> providerObjects(ObjectManager manager, String methodName) {
        try {
            Method method = ObjectManager.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            return (List<ObjectInstance>) method.invoke(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed reading provider index via " + methodName, e);
        }
    }

    private static final class NoOpObjectRegistry implements ObjectRegistry {
        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return null;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "Test";
        }
    }

    private abstract static class BaseProbeObject extends AbstractObjectInstance {
        private BaseProbeObject(ObjectSpawn spawn, String name) {
            super(spawn, name);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
        }
    }

    private static final class PlainProbeObject extends BaseProbeObject {
        private PlainProbeObject(int x, int y) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "PlainProbe");
        }
    }

    private static final class SolidProbeObject extends BaseProbeObject implements SolidObjectProvider {
        private final SolidObjectParams params = new SolidObjectParams(16, 8, 8);

        private SolidProbeObject(int x, int y) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "SolidProbe");
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return params;
        }
    }

    private static final class TouchProbeObject extends BaseProbeObject implements TouchResponseProvider {
        private TouchProbeObject(int x, int y) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "TouchProbe");
        }

        @Override
        public int getCollisionFlags() {
            return 0xC0 | 0x01;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }
    }
}
