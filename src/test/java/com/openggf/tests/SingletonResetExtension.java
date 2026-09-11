package com.openggf.tests;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class SingletonResetExtension implements BeforeEachCallback {
    @Override
    public void beforeEach(ExtensionContext context) {
        boolean fullReset = context.getRequiredTestMethod()
                .isAnnotationPresent(FullReset.class)
                || context.getRequiredTestClass()
                .isAnnotationPresent(FullReset.class);
        if (fullReset) {
            TestEnvironment.resetAll();
        } else {
            TestEnvironment.resetPerTest();
        }
    }
}


