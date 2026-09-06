package com.openggf.render;

import com.openggf.debug.DebugState;
import com.openggf.game.GameMode;

import java.util.Objects;

public final class EngineRenderDispatcher {

    /**
     * Clear-colour routing with the special-stage entry override: while the
     * stage is still inside the ROM's entry fade-to-white
     * ({@code SpecialStageProvider#isEntryFadeToWhiteActive}) the level's last
     * frame is what the display shows, so the level's clear colour applies.
     */
    public void applyClearColor(GameMode mode, boolean specialStageShowsLevel,
                                ClearActions actions) {
        Objects.requireNonNull(actions, "actions");
        if (mode == GameMode.SPECIAL_STAGE && specialStageShowsLevel) {
            actions.level();
            return;
        }
        applyClearColor(mode, actions);
    }

    public void applyClearColor(GameMode mode, ClearActions actions) {
        Objects.requireNonNull(actions, "actions");
        if (mode == null) {
            actions.level();
            return;
        }
        switch (mode) {
            case SPECIAL_STAGE -> actions.specialStage();
            case SPECIAL_STAGE_RESULTS -> actions.specialStageResults();
            case TITLE_SCREEN -> actions.titleScreen();
            case LEVEL_SELECT -> actions.levelSelect();
            case DATA_SELECT -> actions.dataSelect();
            case CREDITS_TEXT, ENDING_CUTSCENE -> actions.ending();
            case CONTINUE_SCREEN, TRY_AGAIN_END, MASTER_TITLE_SCREEN, LEGAL_DISCLAIMER, EDITOR -> actions.black();
            case TITLE_CARD -> actions.level();
            default -> actions.level();
        }
    }

    /**
     * Draw routing with the special-stage entry override: see
     * {@link #applyClearColor(GameMode, boolean, ClearActions)}. The frozen
     * level is drawn through the ordinary level path, debug views included.
     */
    public void draw(GameMode mode, boolean specialStageShowsLevel, boolean debugViewEnabled,
                     DebugState debugState, DrawActions actions) {
        Objects.requireNonNull(actions, "actions");
        if (mode == GameMode.SPECIAL_STAGE && specialStageShowsLevel) {
            drawLevel(debugViewEnabled, debugState, actions);
            return;
        }
        draw(mode, debugViewEnabled, debugState, actions);
    }

    public void draw(GameMode mode, boolean debugViewEnabled, DebugState debugState, DrawActions actions) {
        Objects.requireNonNull(actions, "actions");
        if (mode == null) {
            drawLevel(debugViewEnabled, debugState, actions);
            return;
        }
        switch (mode) {
            case LEGAL_DISCLAIMER -> actions.legalDisclaimer();
            case MASTER_TITLE_SCREEN -> actions.masterTitle();
            case EDITOR -> actions.editor();
            case SPECIAL_STAGE -> actions.specialStage();
            case SPECIAL_STAGE_RESULTS -> actions.specialStageResults();
            case TITLE_SCREEN -> actions.titleScreen();
            case LEVEL_SELECT -> actions.levelSelect();
            case DATA_SELECT -> actions.dataSelect();
            case CONTINUE_SCREEN -> actions.continueScreen();
            case ENDING_CUTSCENE -> actions.endingCutscene();
            case CREDITS_TEXT -> actions.creditsText();
            case CREDITS_DEMO -> actions.creditsDemo();
            case TRY_AGAIN_END -> actions.tryAgainEnd();
            case TITLE_CARD -> actions.titleCard();
            default -> drawLevel(debugViewEnabled, debugState, actions);
        }
    }

    private void drawLevel(boolean debugViewEnabled, DebugState debugState, DrawActions actions) {
        if (!debugViewEnabled) {
            actions.level();
            return;
        }
        switch (debugState) {
            case PATTERNS_VIEW -> actions.debugPatterns();
            case BLOCKS_VIEW -> actions.debugBlocks();
            case null, default -> actions.level();
        }
    }

    public interface ClearActions {
        void specialStage();
        void specialStageResults();
        void titleScreen();
        void levelSelect();
        void dataSelect();
        void ending();
        void black();
        void level();
    }

    public interface DrawActions {
        void continueScreen();
        void legalDisclaimer();
        void masterTitle();
        void editor();
        void specialStage();
        void specialStageResults();
        void titleScreen();
        void levelSelect();
        void dataSelect();
        void endingCutscene();
        void creditsText();
        void creditsDemo();
        void tryAgainEnd();
        void titleCard();
        void debugPatterns();
        void debugBlocks();
        void level();
    }
}
