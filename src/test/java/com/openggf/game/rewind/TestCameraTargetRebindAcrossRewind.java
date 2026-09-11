package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Track H.1 — Camera target rebind test across rewind.
 *
 * <p>Verifies that after a rewind snapshot restore, the Camera's focused
 * sprite is not null — i.e., {@link Camera#restore} correctly re-resolves the
 * main player sprite via SpriteManager rather than leaving the field in a
 * stale or null state.
 *
 * <p><strong>Scenario:</strong>
 * <ol>
 *   <li>Boot S2 EHZ1 via BK2 trace and advance 60 frames to establish
 *       non-trivial camera state.</li>
 *   <li>Assert camera already has a focused sprite (pre-condition).</li>
 *   <li>Capture a CompositeSnapshot.</li>
 *   <li>Clear camera state via {@link Camera#resetState()} which nulls the
 *       focused sprite field, simulating a state that would occur if a
 *       previous restore left the reference stale.</li>
 *   <li>Restore the snapshot via the RewindRegistry.</li>
 *   <li>Assert the focused sprite is back — not null and pointing to the
 *       same logical player sprite that was set before the clear.</li>
 * </ol>
 *
 * <p>If this test fails, it means {@link Camera#restore} is not rebinding
 * {@code focusedSprite}, which is a real defect in the Track C camera
 * adapter.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestCameraTargetRebindAcrossRewind {

    private static final Path TRACE_DIR =
            Path.of("src/test/resources/traces/s2/ehz1_fullrun");

    private static final int ADVANCE_FRAMES = 60;

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    void cameraFocusedSpriteIsReboundAfterSnapshotRestore() throws Exception {
        // Skip if trace is absent (CI without recordings)
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR),
                "Trace directory not found: " + TRACE_DIR);
        Path bk2Path = findBk2(TRACE_DIR);
        Assumptions.assumeTrue(bk2Path != null,
                "No .bk2 file found in " + TRACE_DIR);

        // 1. Boot S2 EHZ1 with BK2 recording
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withRecording(bk2Path)
                .withZoneAndAct(0, 0)
                .build();

        Camera camera = fixture.camera();
        AbstractPlayableSprite expectedSprite = fixture.sprite();

        // 2. Pre-condition: camera must track the player after boot
        assertNotNull(camera.getFocusedSprite(),
                "Camera must have a focused sprite after fixture build");
        assertSame(expectedSprite, camera.getFocusedSprite(),
                "Camera must track the main player sprite after fixture build");

        // 3. Advance to produce meaningful state
        for (int i = 0; i < ADVANCE_FRAMES; i++) {
            fixture.stepFrameFromRecording();
        }

        // 4. Confirm focused sprite is still valid mid-play
        assertNotNull(camera.getFocusedSprite(),
                "Camera must still have a focused sprite after advancing frames");

        // 5. Capture snapshot via RewindRegistry
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        assertNotNull(gameplayMode, "GameplayModeContext must be available");
        RewindRegistry registry = gameplayMode.getRewindRegistry();
        assertNotNull(registry, "RewindRegistry must be non-null");

        CompositeSnapshot snap = registry.capture();
        assertNotNull(snap, "capture() must return a non-null CompositeSnapshot");
        assertNotNull(snap.get("camera"), "Snapshot must contain 'camera' key");

        // 6. Clear camera state (nulls focusedSprite among other fields)
        camera.resetState();
        assertNull(camera.getFocusedSprite(),
                "focusedSprite must be null after resetState() — pre-condition for restore test");

        // 7. Restore snapshot — Camera.restore() must rebind focusedSprite
        registry.restore(snap);

        // 8. Assert: focusedSprite must be re-resolved after restore
        assertNotNull(camera.getFocusedSprite(),
                "Camera.restore() must rebind focusedSprite via SpriteManager — " +
                "if null, Camera's restore() does not re-resolve the main player sprite.");

        // 9. The rebound sprite must be the same logical player sprite
        assertSame(expectedSprite, camera.getFocusedSprite(),
                "Rebound focused sprite must be the same AbstractPlayableSprite instance " +
                "as the main player registered in SpriteManager.");
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
