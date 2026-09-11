package com.openggf.game.rewind.identity;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindIdentityTable {

    @Test
    void playerRefIdEncodesNullMainAndSidekickSlots() {
        assertEquals(0, PlayerRefId.nullRef().encoded());
        assertEquals(1, PlayerRefId.mainPlayer().encoded());
        assertEquals(2, PlayerRefId.sidekick(0).encoded());
        assertEquals(4, PlayerRefId.sidekick(2).encoded());

        assertTrue(PlayerRefId.nullRef().isNull());
        assertFalse(PlayerRefId.mainPlayer().isNull());
        assertThrows(IllegalArgumentException.class, () -> PlayerRefId.sidekick(-1));
    }

    @Test
    void objectRefIdFactoriesPreserveFieldsAndKind() {
        ObjectRefId layout = ObjectRefId.layout(3, 4, 25);
        assertEquals(3, layout.slotIndex());
        assertEquals(4, layout.generation());
        assertEquals(25, layout.spawnId());
        assertEquals(-1, layout.dynamicId());
        assertEquals(ObjectRefKind.LAYOUT, layout.kind());

        ObjectRefId dynamic = ObjectRefId.dynamic(5, 6, 101);
        assertEquals(5, dynamic.slotIndex());
        assertEquals(6, dynamic.generation());
        assertEquals(-1, dynamic.spawnId());
        assertEquals(101, dynamic.dynamicId());
        assertEquals(ObjectRefKind.DYNAMIC, dynamic.kind());

        ObjectRefId child = ObjectRefId.child(7, 8, 33, 202);
        assertEquals(7, child.slotIndex());
        assertEquals(8, child.generation());
        assertEquals(33, child.spawnId());
        assertEquals(202, child.dynamicId());
        assertEquals(ObjectRefKind.CHILD, child.kind());
    }

    @Test
    void spawnRefIdUsesObjectSpawnLayoutIndex() {
        ObjectSpawn spawn = new ObjectSpawn(0x1234, 0x5678, 0x42, 0x9, 0x2, true, 0x5678, 17);

        assertEquals(new SpawnRefId(17), SpawnRefId.fromSpawn(spawn));
    }

    @Test
    void identityTableRegistersEncodesAndResolvesPlayers() {
        RewindIdentityTable table = new RewindIdentityTable();
        PlayableEntity main = playable();
        PlayableEntity sidekick = playable();

        table.registerPlayer(main, PlayerRefId.mainPlayer());
        table.registerPlayer(sidekick, PlayerRefId.sidekick(0));

        assertEquals(PlayerRefId.mainPlayer(), table.encodePlayer(main));
        assertEquals(PlayerRefId.sidekick(0), table.encodePlayer(sidekick));
        assertEquals(PlayerRefId.nullRef(), table.encodePlayer(null));
        assertSame(main, table.resolvePlayer(PlayerRefId.mainPlayer(), true));
        assertSame(sidekick, table.resolvePlayer(PlayerRefId.sidekick(0), true));
        assertNull(table.resolvePlayer(PlayerRefId.sidekick(3), false));
        assertThrows(IllegalStateException.class, () -> table.resolvePlayer(PlayerRefId.sidekick(3), true));
    }

    @Test
    void duplicatePlayerRegistrationWithDifferentIdThrows() {
        RewindIdentityTable table = new RewindIdentityTable();
        PlayableEntity player = playable();

        table.registerPlayer(player, PlayerRefId.mainPlayer());

        assertThrows(IllegalStateException.class, () -> table.registerPlayer(player, PlayerRefId.sidekick(0)));
    }

    @Test
    void identityTableRegistersEncodesAndResolvesObjects() {
        RewindIdentityTable table = new RewindIdentityTable();
        ObjectInstance object = object();
        ObjectRefId id = ObjectRefId.layout(12, 2, 44);

        table.registerObject(object, id);

        assertEquals(id, table.encodeObject(object));
        assertNull(table.encodeObject(null));
        assertSame(object, table.resolveObject(id, true));
        assertNull(table.resolveObject(ObjectRefId.layout(99, 2, 44), false));
        assertThrows(IllegalStateException.class, () -> table.resolveObject(ObjectRefId.layout(99, 2, 44), true));
    }

    @Test
    void duplicateObjectRegistrationWithDifferentIdThrows() {
        RewindIdentityTable table = new RewindIdentityTable();
        ObjectInstance object = object();

        table.registerObject(object, ObjectRefId.dynamic(1, 1, 50));

        assertThrows(IllegalStateException.class, () -> table.registerObject(object, ObjectRefId.dynamic(1, 2, 50)));
    }

    @Test
    void generationMismatchedObjectIdDoesNotResolve() {
        RewindIdentityTable table = new RewindIdentityTable();
        ObjectInstance object = object();

        table.registerObject(object, ObjectRefId.dynamic(1, 7, 50));

        assertNull(table.resolveObject(ObjectRefId.dynamic(1, 8, 50), false));
        assertThrows(IllegalStateException.class, () -> table.resolveObject(ObjectRefId.dynamic(1, 8, 50), true));
    }

    @Test
    void identityTableRegistersEncodesAndResolvesSpawns() {
        RewindIdentityTable table = new RewindIdentityTable();
        ObjectSpawn spawn = new ObjectSpawn(0x100, 0x200, 0x10, 0x3, 0x0, true, 0x200, 9);

        table.registerSpawn(spawn);

        assertEquals(new SpawnRefId(9), table.encodeSpawn(spawn));
        assertNull(table.encodeSpawn(null));
        assertSame(spawn, table.resolveSpawn(new SpawnRefId(9), true));
        assertNull(table.resolveSpawn(new SpawnRefId(10), false));
        assertThrows(IllegalStateException.class, () -> table.resolveSpawn(new SpawnRefId(10), true));
    }

    private static PlayableEntity playable() {
        return (PlayableEntity) Proxy.newProxyInstance(
                TestRewindIdentityTable.class.getClassLoader(),
                new Class<?>[]{PlayableEntity.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static ObjectInstance object() {
        return new ObjectInstance() {
            @Override
            public ObjectSpawn getSpawn() {
                return new ObjectSpawn(0, 0, 0, 0, 0, false, 0);
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

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class) {
            return null;
        }
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        throw new IllegalArgumentException("Unsupported primitive return type: " + returnType);
    }
}
