package com.openggf.tests.rules;

import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRequiresRom {

    @AfterEach
    void tearDown() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        RomManager.getInstance().setRom(null);
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void enablesClassesWithoutRequiresRomAnnotation() {
        RequiresRomCondition condition = new RequiresRomCondition();

        ConditionEvaluationResult result = condition.evaluateExecutionCondition(extensionContextFor(NoAnnotationTest.class));

        assertFalse(result.isDisabled());
    }

    @Test
    void configuresRequestedRomAcrossRegistrySessionAndRuntime() {
        TestTarget target = selectAvailableTarget();
        Assumptions.assumeTrue(target != null, "No ROM available for @RequiresRom test");

        RequiresRomCondition condition = new RequiresRomCondition();
        condition.beforeEach(extensionContextFor(target.testClass()));

        assertEquals(target.expectedModuleId(), GameModuleRegistry.getCurrent().getGameId().code());
        assertEquals(target.expectedModuleId(), SessionManager.requireCurrentGameModule().getGameId().code());
        assertEquals(target.expectedModuleId(),
                TestEnvironment.activeGameplayMode().getWorldSession().getGameModule().getGameId().code());
        assertSame(target.rom(), com.openggf.tests.TestEnvironment.currentRom());
    }

    @Test
    void requiresRomAnnotationIsInheritedByTraceReplaySubclasses() {
        RequiresRom inherited = InheritedRequiresRomTest.class.getAnnotation(RequiresRom.class);

        assertEquals(SonicGame.SONIC_2, inherited.value(),
                "Concrete trace subclasses must inherit the ROM gate from abstract trace bases");
    }

    private TestTarget selectAvailableTarget() {
        for (SonicGame game : SonicGame.values()) {
            Rom rom = RomCache.getRom(game);
            if (rom != null) {
                String moduleId = switch (game) {
                    case SONIC_1 -> "s1";
                    case SONIC_2 -> "s2";
                    case SONIC_3K -> "s3k";
                };
                return new TestTarget(testClassFor(game), moduleId, rom);
            }
        }
        return null;
    }

    private Class<?> testClassFor(SonicGame game) {
        return switch (game) {
            case SONIC_1 -> RequiresS1RomTest.class;
            case SONIC_2 -> RequiresS2RomTest.class;
            case SONIC_3K -> RequiresS3kRomTest.class;
        };
    }

    private ExtensionContext extensionContextFor(Class<?> testClass) {
        Map<ExtensionContext.Namespace, Map<Object, Object>> stores = new HashMap<>();
        return (ExtensionContext) Proxy.newProxyInstance(
                ExtensionContext.class.getClassLoader(),
                new Class[]{ExtensionContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequiredTestClass" -> testClass;
                    case "getStore" -> storeFor(stores, (ExtensionContext.Namespace) args[0]);
                    case "getParent" -> Optional.empty();
                    case "getRoot" -> proxy;
                    case "getUniqueId" -> "requires-rom";
                    case "getDisplayName" -> testClass.getSimpleName();
                    case "getTags" -> java.util.Collections.emptySet();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private ExtensionContext.Store storeFor(Map<ExtensionContext.Namespace, Map<Object, Object>> stores,
                                            ExtensionContext.Namespace namespace) {
        Map<Object, Object> values = stores.computeIfAbsent(namespace, ignored -> new HashMap<>());
        return (ExtensionContext.Store) Proxy.newProxyInstance(
                ExtensionContext.Store.class.getClassLoader(),
                new Class[]{ExtensionContext.Store.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> {
                        Object value = values.get(args[0]);
                        if (args.length == 2 && value != null) {
                            yield ((Class<?>) args[1]).cast(value);
                        }
                        yield value;
                    }
                    case "put" -> values.put(args[0], args[1]);
                    case "remove" -> {
                        Object removed = values.remove(args[0]);
                        if (args.length == 2 && removed != null) {
                            yield ((Class<?>) args[1]).cast(removed);
                        }
                        yield removed;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class NoAnnotationTest {
    }

    @RequiresRom(SonicGame.SONIC_1)
    private static final class RequiresS1RomTest {
    }

    @RequiresRom(SonicGame.SONIC_2)
    private static final class RequiresS2RomTest {
    }

    @RequiresRom(SonicGame.SONIC_3K)
    private static final class RequiresS3kRomTest {
    }

    @RequiresRom(SonicGame.SONIC_2)
    private abstract static class AbstractRequiresRomTest {
    }

    private static final class InheritedRequiresRomTest extends AbstractRequiresRomTest {
    }

    private record TestTarget(Class<?> testClass, String expectedModuleId, Rom rom) {
    }
}


