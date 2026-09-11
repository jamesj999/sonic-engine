package com.openggf.trace.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Regression guard: {@link TraceCatalog#scan} must bound its walk so a
 * misconfigured {@code TRACE_CATALOG_DIR} (resolving to the project root
 * or some other deep tree) does not freeze the master title screen.
 *
 * <p>Before the depth-2 bound, scanning the project root walked
 * thousands of files and took multiple minutes; the {@code @Timeout}
 * is the test's real assertion.
 */
class TraceCatalogHangTest {

    @Test
    @Timeout(value = 3, unit = TimeUnit.SECONDS)
    void scanReturnsQuicklyOnLargeUnrelatedRoot() {
        // @Timeout asserts the real invariant. No content assertions —
        // the caller's dir may or may not contain traces; the guarantee
        // is that scan returns promptly regardless of tree size.
        Path root = Path.of(System.getProperty("user.dir"));
        TraceCatalog.scan(root);
    }
}
