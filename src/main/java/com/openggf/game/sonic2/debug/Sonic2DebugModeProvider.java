package com.openggf.game.sonic2.debug;

import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManager;
import com.openggf.game.DebugModeProvider;

/**
 * Debug mode provider for Sonic 2.
 * Provides access to special stage debug features.
 */
public class Sonic2DebugModeProvider implements DebugModeProvider {
    private final Sonic2SpecialStageDebugController specialStageDebug;

    public Sonic2DebugModeProvider() {
        this(createSpecialStageGraph());
    }

    public Sonic2DebugModeProvider(Sonic2SpecialStageManager manager,
                                   Sonic2SpecialStageSpriteDebug spriteDebug) {
        this.specialStageDebug = new Sonic2SpecialStageDebugController(manager, spriteDebug);
    }

    private Sonic2DebugModeProvider(SpecialStageGraph graph) {
        this(graph.manager(), graph.spriteDebug());
    }

    private static SpecialStageGraph createSpecialStageGraph() {
        Sonic2SpecialStageSpriteDebug spriteDebug = new Sonic2SpecialStageSpriteDebug();
        return new SpecialStageGraph(new Sonic2SpecialStageManager(spriteDebug), spriteDebug);
    }

    @Override
    public boolean hasSpecialStageDebug() {
        return true;
    }

    @Override
    public SpecialStageDebugController getSpecialStageDebugController() {
        return specialStageDebug;
    }

    @Override
    public boolean hasLevelDebug() {
        // Level debug is handled by the generic DebugRenderer
        return false;
    }

    /**
     * Sonic 2 special stage debug controller implementation.
     * Wraps the Sonic2SpecialStageManager debug methods.
     */
    public static class Sonic2SpecialStageDebugController implements SpecialStageDebugController {
        private final Sonic2SpecialStageManager manager;
        private final Sonic2SpecialStageSpriteDebug spriteDebug;

        public Sonic2SpecialStageDebugController(Sonic2SpecialStageManager manager,
                                                 Sonic2SpecialStageSpriteDebug spriteDebug) {
            this.manager = manager;
            this.spriteDebug = spriteDebug;
        }

        @Override
        public void toggleAlignmentTestMode() {
            manager.toggleAlignmentTestMode();
        }

        @Override
        public boolean isAlignmentTestMode() {
            return manager.isAlignmentTestMode();
        }

        @Override
        public void toggleSpriteDebugMode() {
            manager.toggleSpriteDebugMode();
        }

        @Override
        public boolean isSpriteDebugMode() {
            return manager.isSpriteDebugMode();
        }

        @Override
        public void cyclePlaneDebugMode() {
            manager.cyclePlaneDebugMode();
        }

        @Override
        public void adjustAlignmentOffset(int delta) {
            manager.adjustAlignmentOffset(delta);
        }

        @Override
        public void adjustAlignmentSpeed(double delta) {
            manager.adjustAlignmentSpeed(delta);
        }

        @Override
        public void toggleAlignmentStepMode() {
            manager.toggleAlignmentStepMode();
        }

        @Override
        public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) {
            manager.renderAlignmentOverlay(viewportWidth, viewportHeight);
        }

        @Override
        public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) {
            // S2 does not advertise a live lag diagnostic.
        }

        @Override
        public void renderSpriteDebug() {
            spriteDebug.draw();
        }

        @Override
        public void handleSpriteDebugInput(boolean leftPressed, boolean rightPressed,
                                           boolean upPressed, boolean downPressed) {
            if (rightPressed) {
                spriteDebug.nextPage();
            }
            if (leftPressed) {
                spriteDebug.previousPage();
            }
            if (downPressed) {
                spriteDebug.nextSet();
            }
            if (upPressed) {
                spriteDebug.previousSet();
            }
        }

        public Sonic2SpecialStageManager getManager() {
            return manager;
        }

        public Sonic2SpecialStageSpriteDebug getSpriteDebug() {
            return spriteDebug;
        }
    }

    private record SpecialStageGraph(
            Sonic2SpecialStageManager manager,
            Sonic2SpecialStageSpriteDebug spriteDebug) {
    }
}
