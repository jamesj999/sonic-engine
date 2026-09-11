package com.openggf.level.objects;

import com.openggf.game.GameServices;
import com.openggf.game.session.EngineServices;
import com.openggf.game.solid.InertSolidExecutionRegistry;
import com.openggf.game.solid.ObjectSolidExecutionContext;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestObjectServicesRuntimeDefaults {

    @Test
    void stubObjectServicesExposeInertSolidExecutionRegistryWithoutActiveRuntime() {
        StubObjectServices services = new StubObjectServices();

        SolidExecutionRegistry registry = services.solidExecutionRegistry();
        ObjectSolidExecutionContext execution = services.solidExecution();

        assertInstanceOf(InertSolidExecutionRegistry.class, registry);
        assertSame(registry.currentObject(), execution);
        assertTrue(execution.isInert());
    }

    @Test
    void stubObjectServicesRequiresExplicitPlayerQueryStub() {
        StubObjectServices services = new StubObjectServices();

        assertThrows(UnsupportedOperationException.class, services::playerQuery);
    }

    @Test
    void runtimeBackedObjectServicesExposeRuntimeOwnedSolidExecutionRegistry() {
        TestEnvironment.resetAll();
        DefaultObjectServices services = new DefaultObjectServices(
                TestEnvironment.activeGameplayMode(), EngineServices.current());
        SolidExecutionRegistry registry = services.solidExecutionRegistry();

        assertSame(GameServices.solidExecutionRegistry(), registry);
        assertSame(registry.currentObject(), services.solidExecution());
    }
}
