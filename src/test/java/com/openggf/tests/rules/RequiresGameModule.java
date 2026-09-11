package com.openggf.tests.rules;

import com.openggf.game.GameModule;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a test class needs a game module configured but not a real ROM.
 * Useful for tests that use a MockRom or don't need ROM data at all.
 * The attached Jupiter extension configures the requested module before each
 * test method.
 * <p>
 * This is mutually exclusive with {@link RequiresRom}; tests should use one or
 * the other, never both.
 * <p>
 * Legacy fixtures used the old ROM rule to set the appropriate
 * {@link GameModule}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(RequiresGameModuleCondition.class)
public @interface RequiresGameModule {
    SonicGame value();
}


