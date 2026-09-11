package com.openggf.game;

import com.openggf.control.InputActionMasks;

/**
 * The two controllers' press edges for the current level frame, as the ROM's
 * {@code v_jpadpress1} / {@code Ctrl_1_Press} / {@code Ctrl_1_pressed} bytes
 * expose them to objects that poll a button rather than drive a player.
 *
 * @param player1ActionPressed {@link InputActionMasks} bits for A/B/C pressed this frame
 * @param player1StartPressed  Start pressed this frame
 * @param player2ActionPressed second controller A/B/C press bits
 * @param player2StartPressed  second controller Start press
 */
public record JoypadPressSnapshot(
        int player1ActionPressed,
        boolean player1StartPressed,
        int player2ActionPressed,
        boolean player2StartPressed) {

    public static final JoypadPressSnapshot NONE = new JoypadPressSnapshot(0, false, 0, false);

    /** {@code move.b (Ctrl_1_Press).w,d0; or.b (Ctrl_2_Press).w,d0} A/B/C bits. */
    public int eitherActionPressed() {
        return player1ActionPressed | player2ActionPressed;
    }

    /** {@code andi.b #btnABC,d0} on controller 1 alone. */
    public boolean player1AnyActionPressed() {
        return (player1ActionPressed & InputActionMasks.ACTION_ALL) != 0;
    }

    /** {@code andi.b #button_A_mask|button_B_mask|button_C_mask,d0} on the OR of both controllers. */
    public boolean eitherAnyActionPressed() {
        return (eitherActionPressed() & InputActionMasks.ACTION_ALL) != 0;
    }

    public boolean eitherStartPressed() {
        return player1StartPressed || player2StartPressed;
    }
}
