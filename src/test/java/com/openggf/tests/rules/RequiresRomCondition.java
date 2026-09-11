package com.openggf.tests.rules;

import com.openggf.data.Rom;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Jupiter extension backing {@link RequiresRom}.
 * <p>
 * Reads the {@link RequiresRom} annotation on the test class, checks ROM
 * availability, and disables the test when the ROM is absent. When the ROM
 * is present it rebuilds the gameplay mode around the selected ROM before
 * each test method.
 * <p>
 * Usage:
 * <pre>
 * {@literal @}RequiresRom(SonicGame.SONIC_3K)
 * class MyJupiterTest {
 *     // ...
 * }
 * </pre>
 */
public class RequiresRomCondition implements ExecutionCondition, BeforeAllCallback, BeforeEachCallback {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(RequiresRomCondition.class);

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        RequiresRom annotation = testClass.getAnnotation(RequiresRom.class);
        if (annotation == null) {
            return ConditionEvaluationResult.enabled("No @RequiresRom annotation");
        }

        SonicGame game = annotation.value();
        Rom rom = RomCache.getRom(game);
        if (rom == null) {
            return ConditionEvaluationResult.disabled(
                    game.getDisplayName() + " ROM not available — skipping test");
        }
        return ConditionEvaluationResult.enabled(game.getDisplayName() + " ROM available");
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        configureRomFixture(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        configureRomFixture(context);
    }

    private void configureRomFixture(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        if (testClass.isAnnotationPresent(RequiresGameModule.class)) {
            throw new IllegalStateException(
                    "@RequiresRom and @RequiresGameModule are mutually exclusive on " + testClass.getName());
        }
        RequiresRom annotation = testClass.getAnnotation(RequiresRom.class);
        if (annotation == null) {
            return;
        }

        SonicGame game = annotation.value();
        Rom rom = RomCache.getRom(game);
        if (rom == null) {
            return; // Will be skipped by evaluateExecutionCondition
        }

        TestEnvironment.configureRomFixture(rom);

        // Store rom in extension store for test access
        context.getStore(NS).put("rom", rom);
    }

    /**
     * Retrieves the loaded ROM from the extension context.
     */
    public static Rom getRom(ExtensionContext context) {
        return context.getStore(NS).get("rom", Rom.class);
    }
}


