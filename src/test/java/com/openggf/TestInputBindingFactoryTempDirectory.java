package com.openggf;

import com.openggf.control.InputHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every no-argument {@link InputHandler} routes through
 * {@code InputBindingFactory.standaloneSupplier()}, and there are hundreds of
 * such construction sites across the engine and its tests. When that path made
 * a fresh {@code Files.createTempDirectory} per call and never removed it, a
 * full suite run left one directory behind per call: on a machine with a
 * RAM-backed {@code /tmp} this reached 222,524 directories and filled the
 * filesystem, which then broke live recording -- ffmpeg died with ENOSPC and
 * reported only "Stream closed".
 */
class TestInputBindingFactoryTempDirectory {

    @Test
    void repeatedStandaloneHandlersShareOneTemporaryDirectory() throws IOException {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));

        long before = countSyntheticDirectories(tmp);
        for (int i = 0; i < 50; i++) {
            new InputHandler();
        }
        long after = countSyntheticDirectories(tmp);

        assertTrue(after - before <= 1,
                "50 standalone input handlers must not leave 50 directories in "
                        + tmp + " (grew by " + (after - before) + ")");
    }

    /**
     * Sharing the directory must not change what a standalone supplier
     * resolves to: each call still builds its own service reading the same
     * absent-config defaults, so the bindings are equal, not merely present.
     */
    @Test
    void standaloneBindingsStillReadTheSameDefaults() {
        assertEquals(InputBindingFactory.standaloneSupplier().get(),
                InputBindingFactory.standaloneSupplier().get());
    }

    private static long countSyntheticDirectories(Path tmp) throws IOException {
        try (var entries = Files.list(tmp)) {
            return entries.filter(path -> path.getFileName().toString()
                    .startsWith("openggf-input-handler")).count();
        }
    }
}
