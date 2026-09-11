package com.openggf.tests.rules;

import com.openggf.data.RomManager;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a test class needs a real ROM loaded.
 * The Jupiter extension attached to this annotation loads the ROM, detects the
 * game module, and configures {@link RomManager} before each test.
 * If the ROM is unavailable, tests are gracefully skipped.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@ExtendWith(RequiresRomCondition.class)
public @interface RequiresRom {
    SonicGame value();
}


