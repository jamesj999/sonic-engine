package com.openggf.control;

import static org.lwjgl.glfw.GLFW.*;

/** Shared menu controls and hints, selected by the last intentional physical input. */
public final class MenuInput {
    private MenuInput() {
    }

    /** GLFW text input is consumed only by an active editor, never gameplay bindings. */
    public static void handleCharEvent(InputHandler input, int codepoint) {
        input.appendMenuCodepoint(codepoint);
    }

    public static String consumeText(InputHandler input) { return input.consumeMenuText(); }
    public static boolean textKeyPressed(InputHandler input, int key) { return input.isRawKeyPressed(key); }
    public static boolean textBack(InputHandler input) {
        return input.isRawKeyPressed(GLFW_KEY_ESCAPE) || input.physicalMenuGamepad().menuBack();
    }
    public static boolean textAccept(InputHandler input) {
        PlayerInputState pad = input.physicalMenuGamepad().player1();
        return !textBack(input) && (input.isRawKeyPressed(GLFW_KEY_ENTER)
                || input.isRawKeyPressed(GLFW_KEY_KP_ENTER) || pad.startPressed()
                || (pad.actionPressedMask() & (InputActionMasks.ACTION_A | InputActionMasks.ACTION_B)) != 0);
    }
    public static boolean textLeft(InputHandler input) {
        return input.isRawKeyPressed(GLFW_KEY_LEFT) || input.physicalMenuGamepad().menuLeft();
    }
    public static boolean textRight(InputHandler input) {
        return input.isRawKeyPressed(GLFW_KEY_RIGHT) || input.physicalMenuGamepad().menuRight();
    }
    public static boolean textUp(InputHandler input) {
        return input.isRawKeyPressed(GLFW_KEY_UP) || input.physicalMenuGamepad().menuUp();
    }
    public static boolean textDown(InputHandler input) {
        return input.isRawKeyPressed(GLFW_KEY_DOWN) || input.physicalMenuGamepad().menuDown();
    }

    public static boolean controller(InputHandler input) {
        return input.usesControllerPresentation();
    }

    public static String confirmLabel(InputHandler input) {
        return controller(input) ? "A" : "Enter";
    }

    public static String backLabel(InputHandler input) {
        return controller(input) ? "B" : "Esc";
    }

    public static String directionLabel(InputHandler input) {
        return controller(input) ? "D-Pad" : "Arrows";
    }

    public static String horizontalLabel(InputHandler input) {
        return controller(input) ? "D-Pad L/R" : "Left/Right";
    }

    /** Back takes precedence if the same frame also contains a confirm edge. */
    public static boolean accept(InputHandler input) {
        return !back(input) && (input.isRawKeyPressed(GLFW_KEY_ENTER)
                || input.isRawKeyPressed(GLFW_KEY_KP_ENTER)
                || mappedAccept(input));
    }

    public static boolean back(InputHandler input) {
        return input.isRawKeyPressed(GLFW_KEY_ESCAPE) || mapped(input).menuBack();
    }

    public static boolean up(InputHandler input) {
        return input.isRawKeyPressed(GLFW_KEY_UP) || mapped(input).menuUp();
    }

    public static boolean down(InputHandler input) {
        return input.isRawKeyPressed(GLFW_KEY_DOWN) || mapped(input).menuDown();
    }

    public static boolean left(InputHandler input) {
        return input.isRawKeyPressed(GLFW_KEY_LEFT) || mapped(input).menuLeft();
    }

    public static boolean right(InputHandler input) {
        return input.isRawKeyPressed(GLFW_KEY_RIGHT) || mapped(input).menuRight();
    }

    private static boolean mappedAccept(InputHandler input) {
        PlayerInputState player = mapped(input).player1();
        return (player.actionPressedMask() & (InputActionMasks.ACTION_A | InputActionMasks.ACTION_B)) != 0
                || player.startPressed();
    }

    private static LogicalInputSnapshot mapped(InputHandler input) {
        // Fixed menu keys own this frame's keyboard intent. Their gameplay bindings
        // may point elsewhere (Enter -> C, Left -> Right), so retain only the pad
        // contribution alongside them. In particular a simultaneous physical B still
        // takes precedence over keyboard Enter. Replay overrides remain authoritative.
        boolean fixed = input.isRawKeyPressed(GLFW_KEY_ENTER) || input.isRawKeyPressed(GLFW_KEY_KP_ENTER)
                || input.isRawKeyPressed(GLFW_KEY_ESCAPE) || input.isRawKeyPressed(GLFW_KEY_UP)
                || input.isRawKeyPressed(GLFW_KEY_DOWN) || input.isRawKeyPressed(GLFW_KEY_LEFT)
                || input.isRawKeyPressed(GLFW_KEY_RIGHT);
        return fixed ? input.menuWithoutMappedKeyboard() : input.logical();
    }

}
