package com.openggf.game;

/**
 * Where the GAME OVER card sends the game once it is dismissed.
 *
 * <p>ROM {@code Over_Wait .changeMode} (docs/s1disasm/_incObj/39 Game
 * Over.asm:73-80), S2 {@code Obj39_Dismiss} (docs/s2disasm/s2.asm:27737-27748)
 * and S3K {@code loc_2D666} (docs/skdisasm/sonic3k.asm:62089-62096) all write
 * the continue-screen game mode first and downgrade it to the Sega screen when
 * {@code Continue_count} is zero.
 */
public enum GameOverExit {
    /** {@code GameMode_Continue}: at least one continue remains. */
    CONTINUE_SCREEN,
    /** {@code id_Sega} / {@code GameModeID_SegaScreen}: no continues remain. */
    TITLE_SCREEN
}
