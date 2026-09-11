package com.openggf.testmode;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.TraceCharacterState;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.live.LiveTraceComparator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Trace Test Mode camera focus controller.
 *
 * <p>While paused during a live trace session, lets the user cycle the camera
 * between up to five focus targets (default, engine/trace sidekick, engine/trace
 * main player) via the P1 LEFT/RIGHT keys, then restores the camera to its
 * pre-pause position on unpause.
 *
 * <p><b>Camera mutation contract:</b> this class must only call
 * {@link Camera#setX(short)} and {@link Camera#setY(short)}. It must not call
 * {@link Camera#updatePosition} or any other camera method, and it must not
 * invoke any sprite/object/manager update path. Calling {@code updatePosition}
 * would clobber the focus value with the focused-sprite-derived position; the
 * pause early-return in {@link com.openggf.GameLoop} keeps every other system
 * frozen, so simply writing {@code x}/{@code y} is sufficient and safe.
 */
public final class TraceCameraFocusController {

    public enum FocusMode {
        DEFAULT("Default"),
        SIDEKICK_ENGINE("Sidekick (Eng)"),
        SIDEKICK_TRACE("Sidekick (Trace)"),
        MAIN_ENGINE("Main (Eng)"),
        MAIN_TRACE("Main (Trace)");

        private final String label;
        FocusMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final LiveTraceComparator comparator;
    private final Supplier<AbstractPlayableSprite> mainSpriteSupplier;
    private final Supplier<AbstractPlayableSprite> firstSidekickSupplier;
    private final Supplier<Camera> cameraSupplier;
    private final SonicConfigurationService configService;
    private final Supplier<Boolean> pausedSupplier;

    private final List<FocusMode> available = new ArrayList<>();
    private int activeIndex;
    private short savedCamX;
    private short savedCamY;
    private boolean wasPaused;
    private FocusMode reapplyAfterStep;

    public TraceCameraFocusController(
            LiveTraceComparator comparator,
            Supplier<AbstractPlayableSprite> mainSpriteSupplier,
            Supplier<AbstractPlayableSprite> firstSidekickSupplier,
            Supplier<Camera> cameraSupplier,
            SonicConfigurationService configService,
            Supplier<Boolean> pausedSupplier) {
        this.comparator = Objects.requireNonNull(comparator, "comparator");
        this.mainSpriteSupplier = Objects.requireNonNull(mainSpriteSupplier, "mainSpriteSupplier");
        this.firstSidekickSupplier = Objects.requireNonNull(firstSidekickSupplier, "firstSidekickSupplier");
        this.cameraSupplier = Objects.requireNonNull(cameraSupplier, "cameraSupplier");
        this.configService = Objects.requireNonNull(configService, "configService");
        this.pausedSupplier = Objects.requireNonNull(pausedSupplier, "pausedSupplier");
    }

    /**
     * @return current focus label for the HUD, or {@code null} if not paused.
     *
     * <p>When the engine and ROM-trace positions for a character match (so the
     * TRACE variant was filtered out of the available list), the (Eng) suffix
     * is dropped — there's no second variant to disambiguate from, so the bare
     * "Sidekick" / "Main" label is unambiguous and less noisy.
     */
    public String currentLabel() {
        if (!wasPaused || available.isEmpty()) return null;
        FocusMode mode = available.get(activeIndex);
        return switch (mode) {
            case SIDEKICK_ENGINE -> available.contains(FocusMode.SIDEKICK_TRACE) ? mode.label() : "Sidekick";
            case MAIN_ENGINE -> available.contains(FocusMode.MAIN_TRACE) ? mode.label() : "Main";
            default -> mode.label();
        };
    }

    /** Test hook: number of focuses currently available (including DEFAULT). */
    int availableSize() { return available.size(); }

    /** Test hook: currently active focus mode while paused. */
    FocusMode activeMode() { return available.isEmpty() ? null : available.get(activeIndex); }

    public void tick(InputHandler inputHandler) {
        boolean paused = pausedSupplier.get();
        if (!wasPaused && paused) {
            enterPause();
        } else if (wasPaused && !paused) {
            exitPause();
        } else if (paused) {
            int frameStepKey = configService.getInt(SonicConfiguration.FRAME_STEP_KEY);
            boolean frameStep = inputHandler.isKeyPressed(frameStepKey);
            if (frameStep) {
                // Restore saved camera so the upcoming gameplay update sees the
                // original viewpoint (object placement windows etc.). The focus
                // is re-applied in postUpdate(), which runs after the update but
                // before render, so the user never sees a flicker to default.
                reapplyAfterStep = available.get(activeIndex);
                Camera cam = cameraSupplier.get();
                cam.setX(savedCamX);
                cam.setY(savedCamY);
            } else {
                handleCycleInput(inputHandler);
            }
        }
        wasPaused = paused;
    }

    /**
     * Called by {@link com.openggf.GameLoop} at the end of each {@code stepInternal}
     * pass that ran a gameplay update. On the frame-step path, this:
     * <ol>
     *   <li>Captures the camera's post-update natural-tracking position as the new
     *       savedCam. The camera just ran one frame from the previous savedCam
     *       through {@link Camera#updatePosition} during the gameplay update, so
     *       its current x/y is exactly where the camera would be at this point in
     *       continuous play. Updating savedCam keeps DEFAULT and the unpause
     *       restore in sync with current ground-truth, so unpausing after several
     *       frame-steps doesn't jolt the camera back to the original pause-entry
     *       position (which would mismatch the player's now-advanced position and
     *       cause object-placement / scroll desync).</li>
     *   <li>Rebuilds the available focus list, since spawn state may have changed
     *       during the step (e.g., sidekick despawn).</li>
     *   <li>Re-applies the focus the user had selected, falling back to DEFAULT if
     *       it is no longer available. Doing this before render means the rendered
     *       frame already shows the focus camera, eliminating the flicker that a
     *       deferred (next-frame) re-apply would cause.</li>
     * </ol>
     */
    public void postUpdate() {
        if (reapplyAfterStep == null) return;
        Camera cam = cameraSupplier.get();
        savedCamX = cam.getX();
        savedCamY = cam.getY();
        FocusMode prev = reapplyAfterStep;
        reapplyAfterStep = null;
        buildAvailable();
        int idx = available.indexOf(prev);
        activeIndex = idx >= 0 ? idx : 0;
        applyFocus(available.get(activeIndex));
    }

    /**
     * Synchronizes the DEFAULT focus camera with an external runtime restore
     * such as rewind. Rewind restores camera state without running a normal
     * frame-step update, so the pause controller must refresh its saved default
     * before unpause can safely restore it.
     */
    public void syncDefaultCameraToCurrentPosition() {
        if (!wasPaused) return;
        Camera cam = cameraSupplier.get();
        savedCamX = cam.getX();
        savedCamY = cam.getY();
        FocusMode prev = available.isEmpty() ? FocusMode.DEFAULT : available.get(activeIndex);
        buildAvailable();
        int idx = available.indexOf(prev);
        activeIndex = idx >= 0 ? idx : 0;
        applyFocus(available.get(activeIndex));
    }

    private void handleCycleInput(InputHandler inputHandler) {
        if (available.size() <= 1) return;
        int leftKey = configService.getInt(SonicConfiguration.LEFT);
        int rightKey = configService.getInt(SonicConfiguration.RIGHT);
        int n = available.size();
        boolean changed = false;
        if (inputHandler.isKeyPressed(leftKey)) {
            activeIndex = (activeIndex - 1 + n) % n;
            changed = true;
        } else if (inputHandler.isKeyPressed(rightKey)) {
            activeIndex = (activeIndex + 1) % n;
            changed = true;
        }
        if (changed) {
            applyFocus(available.get(activeIndex));
        }
    }

    private void applyFocus(FocusMode mode) {
        Camera cam = cameraSupplier.get();
        if (mode == FocusMode.DEFAULT) {
            cam.setX(savedCamX);
            cam.setY(savedCamY);
            return;
        }
        int targetX;
        int targetY;
        // Each branch's null/absent guard is defensive: in normal flow the
        // available list (rebuilt at pause-entry and after every frame-step)
        // already excludes any mode whose target isn't reachable. We bail
        // silently here only to stay correct if a target despawns mid-pause
        // without a frame-step (which can't happen with the current pause
        // early-return, but is cheap to guard against).
        switch (mode) {
            case SIDEKICK_ENGINE -> {
                AbstractPlayableSprite s = firstSidekickSupplier.get();
                if (s == null) return;
                targetX = s.getCentreX();
                targetY = s.getCentreY();
            }
            case SIDEKICK_TRACE -> {
                TraceFrame f = comparator.currentVisualFrame();
                if (f == null || f.sidekick() == null || !f.sidekick().present()) return;
                targetX = f.sidekick().x();
                targetY = f.sidekick().y();
            }
            case MAIN_ENGINE -> {
                AbstractPlayableSprite s = mainSpriteSupplier.get();
                if (s == null) return;
                targetX = s.getCentreX();
                targetY = s.getCentreY();
            }
            case MAIN_TRACE -> {
                TraceFrame f = comparator.currentVisualFrame();
                if (f == null) return;
                targetX = f.x();
                targetY = f.y();
            }
            default -> { return; }
        }
        int halfW = cam.getWidth() / 2;
        int halfH = cam.getHeight() / 2;
        short camX = clampShort(targetX - halfW, cam.getMinX(), cam.getMaxX());
        short camY = clampShort(targetY - halfH, cam.getMinY(), cam.getMaxY());
        cam.setX(camX);
        cam.setY(camY);
    }

    // Mirrors Camera.clampAxisWithWrap (private in Camera). The camera's normal
    // path uses the same semantics — including the max<min "wrapped/unbounded"
    // fallback for transient SCZ-style writes — so keep this in sync if Camera
    // changes its clamping behaviour.
    private static short clampShort(int value, short min, short max) {
        if (max < min) {
            // Transient camera-bound state; fall through with no clamp.
            return (short) value;
        }
        if (value < min) return min;
        if (value > max) return max;
        return (short) value;
    }

    private void enterPause() {
        Camera cam = cameraSupplier.get();
        savedCamX = cam.getX();
        savedCamY = cam.getY();
        buildAvailable();
        activeIndex = 0;
        reapplyAfterStep = null;
    }

    private void exitPause() {
        Camera cam = cameraSupplier.get();
        cam.setX(savedCamX);
        cam.setY(savedCamY);
        available.clear();
        activeIndex = 0;
        reapplyAfterStep = null;
    }

    private void buildAvailable() {
        available.clear();
        available.add(FocusMode.DEFAULT);

        AbstractPlayableSprite engineSidekick = firstSidekickSupplier.get();
        AbstractPlayableSprite engineMain = mainSpriteSupplier.get();
        TraceFrame traceFrame = comparator.currentVisualFrame();
        TraceCharacterState traceSidekick = traceFrame != null ? traceFrame.sidekick() : null;

        if (engineSidekick != null) {
            available.add(FocusMode.SIDEKICK_ENGINE);
            if (traceSidekick != null && traceSidekick.present()) {
                int engX = engineSidekick.getCentreX();
                int engY = engineSidekick.getCentreY();
                if (traceSidekick.x() != engX || traceSidekick.y() != engY) {
                    available.add(FocusMode.SIDEKICK_TRACE);
                }
            }
        }

        if (engineMain != null) {
            available.add(FocusMode.MAIN_ENGINE);
            if (traceFrame != null) {
                int engX = engineMain.getCentreX();
                int engY = engineMain.getCentreY();
                if (traceFrame.x() != engX || traceFrame.y() != engY) {
                    available.add(FocusMode.MAIN_TRACE);
                }
            }
        }
    }
}
