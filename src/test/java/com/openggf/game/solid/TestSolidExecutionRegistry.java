package com.openggf.game.solid;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSolidExecutionRegistry {

    @Test
    void noContactResultIsExplicitAndPromotedAcrossFrames() {
        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        PlayableEntity player = playableEntity("player");
        ObjectInstance object = new RegistryTestObject();

        registry.beginFrame(120, List.of(player));
        registry.beginObject(object, () -> new SolidCheckpointBatch(object, Map.of(
                player, new PlayerSolidContactResult(
                        ContactKind.TOP,
                        true,
                        false,
                        false,
                        false,
                        new PreContactState((short) 0x180, (short) 0x40, true, 0),
                        new PostContactState((short) 0, (short) 0, false, true, false),
                        0))));
        registry.currentObject().resolveSolidNow(player);
        registry.endObject(object);
        registry.finishFrame();

        PlayerStandingState previous = registry.previousStanding(object, player);
        assertEquals(ContactKind.TOP, previous.kind());
        assertTrue(previous.standing());

        registry.beginFrame(121, List.of(player));
        registry.beginObject(object, () -> new SolidCheckpointBatch(object, Map.of(
                player, PlayerSolidContactResult.noContact(
                        registry.previousStanding(object, player),
                        new PreContactState((short) 0x200, (short) 0, false, 0),
                        new PostContactState((short) 0x200, (short) 0, true, false, false)))));
        PlayerSolidContactResult result = registry.currentObject().resolveSolidNow(player);
        registry.endObject(object);
        registry.finishFrame();

        assertEquals(ContactKind.NONE, result.kind());
        assertTrue(result.standingLastFrame());
        assertEquals(ContactKind.NONE, registry.previousStanding(object, player).kind());
        assertFalse(registry.previousStanding(object, player).standing());
    }

    @Test
    void currentObjectContextAllowsMultipleRealCheckpointsInOneObjectExecutionWindow() {
        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        PlayableEntity player = playableEntity("player");
        ObjectInstance object = new RegistryTestObject();
        AtomicInteger resolves = new AtomicInteger();

        assertTrue(registry.currentObject().isInert());
        registry.beginFrame(1, List.of(player));
        registry.beginObject(object, () -> {
            int pass = resolves.incrementAndGet();
            return new SolidCheckpointBatch(object, Map.of(
                    player, new PlayerSolidContactResult(
                            pass == 1 ? ContactKind.TOP : ContactKind.NONE,
                            pass == 1,
                            false,
                            false,
                            false,
                            new PreContactState((short) pass, (short) 0, false, 0),
                            new PostContactState((short) 0, (short) 0, false, pass == 1, false),
                            0)));
        });

        ObjectSolidExecutionContext context = registry.currentObject();
        assertSame(object, context.object());
        assertEquals(ContactKind.TOP, context.resolveSolidNow(player).kind());
        assertEquals(ContactKind.NONE, context.resolveSolidNow(player).kind());
        assertEquals(2, resolves.get());
        assertEquals(ContactKind.NONE, context.lastCheckpoint().perPlayer().get(player).kind());
        registry.endObject(object);
        assertTrue(registry.currentObject().isInert());
    }

    @Test
    void resolveSolidNowAllPublishesLatestCheckpointAndResolveSolidNowReturnsExplicitNoContactWhenAbsent() {
        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        PlayableEntity player = playableEntity("player");
        ObjectInstance object = new RegistryTestObject();
        AtomicInteger resolves = new AtomicInteger();

        registry.beginFrame(7, List.of(player));
        registry.beginObject(object, () -> {
            int pass = resolves.incrementAndGet();
            return new SolidCheckpointBatch(object, Map.of());
        });

        ObjectSolidExecutionContext context = registry.currentObject();
        SolidCheckpointBatch first = context.resolveSolidNowAll();
        assertSame(first, context.lastCheckpoint());
        PlayerSolidContactResult missing = context.resolveSolidNow(player);

        assertNotNull(first);
        assertEquals(2, resolves.get());
        assertEquals(ContactKind.NONE, missing.kind());
        assertFalse(missing.standingNow());
        assertEquals(ContactKind.NONE, registry.currentObject().lastCheckpoint().perPlayer().getOrDefault(player, missing).kind());
    }

    @Test
    void resolveSolidNowPreservesPreviousStandingAndPushingWhenPlayerIsOmittedFromFreshBatch() {
        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        PlayableEntity player = playableEntity("player");
        ObjectInstance object = new RegistryTestObject();

        registry.beginFrame(10, List.of(player));
        registry.beginObject(object, () -> new SolidCheckpointBatch(object, Map.of(
                player, new PlayerSolidContactResult(
                        ContactKind.SIDE,
                        false,
                        false,
                        true,
                        false,
                        PreContactState.ZERO,
                        new PostContactState((short) 0, (short) 0, false, false, true),
                        0))));
        registry.currentObject().resolveSolidNow(player);
        registry.endObject(object);
        registry.finishFrame();

        registry.beginFrame(11, List.of(player));
        registry.beginObject(object, () -> new SolidCheckpointBatch(object, Map.of()));

        PlayerSolidContactResult result = registry.currentObject().resolveSolidNow(player);

        assertEquals(ContactKind.NONE, result.kind());
        assertFalse(result.standingNow());
        assertFalse(result.standingLastFrame());
        assertFalse(result.pushingNow());
        assertTrue(result.pushingLastFrame());
    }

    @Test
    void resolveSolidNowPreservesSideDisplacementForManualCheckpointConsumers() {
        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        PlayableEntity player = playableEntity("player");
        ObjectInstance object = new RegistryTestObject();

        registry.beginFrame(12, List.of(player));
        registry.beginObject(object, () -> new SolidCheckpointBatch(object, Map.of(
                player, new PlayerSolidContactResult(
                        ContactKind.SIDE,
                        false,
                        false,
                        true,
                        false,
                        new PreContactState((short) 0, (short) 0, false, 0),
                        new PostContactState((short) 0, (short) 0, false, false, true),
                        0))));

        PlayerSolidContactResult result = registry.currentObject().resolveSolidNow(player);

        assertEquals(ContactKind.SIDE, result.kind());
        assertEquals(0, result.sideDistX(),
                "Manual-checkpoint objects need the preserved Solid_ChkEnter displacement "
                        + "to distinguish real pushes from zero-displacement side contacts");
    }

    @Test
    void publishCheckpointRejectsBatchForDifferentObjectDuringActiveExecutionWindow() {
        DefaultSolidExecutionRegistry registry = new DefaultSolidExecutionRegistry();
        PlayableEntity player = playableEntity("player");
        ObjectInstance activeObject = new RegistryTestObject();
        ObjectInstance otherObject = new RegistryTestObject();

        registry.beginFrame(20, List.of(player));
        registry.beginObject(activeObject, () -> new SolidCheckpointBatch(otherObject, Map.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> registry.currentObject().resolveSolidNowAll());

        assertEquals("Published checkpoint batch must match the currently executing object.", error.getMessage());
    }

    @Test
    void checkpointBatchUsesIdentitySemanticsForPlayableEntityKeys() {
        PlayableEntity first = equalButDistinctPlayableEntity("first");
        PlayableEntity second = equalButDistinctPlayableEntity("second");
        ObjectInstance object = new RegistryTestObject();
        PlayerSolidContactResult result = PlayerSolidContactResult.noContact(
                PlayerStandingState.NONE,
                PreContactState.ZERO,
                PostContactState.ZERO);

        SolidCheckpointBatch batch = new SolidCheckpointBatch(object, Map.of(first, result));

        assertSame(result, batch.perPlayer().get(first));
        assertNotSame(first, second);
        assertFalse(batch.perPlayer().containsKey(second));
    }

    @Test
    void inertRegistryAndContextProvideNoOpFallbacks() {
        SolidExecutionRegistry registry = SolidExecutionRegistry.inert();
        PlayableEntity player = playableEntity("player");
        ObjectInstance object = new RegistryTestObject();

        registry.beginFrame(1, List.of(player));
        registry.beginObject(object, () -> new SolidCheckpointBatch(object, Map.of(
                player, PlayerSolidContactResult.noContact(
                        PlayerStandingState.NONE,
                        PreContactState.ZERO,
                        PostContactState.ZERO))));

        ObjectSolidExecutionContext context = registry.currentObject();
        assertTrue(context.isInert());
        assertTrue(ObjectSolidExecutionContext.inert().isInert());
        assertEquals(ContactKind.NONE, context.resolveSolidNow(player).kind());
        assertTrue(context.resolveSolidNowAll().perPlayer().isEmpty());
        assertTrue(context.lastCheckpoint().perPlayer().isEmpty());
        assertEquals(PlayerStandingState.NONE, registry.previousStanding(object, player));
    }

    private static final class RegistryTestObject implements ObjectInstance {
        private final ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);

        @Override
        public ObjectSpawn getSpawn() {
            return spawn;
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

    private static PlayableEntity playableEntity(String name) {
        return proxyPlayableEntity(name, false);
    }

    private static PlayableEntity equalButDistinctPlayableEntity(String name) {
        return proxyPlayableEntity(name, true);
    }

    private static PlayableEntity proxyPlayableEntity(String name, boolean equalToSameKind) {
        InvocationHandler handler = (proxy, method, args) -> {
            String methodName = method.getName();
            return switch (methodName) {
                case "toString" -> name;
                case "hashCode" -> equalToSameKind ? 1 : System.identityHashCode(proxy);
                case "equals" -> equalToSameKind
                        ? args != null && args.length == 1
                        && Proxy.isProxyClass(args[0].getClass())
                        && Proxy.getInvocationHandler(args[0]) instanceof PlayableEntityHandler other
                        && other.equalToSameKind
                        : proxy == args[0];
                default -> defaultValue(method.getReturnType());
            };
        };
        return (PlayableEntity) Proxy.newProxyInstance(
                TestSolidExecutionRegistry.class.getClassLoader(),
                new Class<?>[]{PlayableEntity.class},
                new PlayableEntityHandler(name, equalToSameKind, handler));
    }

    private static Object defaultValue(Class<?> returnType) {
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
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private record PlayableEntityHandler(
            String name,
            boolean equalToSameKind,
            InvocationHandler delegate) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            return delegate.invoke(proxy, method, args);
        }
    }
}
