package com.openggf.render;

import com.openggf.debug.DebugState;
import com.openggf.game.GameMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestEngineRenderDispatcher {

    @Test
    void clearColorRoutingPreservesModeSpecificSpecialCases() {
        EngineRenderDispatcher dispatcher = new EngineRenderDispatcher();
        RecordingClearActions actions = new RecordingClearActions();

        dispatcher.applyClearColor(GameMode.SPECIAL_STAGE, actions);
        dispatcher.applyClearColor(GameMode.SPECIAL_STAGE_RESULTS, actions);
        dispatcher.applyClearColor(GameMode.TITLE_SCREEN, actions);
        dispatcher.applyClearColor(GameMode.LEVEL_SELECT, actions);
        dispatcher.applyClearColor(GameMode.DATA_SELECT, actions);
        dispatcher.applyClearColor(GameMode.ENDING_CUTSCENE, actions);
        dispatcher.applyClearColor(GameMode.TRY_AGAIN_END, actions);
        dispatcher.applyClearColor(GameMode.MASTER_TITLE_SCREEN, actions);
        dispatcher.applyClearColor(GameMode.LEGAL_DISCLAIMER, actions);
        dispatcher.applyClearColor(GameMode.EDITOR, actions);
        dispatcher.applyClearColor(GameMode.TITLE_CARD, actions);
        dispatcher.applyClearColor(GameMode.LEVEL, actions);

        assertEquals(List.of(
                "specialStage",
                "results",
                "title",
                "levelSelect",
                "dataSelect",
                "ending",
                "black",
                "black",
                "black",
                "black",
                "level",
                "level"
        ), actions.calls);
    }

    @Test
    void continueUsesBlackScreenAndOwnRendererEvenWithDebugEnabled() {
        var dispatcher = new EngineRenderDispatcher();
        var clear = new RecordingClearActions();
        var draw = new RecordingDrawActions();
        dispatcher.applyClearColor(GameMode.CONTINUE_SCREEN, clear);
        dispatcher.draw(GameMode.CONTINUE_SCREEN, true, DebugState.PATTERNS_VIEW, draw);
        assertEquals(List.of("black"), clear.calls);
        assertEquals(List.of("continueScreen"), draw.calls);
    }

    @Test
    void nullModeClearColorFallsBackToLevelClearColor() {
        EngineRenderDispatcher dispatcher = new EngineRenderDispatcher();
        RecordingClearActions actions = new RecordingClearActions();

        dispatcher.applyClearColor(null, actions);

        assertEquals(List.of("level"), actions.calls);
    }

    @Test
    void drawRoutingPreservesModeSpecificSpecialCasesAndDebugViews() {
        EngineRenderDispatcher dispatcher = new EngineRenderDispatcher();
        RecordingDrawActions actions = new RecordingDrawActions();

        dispatcher.draw(GameMode.LEGAL_DISCLAIMER, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.MASTER_TITLE_SCREEN, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.EDITOR, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.SPECIAL_STAGE, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.SPECIAL_STAGE_RESULTS, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.TITLE_SCREEN, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.LEVEL_SELECT, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.DATA_SELECT, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.ENDING_CUTSCENE, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.CREDITS_TEXT, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.CREDITS_DEMO, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.TRY_AGAIN_END, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.TITLE_CARD, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.LEVEL, false, DebugState.NONE, actions);
        dispatcher.draw(GameMode.LEVEL, true, DebugState.PATTERNS_VIEW, actions);
        dispatcher.draw(GameMode.LEVEL, true, DebugState.BLOCKS_VIEW, actions);
        dispatcher.draw(GameMode.LEVEL, true, DebugState.NONE, actions);

        assertEquals(List.of(
                "legalDisclaimer",
                "masterTitle",
                "editor",
                "specialStage",
                "specialStageResults",
                "titleScreen",
                "levelSelect",
                "dataSelect",
                "endingCutscene",
                "creditsText",
                "creditsDemo",
                "tryAgainEnd",
                "titleCard",
                "level",
                "debugPatterns",
                "debugBlocks",
                "level"
        ), actions.calls);
    }

    @Test
    void specialStageEntryFadeShowsTheLevelUntilTheStageOwnsTheFrame() {
        EngineRenderDispatcher dispatcher = new EngineRenderDispatcher();
        RecordingClearActions clear = new RecordingClearActions();
        RecordingDrawActions draw = new RecordingDrawActions();

        dispatcher.applyClearColor(GameMode.SPECIAL_STAGE, true, clear);
        dispatcher.applyClearColor(GameMode.SPECIAL_STAGE, false, clear);
        dispatcher.applyClearColor(GameMode.LEVEL, true, clear);
        dispatcher.draw(GameMode.SPECIAL_STAGE, true, false, DebugState.NONE, draw);
        dispatcher.draw(GameMode.SPECIAL_STAGE, true, true, DebugState.PATTERNS_VIEW, draw);
        dispatcher.draw(GameMode.SPECIAL_STAGE, false, false, DebugState.NONE, draw);
        dispatcher.draw(GameMode.SPECIAL_STAGE_RESULTS, true, false, DebugState.NONE, draw);

        assertEquals(List.of("level", "specialStage", "level"), clear.calls);
        assertEquals(List.of("level", "debugPatterns", "specialStage", "specialStageResults"),
                draw.calls);
    }

    @Test
    void nullModeDrawFallsBackToLevelDraw() {
        EngineRenderDispatcher dispatcher = new EngineRenderDispatcher();
        RecordingDrawActions actions = new RecordingDrawActions();

        dispatcher.draw(null, false, DebugState.NONE, actions);

        assertEquals(List.of("level"), actions.calls);
    }

    static final class RecordingClearActions implements EngineRenderDispatcher.ClearActions {
        final List<String> calls = new ArrayList<>();

        @Override public void specialStage() { calls.add("specialStage"); }
        @Override public void specialStageResults() { calls.add("results"); }
        @Override public void titleScreen() { calls.add("title"); }
        @Override public void levelSelect() { calls.add("levelSelect"); }
        @Override public void dataSelect() { calls.add("dataSelect"); }
        @Override public void ending() { calls.add("ending"); }
        @Override public void black() { calls.add("black"); }
        @Override public void level() { calls.add("level"); }
    }

    static final class RecordingDrawActions implements EngineRenderDispatcher.DrawActions {
        final List<String> calls = new ArrayList<>();

        @Override public void legalDisclaimer() { calls.add("legalDisclaimer"); }
        @Override public void nativeModNotice() { calls.add("nativeModNotice"); }
        @Override public void masterTitle() { calls.add("masterTitle"); }
        @Override public void editor() { calls.add("editor"); }
        @Override public void specialStage() { calls.add("specialStage"); }
        @Override public void specialStageResults() { calls.add("specialStageResults"); }
        @Override public void titleScreen() { calls.add("titleScreen"); }
        @Override public void continueScreen() { calls.add("continueScreen"); }
        @Override public void levelSelect() { calls.add("levelSelect"); }
        @Override public void dataSelect() { calls.add("dataSelect"); }
        @Override public void endingCutscene() { calls.add("endingCutscene"); }
        @Override public void creditsText() { calls.add("creditsText"); }
        @Override public void creditsDemo() { calls.add("creditsDemo"); }
        @Override public void tryAgainEnd() { calls.add("tryAgainEnd"); }
        @Override public void titleCard() { calls.add("titleCard"); }
        @Override public void debugPatterns() { calls.add("debugPatterns"); }
        @Override public void debugBlocks() { calls.add("debugBlocks"); }
        @Override public void level() { calls.add("level"); }
    }
}
