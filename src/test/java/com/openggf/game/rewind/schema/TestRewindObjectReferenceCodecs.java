package com.openggf.game.rewind.schema;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindObjectReferenceCodecs {
    @AfterEach
    void clearRegistry() {
        RewindSchemaRegistry.clearForTest();
    }

    @Test
    void restoresDirectObjectReferenceThroughIdentityTable() {
        ObjectInstance oldObject = object(1);
        ObjectReferenceFixture fixture = new ObjectReferenceFixture(oldObject);

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(
                fixture,
                context(oldObject, ObjectRefId.dynamic(3, 4, 101)));

        ObjectInstance newObject = object(2);
        fixture.object = null;
        CompactFieldCapturer.restore(
                fixture,
                blob,
                context(newObject, ObjectRefId.dynamic(3, 4, 101)));

        assertSame(newObject, fixture.object);
    }

    @Test
    void restoresObjectReferenceCollectionsAndMapsThroughIdentityTable() {
        ObjectInstance oldFirst = object(1);
        ObjectInstance oldSecond = object(2);
        ObjectCollectionFixture fixture = new ObjectCollectionFixture(oldFirst, oldSecond);

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(
                fixture,
                context(
                        oldFirst, ObjectRefId.layout(5, 1, 20),
                        oldSecond, ObjectRefId.child(6, 2, 21, 102)));

        ObjectInstance newFirst = object(11);
        ObjectInstance newSecond = object(12);
        fixture.objects.clear();
        fixture.states.clear();
        CompactFieldCapturer.restore(
                fixture,
                blob,
                context(
                        newFirst, ObjectRefId.layout(5, 1, 20),
                        newSecond, ObjectRefId.child(6, 2, 21, 102)));

        assertEquals(Arrays.asList(newFirst, null, newSecond), fixture.objects);
        assertEquals(List.of(newFirst, newSecond), new ArrayList<>(fixture.states.keySet()));
        assertEquals(7, fixture.states.get(newFirst));
        assertEquals(9, fixture.states.get(newSecond));
    }

    @Test
    void restoresObjectReferenceArraysThroughIdentityTable() {
        ObjectInstance oldFirst = object(1);
        ObjectInstance oldSecond = object(2);
        ObjectArrayFixture fixture = new ObjectArrayFixture(oldFirst, oldSecond);

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(
                fixture,
                context(
                        oldFirst, ObjectRefId.layout(4, 1, 30),
                        oldSecond, ObjectRefId.child(7, 3, 31, 103)));

        ObjectInstance newFirst = object(11);
        ObjectInstance newSecond = object(12);
        fixture.objects = new ObjectInstance[] {null, null, null};
        CompactFieldCapturer.restore(
                fixture,
                blob,
                context(
                        newFirst, ObjectRefId.layout(4, 1, 30),
                        newSecond, ObjectRefId.child(7, 3, 31, 103)));

        assertArrayEquals(new ObjectInstance[] {newFirst, null, newSecond}, fixture.objects);
    }

    @Test
    void objectReferenceCaptureRequiresIdentityContext() {
        ObjectReferenceFixture fixture = new ObjectReferenceFixture(object(1));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(fixture));

        assertTrue(failure.getMessage().contains("RewindIdentityTable"));
    }

    private static RewindCaptureContext context(ObjectInstance first, ObjectRefId firstId) {
        RewindIdentityTable table = new RewindIdentityTable();
        table.registerObject(first, firstId);
        return RewindCaptureContext.withIdentityTable(table);
    }

    private static RewindCaptureContext context(
            ObjectInstance first,
            ObjectRefId firstId,
            ObjectInstance second,
            ObjectRefId secondId) {

        RewindIdentityTable table = new RewindIdentityTable();
        table.registerObject(first, firstId);
        table.registerObject(second, secondId);
        return RewindCaptureContext.withIdentityTable(table);
    }

    private static ObjectInstance object(int spawnId) {
        return new ObjectInstance() {
            @Override
            public ObjectSpawn getSpawn() {
                return new ObjectSpawn(0, 0, spawnId, 0, 0, false, spawnId);
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
        };
    }

    private static final class ObjectReferenceFixture {
        ObjectInstance object;

        private ObjectReferenceFixture(ObjectInstance object) {
            this.object = object;
        }
    }

    private static final class ObjectArrayFixture {
        ObjectInstance[] objects;

        private ObjectArrayFixture(ObjectInstance first, ObjectInstance second) {
            objects = new ObjectInstance[] {first, null, second};
        }
    }

    private static final class ObjectCollectionFixture {
        List<ObjectInstance> objects = new ArrayList<>();
        Map<ObjectInstance, Integer> states = new LinkedHashMap<>();

        private ObjectCollectionFixture(ObjectInstance first, ObjectInstance second) {
            objects.add(first);
            objects.add(null);
            objects.add(second);
            states.put(first, 7);
            states.put(second, 9);
        }
    }
}
