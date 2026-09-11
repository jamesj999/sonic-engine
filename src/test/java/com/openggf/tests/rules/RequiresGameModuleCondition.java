package com.openggf.tests.rules;

import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Jupiter extension backing {@link RequiresGameModule}.
 */
public class RequiresGameModuleCondition implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        RequiresGameModule requiresModule = testClass.getAnnotation(RequiresGameModule.class);
        if (requiresModule == null) {
            return;
        }
        if (testClass.isAnnotationPresent(RequiresRom.class)) {
            throw new IllegalStateException(
                    "@RequiresRom and @RequiresGameModule are mutually exclusive on " + testClass.getName());
        }

        TestEnvironment.configureGameModuleFixture(requiresModule.value());
    }
}


