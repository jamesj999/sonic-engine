package com.openggf.game.session;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins SessionManager's concurrency contract: the hot read-only getters must stay
 * unsynchronized (they sit on the per-tile collision path via GameServices), the
 * session fields they read must stay volatile, and the lifecycle mutators (plus the
 * guarded read requireCurrentGameModule) must stay synchronized.
 */
class TestSessionManagerReadAccessors {

    private static final List<String> READ_ONLY_GETTERS = List.of(
            "getCurrentWorldSession",
            "getCurrentGameplayMode",
            "getCurrentEditorMode");

    private static final List<String> VOLATILE_FIELDS = List.of(
            "currentWorldSession",
            "currentGameplayMode",
            "currentEditorMode");

    // Lifecycle mutators plus requireCurrentGameModule, a deliberately synchronized read.
    private static final List<String> SYNCHRONIZED_METHODS = List.of(
            "openGameplaySession",
            "enterEditorMode",
            "exitEditorMode",
            "resumeGameplayFromEditor",
            "restartGameplayFromBeginning",
            "clear",
            "closeGameplaySession",
            "requireCurrentGameModule");

    @Test
    void readOnlyGettersAreNotSynchronized() {
        for (String name : READ_ONLY_GETTERS) {
            List<Method> methods = methodsNamed(name);
            assertFalse(methods.isEmpty(), "expected SessionManager method " + name);
            for (Method method : methods) {
                assertFalse(Modifier.isSynchronized(method.getModifiers()),
                        name + " must not be synchronized (hot collision-path read)");
            }
        }
    }

    @Test
    void sessionFieldsAreVolatile() throws NoSuchFieldException {
        for (String name : VOLATILE_FIELDS) {
            Field field = SessionManager.class.getDeclaredField(name);
            assertTrue(Modifier.isVolatile(field.getModifiers()),
                    name + " must be volatile so unsynchronized getters publish safely");
        }
    }

    @Test
    void synchronizedMethodsRemainSynchronized() {
        for (String name : SYNCHRONIZED_METHODS) {
            List<Method> methods = methodsNamed(name);
            assertFalse(methods.isEmpty(), "expected SessionManager method " + name);
            for (Method method : methods) {
                assertTrue(Modifier.isSynchronized(method.getModifiers()),
                        name + " must remain synchronized");
            }
        }
    }

    private static List<Method> methodsNamed(String name) {
        return Arrays.stream(SessionManager.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .toList();
    }
}
