package com.openggf.tests.rules;

import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.RomTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestRequiresRomConditionParity {

    @AfterEach
    void tearDown() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        RomManager.getInstance().setRom(null);
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void requiresRomExtensionMatchesSharedFixtureForNonDefaultRomRuntimeState() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());

        TestTarget target = selectAvailableNonDefaultTarget();
        Assumptions.assumeTrue(target != null, "No Sonic 1 or Sonic 3K ROM available");

        FixtureState helperState = applySharedHelper(target.rom());
        FixtureState conditionState = applyCondition(target.testClass());

        assertEquals(helperState, conditionState);
        assertEquals(target.expectedModuleId(), conditionState.registryModuleId());
        assertEquals(target.expectedModuleId(), conditionState.sessionModuleId());
        assertEquals(target.expectedModuleId(), conditionState.runtimeModuleId());
        assertSame(target.rom(), com.openggf.tests.TestEnvironment.currentRom());
    }

    private FixtureState applySharedHelper(Rom rom) {
        com.openggf.tests.TestEnvironment.configureRomFixture(rom);
        return snapshot();
    }

    private FixtureState applyCondition(Class<?> testClass) {
        RequiresRomCondition condition = new RequiresRomCondition();
        condition.beforeEach(extensionContextFor(testClass));
        return snapshot();
    }

    private FixtureState snapshot() {
        return new FixtureState(
                GameModuleRegistry.getCurrent().getGameId().code(),
                SessionManager.requireCurrentGameModule().getGameId().code(),
                TestEnvironment.activeGameplayMode().getWorldSession().getGameModule().getGameId().code());
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
                    case "getUniqueId" -> "requires-rom-condition-parity";
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

    private TestTarget selectAvailableNonDefaultTarget() {
        if (RomTestUtils.ensureSonic1RomAvailable() != null) {
            Rom rom = RomCache.getRom(SonicGame.SONIC_1);
            if (rom != null) {
                return new TestTarget(RequiresS1RomTest.class, "s1", rom);
            }
        }
        if (RomTestUtils.ensureSonic3kRomAvailable() != null) {
            Rom rom = RomCache.getRom(SonicGame.SONIC_3K);
            if (rom != null) {
                return new TestTarget(RequiresS3kRomTest.class, "s3k", rom);
            }
        }
        return null;
    }

    @RequiresRom(SonicGame.SONIC_1)
    private static final class RequiresS1RomTest {
    }

    @RequiresRom(SonicGame.SONIC_3K)
    private static final class RequiresS3kRomTest {
    }

    private record FixtureState(String registryModuleId, String sessionModuleId, String runtimeModuleId) {
    }

    private record TestTarget(Class<?> testClass, String expectedModuleId, Rom rom) {
    }
}


