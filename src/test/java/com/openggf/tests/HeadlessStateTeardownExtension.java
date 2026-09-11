package com.openggf.tests;

import com.openggf.audio.driver.SmpsDriverTestAccess;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resets the shared engine baseline after every top-level test class so a
 * headless session, loaded level, or configuration write left behind by one
 * class never reaches the next class in a reused Surefire fork.
 * <p>
 * Every existing reset mechanism runs <em>before</em> a test:
 * {@link SingletonResetExtension}, {@code @FullReset} and {@code @RequiresRom}
 * all rebuild state in {@code beforeEach}/{@code beforeAll}, so the last test
 * of a class leaves whatever it built — a {@link HeadlessTestFixture},
 * a {@link SharedLevel}, a {@code SessionManager} gameplay session, or a
 * {@code SonicConfigurationService} value — registered in the static
 * singletons. A later class that assumes open air (terrain probes read
 * {@code GameServices.levelOrNull()}) or default configuration then fails
 * depending only on filesystem run order. Several such polluters were fixed
 * one at a time with an explicit {@code @AfterEach TestEnvironment.resetAll()};
 * this extension is the systemic form of that fix.
 * <p>
 * It is registered globally through
 * {@code META-INF/services/org.junit.jupiter.api.extension.Extension} with
 * {@code junit.jupiter.extensions.autodetection.enabled=true} in
 * {@code junit-platform.properties}, so no test class has to declare it.
 * {@code TestHeadlessStateTeardownGuard} keeps that wiring in place and
 * {@code TestHeadlessStateTeardownExtension} proves it is active at runtime.
 * <p>
 * Only top-level classes are torn down. A {@code @Nested} class shares its
 * enclosing class's {@code @BeforeAll} fixture, and resetting after the nested
 * class would pull a shared level out from under the outer class's remaining
 * tests. Classes that dispose a shared fixture in {@code @AfterAll} are
 * unaffected: their own callback runs first, and a second
 * {@link TestEnvironment#resetAll()} on a clean baseline is idempotent.
 */
public final class HeadlessStateTeardownExtension
        implements BeforeAllCallback, AfterAllCallback {

    private static final Set<Class<?>> OBSERVED_CLASSES =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getTestClass().ifPresent(OBSERVED_CLASSES::add);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Class<?> testClass = context.getTestClass().orElse(null);
        if (testClass == null || testClass.getEnclosingClass() != null) {
            return;
        }
        try {
            TestEnvironment.resetAll();
        } finally {
            SmpsDriverTestAccess.closeAll();
        }
    }

    /**
     * Whether this extension's {@code beforeAll} ran for {@code testClass} in
     * the current JVM; true only when the global registration is active.
     */
    public static boolean observed(Class<?> testClass) {
        return OBSERVED_CLASSES.contains(testClass);
    }
}
