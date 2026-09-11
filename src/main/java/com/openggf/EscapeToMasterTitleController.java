package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

public final class EscapeToMasterTitleController {
    public static final String MESSAGE = "hold ESC 2sec to return to title";
    public static final String RETURN_MESSAGE = MESSAGE;
    public static final String EXIT_MESSAGE = "hold ESC 2sec to exit OpenGGF";
    public static final int PROMPT_MINIMUM_FRAMES = 120;
    public static final int HOLD_FRAMES = 120;

    private final BooleanSupplier transitionBlocked;
    private final Runnable returnToMasterTitleStarter;
    private final Runnable exitApplicationStarter;

    private TransitionTarget activeTarget;
    private int promptFramesRemaining;
    private int heldFrames;
    private boolean transitionStarted;

    public EscapeToMasterTitleController(BooleanSupplier transitionBlocked, Runnable transitionStarter) {
        this(transitionBlocked, transitionStarter, () -> {});
    }

    public EscapeToMasterTitleController(
            BooleanSupplier transitionBlocked,
            Runnable returnToMasterTitleStarter,
            Runnable exitApplicationStarter) {
        this.transitionBlocked = Objects.requireNonNull(transitionBlocked, "transitionBlocked");
        this.returnToMasterTitleStarter =
                Objects.requireNonNull(returnToMasterTitleStarter, "returnToMasterTitleStarter");
        this.exitApplicationStarter = Objects.requireNonNull(exitApplicationStarter, "exitApplicationStarter");
    }

    public void update(GameMode mode, InputHandler input) {
        TransitionTarget target = targetFor(mode);
        if (target == null || input == null) {
            reset();
            return;
        }
        if (target != activeTarget) {
            reset();
            activeTarget = target;
        }

        boolean escapeDown = input.isKeyDown(GLFW_KEY_ESCAPE);
        boolean escapePressed = input.isKeyPressed(GLFW_KEY_ESCAPE);
        if (escapePressed) {
            promptFramesRemaining = PROMPT_MINIMUM_FRAMES;
        } else if (promptFramesRemaining > 0 && !escapeDown) {
            promptFramesRemaining--;
        }

        if (escapeDown) {
            heldFrames = Math.min(HOLD_FRAMES, heldFrames + 1);
        } else if (!transitionStarted) {
            heldFrames = 0;
        }

        if (!transitionStarted && heldFrames >= HOLD_FRAMES && !transitionBlocked.getAsBoolean()) {
            transitionStarted = true;
            transitionStarterFor(target).run();
        }
    }

    public boolean visible() {
        return promptFramesRemaining > 0 || heldFrames > 0 || transitionStarted;
    }

    public String message() {
        return activeTarget == TransitionTarget.EXIT_APPLICATION ? EXIT_MESSAGE : RETURN_MESSAGE;
    }

    public double progress() {
        return Math.min(1.0, heldFrames / (double) HOLD_FRAMES);
    }

    public void reset() {
        activeTarget = null;
        promptFramesRemaining = 0;
        heldFrames = 0;
        transitionStarted = false;
    }

    private static TransitionTarget targetFor(GameMode mode) {
        if (mode == null || mode == GameMode.LEGAL_DISCLAIMER || mode == GameMode.EDITOR) {
            return null;
        }
        return mode == GameMode.MASTER_TITLE_SCREEN
                ? TransitionTarget.EXIT_APPLICATION
                : TransitionTarget.RETURN_TO_MASTER_TITLE;
    }

    private Runnable transitionStarterFor(TransitionTarget target) {
        return target == TransitionTarget.EXIT_APPLICATION ? exitApplicationStarter : returnToMasterTitleStarter;
    }

    private enum TransitionTarget {
        RETURN_TO_MASTER_TITLE,
        EXIT_APPLICATION
    }
}
