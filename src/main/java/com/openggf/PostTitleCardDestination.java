package com.openggf;

import com.openggf.level.LevelManager;

/**
 * Where to transition after a title card completes.
 * Normally LEVEL, but bonus stage entry routes through title card first.
 */
enum PostTitleCardDestination {
    /** Normal: title card -> LEVEL mode (default) */
    LEVEL,
    /** Bonus stage entry: title card -> BONUS_STAGE mode */
    BONUS_STAGE;

    /**
     * @param ranPreMainLoopObjectPass whether the release step already ran the
     *        title card's pre-{@code Level_MainLoop} object pass(es). Those
     *        passes are part of the level-init routine and run with no V-int of
     *        their own -- S1's {@code Level_LoadObj} {@code ExecuteObjects}
     *        (docs/s1disasm/sonic.asm:2895-2897) executes once between
     *        {@code Level_TtlCardLoop} and {@code Level_MainLoop}. The first
     *        recorded gameplay row belongs to {@code Level_MainLoop}'s own first
     *        iteration and its {@code id_VBlank_Levels} service (:2999-3003), so
     *        the release step must not consume it.
     */
    LevelFrameResult completeRelease(LevelManager levelManager, Runnable exitTitleCard,
                                     boolean ranPreMainLoopObjectPass) {
        boolean setupOnly = ranPreMainLoopObjectPass;
        if (this == LEVEL) {
            levelManager.completeInitialTitleCardPresentation();
            setupOnly |= levelManager.consumePendingInitialProcessSpritesPass();
        }
        exitTitleCard.run();
        return setupOnly ? LevelFrameResult.SETUP_ONLY : LevelFrameResult.GAMEPLAY_FRAME;
    }
}
