package com.openggf.tools;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.catalog.TraceEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TraceCaptureToolArgsTest {

    @BeforeEach
    void resetConfig() {
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void parsesArgsWithConfigDefaults() {
        TraceCaptureTool.Args a = TraceCaptureTool.Args.parse(
                new String[]{"--trace", "aiz1", "--scale", "2", "--fps", "30"});
        assertEquals("aiz1", a.trace());
        assertEquals(2, a.scale());
        assertEquals(30, a.fps());
        assertEquals("ffv1", a.codec());            // default from config
        assertNotNull(a.outDir());                  // default from config
        assertTrue(a.showGhosts());                 // ghosts on by default
    }

    @Test
    void noGhostsFlagDisablesGhosts() {
        TraceCaptureTool.Args a = TraceCaptureTool.Args.parse(
                new String[]{"--trace", "aiz1", "--no-ghosts"});
        assertFalse(a.showGhosts());
    }

    @Test
    void acceptsNegativeIntegerValuesAsBefore() {
        TraceCaptureTool.Args args = TraceCaptureTool.Args.parse(
                new String[]{"--trace", "aiz1", "--scale", "-3", "--fps", "-30", "--tail-frames", "-1"});

        assertEquals(-3, args.scale());
        assertEquals(-30, args.fps());
        assertEquals(-1, args.tailFrames());
    }

    @Test
    void rejectsAMissingValueWithTheOriginalMessage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TraceCaptureTool.Args.parse(new String[]{"--trace", "aiz1", "--scale"}));

        assertEquals("Missing value for --scale", error.getMessage());
    }

    @Test
    void rejectsMalformedIntegerValuesWithNumberFormatException() {
        assertThrows(NumberFormatException.class,
                () -> TraceCaptureTool.Args.parse(new String[]{"--trace", "aiz1", "--fps", "thirty"}));
    }

    @Test
    void multiSegmentRunIsRejectedAsNotCapturable() {
        TraceRunManifest manifest = new TraceRunManifest(
                "s3k", "run_aiz_gumball", "shared.bk2", null,
                List.of(), List.of());
        assertEquals(TraceRunManifest.ExpectedMovieEndMode.UNSPECIFIED,
                manifest.expectedMovieEndMode(),
                "convenience constructor must leave terminal mode unspecified");
        TraceEntry run = new TraceEntry(
                Path.of("traces", "s3k", "runs", "run_aiz_gumball"),
                "s3k", 0, 0, 6, 500, 0, null, null, null,
                Path.of("traces", "s3k", "runs", "run_aiz_gumball"), manifest);
        assertTrue(run.isRun(), "fixture must be a run entry");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TraceCaptureTool.requireCapturable(run));
        assertTrue(ex.getMessage().contains("not capturable"),
                "rejection must explain runs are not capturable: " + ex.getMessage());
    }

    @Test
    void postBootFailureClosesSessionAndBootBeforePropagating() {
        AtomicBoolean sessionClosed = new AtomicBoolean();
        AtomicBoolean bootClosed = new AtomicBoolean();
        RuntimeException primary = new RuntimeException("post-boot failure");

        RuntimeException actual = assertThrows(RuntimeException.class, () -> {
            try (TraceCaptureTool.BootOwnership<AutoCloseable> ignored =
                    new TraceCaptureTool.BootOwnership<>(
                            () -> bootClosed.set(true),
                            () -> sessionClosed.set(true))) {
                throw primary;
            }
        });

        assertSame(primary, actual);
        assertTrue(sessionClosed.get());
        assertTrue(bootClosed.get());
    }

    @Test
    void ordinarySingleSegmentTraceIsCapturable() {
        TraceEntry level = new TraceEntry(
                Path.of("traces", "s3k", "aiz1"),
                "s3k", 0, 0, 10, 0, 0, null, null, null);
        assertFalse(level.isRun(), "fixture must be an ordinary trace");
        assertSame(level, TraceCaptureTool.requireCapturable(level),
                "an ordinary trace must pass the capturable guard unchanged");
    }
}
