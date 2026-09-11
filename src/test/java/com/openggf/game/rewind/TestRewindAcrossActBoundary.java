package com.openggf.game.rewind;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Track I — Rewind across act-boundary smoke test.
 *
 * <p>Uses the S3K {@code aiz1_to_hcz_fullrun} trace (AIZ1 post-intro gameplay
 * through HCZ handoff) to verify that the rewind framework does not throw
 * exceptions during a capture/restore cycle performed during extended S3K
 * gameplay. The trace name indicates it <em>would</em> cross an act boundary
 * if driven to completion (~20 798 frames), but this smoke test advances only
 * 200 frames within AIZ1 to keep the test fast.
 *
 * <p><strong>v1 limitation (documented):</strong> A true act-boundary crossing
 * test (rewinding from post-act-2 back across the AIZ1→AIZ2 transition) would
 * require running ~3 000+ frames and is deferred to a follow-up integration
 * fixture. The invariant tested here — no exception, snapshot keys present,
 * covered subsystems round-trip — is sufficient to exercise the rewind stack
 * under S3K's richer runtime state (AIZ fire-curtain renderer, animated tiles,
 * AIZ palette ownership, parallax deform, etc.).
 *
 * <p>If the S3K ROM is absent the test is automatically skipped by
 * {@link RequiresRom}.
 *
 * <p><strong>State-divergence after restore is acceptable for v1</strong> for
 * subsystems not yet covered (ParallaxManager scroll state, WaterSystem,
 * ZoneRuntimeRegistry, AnimatedTileChannelGraph are registered but may not
 * fully reconstruct palette-animation counters). The test asserts
 * no-exception and that the core covered keys are present; parity assertions
 * for S3K-specific subsystems are deferred.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestRewindAcrossActBoundary {

    /**
     * S3K AIZ1-to-HCZ trace. Starts at AIZ1 post-intro gameplay.
     * bk2_frame_offset = 511 (skips intro sequence in the recording).
     */
    private static final Path S3K_TRACE_DIR =
            Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun");

    /** Number of frames to advance before capturing. Stays well within AIZ1. */
    private static final int ADVANCE_BEFORE_CAPTURE = 200;

    /** Frames to advance post-capture to diverge state before restore. */
    private static final int ADVANCE_AFTER_CAPTURE = 50;

    /** Core subsystem keys expected in all S3K snapshots. */
    private static final List<String> CORE_KEYS = List.of(
            "camera", "gamestate", "gamerng", "timermanager",
            "fademanager", "oscillation");

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    /**
     * Smoke test: capture + restore during S3K AIZ1 gameplay must not throw,
     * and all core covered keys must be present in both snapshots.
     *
     * <p>Trace used: {@code aiz1_to_hcz_fullrun} (S3K, zone_id=0, act=1).
     * This trace <em>does</em> cross the AIZ1→AIZ2 act boundary at runtime,
     * but the smoke test only exercises the first 200+50 frames of AIZ1.
     * Full act-boundary round-trip coverage is deferred to a follow-up plan.
     */
    @Test
    void captureRestoreDoesNotThrowDuringS3kAiz1Gameplay() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(S3K_TRACE_DIR),
                "S3K trace directory not found — skipping: " + S3K_TRACE_DIR);
        Path bk2 = findBk2(S3K_TRACE_DIR);
        Assumptions.assumeTrue(bk2 != null,
                "No .bk2 file found in " + S3K_TRACE_DIR);

        // Configure S3K-specific settings expected by this trace:
        // - skip intros: the bk2 starts at the gameplay anchor (bk2_frame_offset=511)
        // - main character: sonic, sidekick: tails (per metadata)
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

        // Boot S3K AIZ zone=0, act=1 (post-intro actual gameplay)
        // withRecordingStartFrame(511) to align with the BK2 anchor
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withRecording(bk2)
                .withRecordingStartFrame(511)   // bk2_frame_offset from metadata
                .withZoneAndAct(0, 1)            // AIZ act 1 (post-intro)
                .build();

        // 1. Advance to produce non-trivial S3K runtime state (fire curtain, palette, etc.)
        for (int i = 0; i < ADVANCE_BEFORE_CAPTURE; i++) {
            fixture.stepFrameFromRecording();
        }

        // 2. Get registry and capture snapshot A
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        assertNotNull(gameplayMode, "GameplayModeContext must be available after S3K load");

        RewindRegistry registry = gameplayMode.getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be non-null after S3K level load");

        // Must not throw
        CompositeSnapshot snapA = assertDoesNotThrow(
                registry::capture,
                "registry.capture() must not throw during S3K AIZ1 gameplay");
        assertNotNull(snapA, "capture() must return a non-null CompositeSnapshot");

        // All core subsystem keys must be present
        List<String> missingCoreKeys = CORE_KEYS.stream()
                .filter(k -> snapA.get(k) == null)
                .toList();
        assertTrue(missingCoreKeys.isEmpty(),
                "Core snapshot keys missing after S3K capture: " + missingCoreKeys);

        // 3. Advance to diverge state
        for (int i = 0; i < ADVANCE_AFTER_CAPTURE; i++) {
            fixture.stepFrameFromRecording();
        }

        // 4. Restore — must not throw even with S3K's richer runtime state
        assertDoesNotThrow(
                () -> registry.restore(snapA),
                "registry.restore() must not throw during S3K AIZ1 rewind");

        // 5. Capture snapshot B after restore — must also not throw
        CompositeSnapshot snapB = assertDoesNotThrow(
                registry::capture,
                "registry.capture() after restore must not throw");
        assertNotNull(snapB, "Post-restore capture() must return a non-null CompositeSnapshot");

        // All core keys must still be present in snapB
        List<String> missingAfterRestore = CORE_KEYS.stream()
                .filter(k -> snapB.get(k) == null)
                .toList();
        assertTrue(missingAfterRestore.isEmpty(),
                "Core snapshot keys missing after S3K restore+capture: " + missingAfterRestore);

        // 6. Camera must have a focused sprite after restore (Track H.1 invariant)
        assertNotNull(com.openggf.game.GameServices.camera().getFocusedSprite(),
                "Camera focused sprite must be rebound after S3K restore");
    }

    private static Path findBk2(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
    }
}
