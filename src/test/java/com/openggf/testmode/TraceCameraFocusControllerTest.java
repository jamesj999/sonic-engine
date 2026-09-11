package com.openggf.testmode;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.testmode.TraceCameraFocusController.FocusMode;
import com.openggf.trace.TraceCharacterState;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.live.LiveTraceComparator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceCameraFocusControllerTest {

    private LiveTraceComparator comparator;
    private SonicConfigurationService config;
    private InputHandler input;
    private Camera camera;
    private AtomicBoolean paused;
    private AtomicReference<AbstractPlayableSprite> mainSprite;
    private AtomicReference<AbstractPlayableSprite> sidekick;

    @BeforeEach
    void setup() {
        comparator = mock(LiveTraceComparator.class);
        config = mock(SonicConfigurationService.class);
        input = mock(InputHandler.class);
        camera = mock(Camera.class);
        paused = new AtomicBoolean(false);
        mainSprite = new AtomicReference<>(null);
        sidekick = new AtomicReference<>(null);

        when(config.getInt(SonicConfiguration.LEFT)).thenReturn(263);   // GLFW_KEY_LEFT
        when(config.getInt(SonicConfiguration.RIGHT)).thenReturn(262);  // GLFW_KEY_RIGHT
        when(config.getInt(SonicConfiguration.FRAME_STEP_KEY)).thenReturn(70); // some key
        when(camera.getX()).thenReturn((short) 100);
        when(camera.getY()).thenReturn((short) 200);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.getMinX()).thenReturn((short) 0);
        when(camera.getMaxX()).thenReturn((short) 10000);
        when(camera.getMinY()).thenReturn((short) 0);
        when(camera.getMaxY()).thenReturn((short) 10000);
    }

    private TraceCameraFocusController newController() {
        return new TraceCameraFocusController(
                comparator, mainSprite::get, sidekick::get,
                () -> camera, config, paused::get);
    }

    private static AbstractPlayableSprite spriteAt(int centreX, int centreY) {
        AbstractPlayableSprite s = mock(AbstractPlayableSprite.class);
        when(s.getCentreX()).thenReturn((short) centreX);
        when(s.getCentreY()).thenReturn((short) centreY);
        return s;
    }

    private static TraceFrame frameWith(int mainX, int mainY, TraceCharacterState sidekick) {
        return new TraceFrame(0, 0, (short) mainX, (short) mainY,
                (short) 0, (short) 0, (short) 0, (byte) 0, false, false, 0,
                0, 0, -1, -1, -1, -1, -1, -1, -1, -1, -1, sidekick);
    }

    @Test
    void availableListIsDefaultOnlyWhenNoMainSpriteOrSidekick() {
        when(comparator.currentVisualFrame()).thenReturn(null);
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        assertEquals(1, controller.availableSize());
        assertEquals(FocusMode.DEFAULT, controller.activeMode());
    }

    @Test
    void availableListIncludesMainEngineWhenSpriteAlive() {
        mainSprite.set(spriteAt(500, 300));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(500, 300, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        // Main engine added; trace position matches engine, so MAIN_TRACE skipped.
        assertEquals(2, controller.availableSize());
    }

    @Test
    void availableListIncludesMainTraceWhenPositionsDiffer() {
        mainSprite.set(spriteAt(500, 300));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(600, 300, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        assertEquals(3, controller.availableSize());
    }

    @Test
    void availableListIncludesSidekickEngineAndTraceWhenBothPresentAndDiffer() {
        mainSprite.set(spriteAt(500, 300));
        sidekick.set(spriteAt(450, 320));
        TraceCharacterState sk = new TraceCharacterState(true,
                (short) 470, (short) 320, (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0, 0, 0, -1, -1, -1);
        when(comparator.currentVisualFrame()).thenReturn(frameWith(500, 300, sk));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        // DEFAULT, SIDEKICK_ENGINE, SIDEKICK_TRACE, MAIN_ENGINE; MAIN_TRACE skipped (matches).
        assertEquals(4, controller.availableSize());
    }

    @Test
    void availableListSkipsSidekickTraceWhenSidekickAbsentInTrace() {
        sidekick.set(spriteAt(450, 320));
        TraceCharacterState sk = TraceCharacterState.absent();
        when(comparator.currentVisualFrame()).thenReturn(frameWith(500, 300, sk));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        // DEFAULT + SIDEKICK_ENGINE only.
        assertEquals(2, controller.availableSize());
    }

    @Test
    void availableListSkipsSidekickWhenEngineHasNoSidekick() {
        TraceCharacterState sk = new TraceCharacterState(true,
                (short) 470, (short) 320, (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0, 0, 0, -1, -1, -1);
        when(comparator.currentVisualFrame()).thenReturn(frameWith(500, 300, sk));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        // DEFAULT only — no SIDEKICK_TRACE without SIDEKICK_ENGINE.
        assertEquals(1, controller.availableSize());
    }

    @Test
    void labelIsNullWhenNotPaused() {
        TraceCameraFocusController controller = newController();
        assertNull(controller.currentLabel());
    }

    @Test
    void unpauseRestoresCameraToSavedPosition() {
        when(comparator.currentVisualFrame()).thenReturn(null);
        TraceCameraFocusController controller = newController();

        paused.set(true);
        when(camera.getX()).thenReturn((short) 1234);
        when(camera.getY()).thenReturn((short) 567);
        controller.tick(input);  // pause-edge enter, snapshots 1234/567

        paused.set(false);
        controller.tick(input);  // pause-edge exit, must restore

        org.mockito.Mockito.verify(camera).setX((short) 1234);
        org.mockito.Mockito.verify(camera).setY((short) 567);
    }

    @Test
    void labelClearedAfterUnpause() {
        when(comparator.currentVisualFrame()).thenReturn(null);
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        assertEquals("Default", controller.currentLabel());
        paused.set(false);
        controller.tick(input);
        assertNull(controller.currentLabel());
    }

    @Test
    void rightArrowAdvancesFocusAndAppliesCameraCentredOnTarget() {
        mainSprite.set(spriteAt(1000, 500));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(1000, 500, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        // Available: DEFAULT, MAIN_ENGINE.

        when(input.isKeyPressed(262)).thenReturn(true);  // RIGHT
        controller.tick(input);
        when(input.isKeyPressed(262)).thenReturn(false);

        assertEquals(FocusMode.MAIN_ENGINE, controller.activeMode());
        // Centred: camX = 1000 - 320/2 = 840; camY = 500 - 224/2 = 388.
        org.mockito.Mockito.verify(camera).setX((short) 840);
        org.mockito.Mockito.verify(camera).setY((short) 388);
    }

    @Test
    void leftArrowFromDefaultWrapsToLastAvailable() {
        mainSprite.set(spriteAt(1000, 500));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(2000, 600, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        // Available: DEFAULT, MAIN_ENGINE, MAIN_TRACE (3 items).

        when(input.isKeyPressed(263)).thenReturn(true);  // LEFT
        controller.tick(input);
        when(input.isKeyPressed(263)).thenReturn(false);

        assertEquals(FocusMode.MAIN_TRACE, controller.activeMode());
        // MAIN_TRACE centres on (2000, 600): camX = 2000-160 = 1840, camY = 600-112 = 488.
        org.mockito.Mockito.verify(camera).setX((short) 1840);
        org.mockito.Mockito.verify(camera).setY((short) 488);
    }

    @Test
    void cycleClampsToCameraBounds() {
        mainSprite.set(spriteAt(50, 50));  // near top-left of level
        when(comparator.currentVisualFrame()).thenReturn(frameWith(50, 50, null));
        when(camera.getMinX()).thenReturn((short) 0);
        when(camera.getMaxX()).thenReturn((short) 10000);
        when(camera.getMinY()).thenReturn((short) 0);
        when(camera.getMaxY()).thenReturn((short) 10000);
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);

        when(input.isKeyPressed(262)).thenReturn(true);  // RIGHT to MAIN_ENGINE
        controller.tick(input);

        // 50 - 160 = -110, clamped to 0.
        org.mockito.Mockito.verify(camera).setX((short) 0);
        org.mockito.Mockito.verify(camera).setY((short) 0);
    }

    @Test
    void frameStepRestoresCameraBeforeStepAndReappliesFocusAfter() {
        mainSprite.set(spriteAt(1000, 500));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(1000, 500, null));
        TraceCameraFocusController controller = newController();

        when(camera.getX()).thenReturn((short) 100);
        when(camera.getY()).thenReturn((short) 200);
        paused.set(true);
        controller.tick(input);  // pause-edge enter

        when(input.isKeyPressed(262)).thenReturn(true);
        controller.tick(input);  // RIGHT -> MAIN_ENGINE
        when(input.isKeyPressed(262)).thenReturn(false);
        org.mockito.Mockito.clearInvocations(camera);

        // Frame-step pressed: paused stays true, controller should restore savedCam,
        // remember the focus, and let the step happen.
        when(input.isKeyPressed(70)).thenReturn(true);  // FRAME_STEP_KEY
        controller.tick(input);
        when(input.isKeyPressed(70)).thenReturn(false);

        org.mockito.Mockito.verify(camera).setX((short) 100);
        org.mockito.Mockito.verify(camera).setY((short) 200);
        org.mockito.Mockito.clearInvocations(camera);

        // Post-update (after gameplay update, before render): re-apply MAIN_ENGINE focus
        // within the same frame so the rendered frame shows the focus camera (no flicker).
        controller.postUpdate();
        org.mockito.Mockito.verify(camera).setX((short) 840);
        org.mockito.Mockito.verify(camera).setY((short) 388);
        assertEquals(FocusMode.MAIN_ENGINE, controller.activeMode());
    }

    @Test
    void frameStepFallsBackToDefaultWhenPreviousFocusGone() {
        mainSprite.set(spriteAt(1000, 500));
        sidekick.set(spriteAt(950, 500));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(1000, 500, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        // Available: DEFAULT, SIDEKICK_ENGINE, MAIN_ENGINE.

        // Move to SIDEKICK_ENGINE.
        when(input.isKeyPressed(262)).thenReturn(true);
        controller.tick(input);
        when(input.isKeyPressed(262)).thenReturn(false);
        assertEquals(FocusMode.SIDEKICK_ENGINE, controller.activeMode());

        // Frame-step: pre-update tick restores savedCam, gameplay runs and the
        // sidekick despawns during the update.
        when(input.isKeyPressed(70)).thenReturn(true);
        controller.tick(input);
        when(input.isKeyPressed(70)).thenReturn(false);
        sidekick.set(null);

        // Post-update (same frame, after the update): rebuild available list;
        // SIDEKICK_ENGINE is gone, fall back to DEFAULT before render.
        controller.postUpdate();
        assertEquals(FocusMode.DEFAULT, controller.activeMode());
        // wasPaused is still true from the pre-update tick, so the label reflects
        // the new focus rather than the stale SIDEKICK_ENGINE value.
        assertEquals("Default", controller.currentLabel());
    }

    @Test
    void controllerOnlyMutatesSetXAndSetYOnCamera() {
        mainSprite.set(spriteAt(1000, 500));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(2000, 600, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);
        when(input.isKeyPressed(262)).thenReturn(true);
        controller.tick(input);
        when(input.isKeyPressed(262)).thenReturn(false);
        controller.tick(input);
        paused.set(false);
        controller.tick(input);

        // updatePosition must NEVER be called.
        org.mockito.Mockito.verify(camera, org.mockito.Mockito.never()).updatePosition();
        org.mockito.Mockito.verify(camera, org.mockito.Mockito.never()).updatePosition(
                org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.verify(camera, org.mockito.Mockito.never()).setShakeOffsets(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void postUpdateIsNoOpWhenNoFrameStepRanThisTick() {
        mainSprite.set(spriteAt(1000, 500));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(1000, 500, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);  // enter pause
        when(input.isKeyPressed(262)).thenReturn(true);
        controller.tick(input);  // cycle to MAIN_ENGINE
        when(input.isKeyPressed(262)).thenReturn(false);
        org.mockito.Mockito.clearInvocations(camera);

        controller.postUpdate();  // no frame-step happened -> nothing to do.

        org.mockito.Mockito.verifyNoInteractions(camera);
        assertEquals(FocusMode.MAIN_ENGINE, controller.activeMode());
    }

    @Test
    void frameStepNoFlickerCameraEndsAtFocusBeforeRender() {
        // Verifies the no-flicker invariant: by the time the post-update hook
        // returns (i.e. just before the engine's draw() runs), the camera holds
        // the focus position, NOT the saved-camera position. The saved-camera
        // write was a transient that only existed during the gameplay update.
        mainSprite.set(spriteAt(1000, 500));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(1000, 500, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);  // enter pause
        when(input.isKeyPressed(262)).thenReturn(true);
        controller.tick(input);  // cycle to MAIN_ENGINE -> camera at (840, 388)
        when(input.isKeyPressed(262)).thenReturn(false);

        when(input.isKeyPressed(70)).thenReturn(true);  // frame-step
        controller.tick(input);  // pre-update: camera transiently restored to savedCam
        when(input.isKeyPressed(70)).thenReturn(false);
        controller.postUpdate();  // post-update: camera back to focus

        // The LAST setX/setY observed must be the focus position, not the saved
        // camera. Mockito InOrder lets us assert ordering on a single mock.
        var inOrder = org.mockito.Mockito.inOrder(camera);
        inOrder.verify(camera).setX((short) 100);  // savedCam restore (transient)
        inOrder.verify(camera).setX((short) 840);  // focus re-applied (final)
    }

    @Test
    void savedCamAdvancesEachFrameStepSoUnpauseStaysSynced() {
        // Verifies the desync fix: when frame-stepping with a non-DEFAULT focus,
        // the controller captures the camera's natural-tracking position after
        // each gameplay update, so DEFAULT and the unpause-restore both reflect
        // current ground-truth (not the stale pause-entry position).
        mainSprite.set(spriteAt(1000, 500));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(1000, 500, null));
        TraceCameraFocusController controller = newController();

        // Enter pause with savedCam = (100, 200).
        when(camera.getX()).thenReturn((short) 100);
        when(camera.getY()).thenReturn((short) 200);
        paused.set(true);
        controller.tick(input);

        // Cycle to MAIN_ENGINE so subsequent frame-steps must restore savedCam
        // (not stay on the focus camera).
        when(input.isKeyPressed(262)).thenReturn(true);
        controller.tick(input);
        when(input.isKeyPressed(262)).thenReturn(false);

        // Frame-step #1. Simulate the gameplay update advancing the camera by
        // 16px to the right (a typical fast-scroll catch-up step).
        when(input.isKeyPressed(70)).thenReturn(true);
        controller.tick(input);  // pre-update: restore savedCam=(100,200)
        when(input.isKeyPressed(70)).thenReturn(false);
        when(camera.getX()).thenReturn((short) 116);  // post-update natural position
        when(camera.getY()).thenReturn((short) 200);
        controller.postUpdate();
        // savedCam should now be (116, 200) — captured before re-apply.

        // Frame-step #2. Pre-update should restore to the NEW savedCam (116, 200),
        // not the stale (100, 200).
        org.mockito.Mockito.clearInvocations(camera);
        when(input.isKeyPressed(70)).thenReturn(true);
        controller.tick(input);
        when(input.isKeyPressed(70)).thenReturn(false);

        org.mockito.Mockito.verify(camera).setX((short) 116);
        org.mockito.Mockito.verify(camera).setY((short) 200);
    }

    @Test
    void unpauseAfterFrameStepsRestoresLatestNaturalCameraNotStaleSavedCam() {
        // End-to-end check: pause, cycle, frame-step several times, then unpause.
        // The unpause must restore the latest natural-tracking camera position,
        // NOT the pause-entry position. This is the desync fix the user reported.
        mainSprite.set(spriteAt(1000, 500));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(1000, 500, null));
        TraceCameraFocusController controller = newController();

        when(camera.getX()).thenReturn((short) 100);
        when(camera.getY()).thenReturn((short) 200);
        paused.set(true);
        controller.tick(input);

        when(input.isKeyPressed(262)).thenReturn(true);
        controller.tick(input);  // cycle to MAIN_ENGINE
        when(input.isKeyPressed(262)).thenReturn(false);

        // Frame-step thrice. Each step's gameplay update advances camera by 16px.
        for (int i = 0; i < 3; i++) {
            when(input.isKeyPressed(70)).thenReturn(true);
            controller.tick(input);
            when(input.isKeyPressed(70)).thenReturn(false);
            short newX = (short) (100 + 16 * (i + 1));  // 116, 132, 148
            when(camera.getX()).thenReturn(newX);
            when(camera.getY()).thenReturn((short) 200);
            controller.postUpdate();
        }

        // Unpause. exitPause must restore camera to (148, 200) — the latest
        // tracked position — not the original pause-entry (100, 200).
        org.mockito.Mockito.clearInvocations(camera);
        paused.set(false);
        controller.tick(input);

        org.mockito.Mockito.verify(camera).setX((short) 148);
        org.mockito.Mockito.verify(camera).setY((short) 200);
    }

    @Test
    void externalRewindWhilePausedRefreshesSavedDefaultCamera() {
        when(comparator.currentVisualFrame()).thenReturn(null);
        TraceCameraFocusController controller = newController();

        when(camera.getX()).thenReturn((short) 100);
        when(camera.getY()).thenReturn((short) 200);
        paused.set(true);
        controller.tick(input);

        // Rewind restores the runtime camera while the pause focus controller is
        // active. Without an explicit sync, unpausing would restore stale
        // pause-entry coordinates and jump back to (100, 200).
        when(camera.getX()).thenReturn((short) 24);
        when(camera.getY()).thenReturn((short) 48);
        controller.syncDefaultCameraToCurrentPosition();

        org.mockito.Mockito.clearInvocations(camera);
        paused.set(false);
        controller.tick(input);

        org.mockito.Mockito.verify(camera).setX((short) 24);
        org.mockito.Mockito.verify(camera).setY((short) 48);
    }

    @Test
    void mainEngineLabelDropsEngSuffixWhenTraceMatches() {
        // Engine and trace main player at the same position -> MAIN_TRACE filtered
        // out of available -> MAIN_ENGINE has no sibling to disambiguate from,
        // so the label is just "Main" (no "(Eng)" suffix).
        mainSprite.set(spriteAt(500, 300));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(500, 300, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);

        when(input.isKeyPressed(262)).thenReturn(true);
        controller.tick(input);  // cycle to MAIN_ENGINE
        when(input.isKeyPressed(262)).thenReturn(false);

        assertEquals(FocusMode.MAIN_ENGINE, controller.activeMode());
        assertEquals("Main", controller.currentLabel());
    }

    @Test
    void mainEngineLabelKeepsEngSuffixWhenTraceVariantPresent() {
        mainSprite.set(spriteAt(500, 300));
        when(comparator.currentVisualFrame()).thenReturn(frameWith(600, 300, null));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);

        when(input.isKeyPressed(262)).thenReturn(true);
        controller.tick(input);  // cycle to MAIN_ENGINE
        when(input.isKeyPressed(262)).thenReturn(false);

        assertEquals(FocusMode.MAIN_ENGINE, controller.activeMode());
        assertEquals("Main (Eng)", controller.currentLabel());
    }

    @Test
    void sidekickEngineLabelDropsEngSuffixWhenTraceMatches() {
        sidekick.set(spriteAt(450, 320));
        TraceCharacterState sk = new TraceCharacterState(true,
                (short) 450, (short) 320, (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0, 0, 0, -1, -1, -1);
        when(comparator.currentVisualFrame()).thenReturn(frameWith(500, 300, sk));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);

        when(input.isKeyPressed(262)).thenReturn(true);
        controller.tick(input);  // cycle to SIDEKICK_ENGINE
        when(input.isKeyPressed(262)).thenReturn(false);

        assertEquals(FocusMode.SIDEKICK_ENGINE, controller.activeMode());
        assertEquals("Sidekick", controller.currentLabel());
    }

    @Test
    void sidekickEngineLabelKeepsEngSuffixWhenTraceVariantPresent() {
        sidekick.set(spriteAt(450, 320));
        TraceCharacterState sk = new TraceCharacterState(true,
                (short) 470, (short) 320, (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0, 0, 0, -1, -1, -1);
        when(comparator.currentVisualFrame()).thenReturn(frameWith(500, 300, sk));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);

        when(input.isKeyPressed(262)).thenReturn(true);
        controller.tick(input);  // cycle to SIDEKICK_ENGINE
        when(input.isKeyPressed(262)).thenReturn(false);

        assertEquals(FocusMode.SIDEKICK_ENGINE, controller.activeMode());
        assertEquals("Sidekick (Eng)", controller.currentLabel());
    }

    @Test
    void traceLabelsAlwaysKeepTraceSuffix() {
        // Trace variants only appear when positions differ, so they always
        // have an engine sibling and always need the "(Trace)" suffix to
        // disambiguate. No suffix-dropping path applies to them.
        mainSprite.set(spriteAt(500, 300));
        sidekick.set(spriteAt(450, 320));
        TraceCharacterState sk = new TraceCharacterState(true,
                (short) 470, (short) 320, (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0, 0, 0, -1, -1, -1);
        when(comparator.currentVisualFrame()).thenReturn(frameWith(600, 300, sk));
        TraceCameraFocusController controller = newController();
        paused.set(true);
        controller.tick(input);

        // Cycle until SIDEKICK_TRACE is active.
        while (controller.activeMode() != FocusMode.SIDEKICK_TRACE) {
            when(input.isKeyPressed(262)).thenReturn(true);
            controller.tick(input);
            when(input.isKeyPressed(262)).thenReturn(false);
        }
        assertEquals("Sidekick (Trace)", controller.currentLabel());

        while (controller.activeMode() != FocusMode.MAIN_TRACE) {
            when(input.isKeyPressed(262)).thenReturn(true);
            controller.tick(input);
            when(input.isKeyPressed(262)).thenReturn(false);
        }
        assertEquals("Main (Trace)", controller.currentLabel());
    }
}
