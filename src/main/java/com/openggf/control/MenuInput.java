package com.openggf.control;

import com.openggf.sprites.playable.AbstractPlayableSprite;

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
    public static boolean textKeyRepeated(InputHandler input, int key) {
        return key >= 0 && key < input.keys.length
                && input.menuRepeat.pulse(key + 1024, input.menuFrame, input.keys[key], input.isRawKeyPressed(key));
    }
    public static boolean details(InputHandler input) {
        return input.isRawKeyPressed(GLFW_KEY_F1) || input.menuDetailsPressed();
    }
    public static boolean textDetails(InputHandler input) { return details(input); }
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
        return direction(input, GLFW_KEY_LEFT, AbstractPlayableSprite.INPUT_LEFT, input.physicalMenuGamepad(), true);
    }
    public static boolean textRight(InputHandler input) {
        return direction(input, GLFW_KEY_RIGHT, AbstractPlayableSprite.INPUT_RIGHT, input.physicalMenuGamepad(), true);
    }
    public static boolean textUp(InputHandler input) {
        return direction(input, GLFW_KEY_UP, AbstractPlayableSprite.INPUT_UP, input.physicalMenuGamepad(), true);
    }
    public static boolean textDown(InputHandler input) {
        return direction(input, GLFW_KEY_DOWN, AbstractPlayableSprite.INPUT_DOWN, input.physicalMenuGamepad(), true);
    }

    public static boolean controller(InputHandler input) {
        return input.usesControllerPresentation();
    }

    public static String confirmLabel(InputHandler input) {
        return controller(input) ? input.menuControllerStyle().confirm() : "Enter";
    }

    public static String backLabel(InputHandler input) {
        return controller(input) ? input.menuControllerStyle().back() : "Esc";
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
        return direction(input, GLFW_KEY_UP, AbstractPlayableSprite.INPUT_UP, mapped(input), false);
    }

    public static boolean down(InputHandler input) {
        return direction(input, GLFW_KEY_DOWN, AbstractPlayableSprite.INPUT_DOWN, mapped(input), false);
    }

    public static boolean left(InputHandler input) {
        return direction(input, GLFW_KEY_LEFT, AbstractPlayableSprite.INPUT_LEFT, mapped(input), false);
    }

    public static boolean right(InputHandler input) {
        return direction(input, GLFW_KEY_RIGHT, AbstractPlayableSprite.INPUT_RIGHT, mapped(input), false);
    }

    public static String detailsLabel(InputHandler input) {
        return controller(input) ? input.menuControllerStyle().details() : "F1";
    }

    private static boolean direction(InputHandler input, int key, int mask, LogicalInputSnapshot snapshot, boolean text) {
        return input.menuRepeat.pulse(text ? key + 1024 : key, input.menuFrame,
                input.keys[key] || (snapshot.player1().heldMask() & mask) != 0,
                input.isRawKeyPressed(key) || (snapshot.player1().pressedMask() & mask) != 0);
    }

    private static boolean mappedAccept(InputHandler input) {
        PlayerInputState player = mapped(input).player1();
        return (player.actionPressedMask() & (InputActionMasks.ACTION_A | InputActionMasks.ACTION_B)) != 0
                || player.startPressed();
    }

    private static boolean rawHeld(InputHandler input, int key) { return input.keys[key]; }

    private static LogicalInputSnapshot mapped(InputHandler input) {
        // Fixed menu keys own this frame's keyboard intent. Their gameplay bindings
        // may point elsewhere (Enter -> C, Left -> Right), so retain only the pad
        // contribution alongside them. In particular a simultaneous physical B still
        // takes precedence over keyboard Enter. Replay overrides remain authoritative.
        boolean fixed = rawHeld(input, GLFW_KEY_ENTER) || rawHeld(input, GLFW_KEY_KP_ENTER)
                || rawHeld(input, GLFW_KEY_ESCAPE) || rawHeld(input, GLFW_KEY_UP)
                || rawHeld(input, GLFW_KEY_DOWN) || rawHeld(input, GLFW_KEY_LEFT)
                || rawHeld(input, GLFW_KEY_RIGHT);
        return fixed ? input.menuWithoutMappedKeyboard() : input.logical();
    }

}
