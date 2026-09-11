package com.openggf.tests;

import com.openggf.game.session.GameplayModeContext;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.FrameCollisionPlan;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Tails;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Installs the two runtime-owned states that exposed missing consumer reset
 * boundaries in the full suite. Tests declare this extension immediately
 * before {@link SingletonResetExtension}; their behavior assertions then prove
 * the real reset callback replaced both session owners.
 */
public final class RuntimeStateContaminationExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        TestEnvironment.resetAll();
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();

        Tails leakedSidekick = new Tails(
                "leaked-sidekick", (short) 0, (short) 0);
        leakedSidekick.setCpuControlled(true);
        gameplayMode.getSpriteManager().addSprite(leakedSidekick, "tails");

        CollisionSystem leakedCollision = new CollisionSystem(
                gameplayMode.getTerrainCollisionManager()) {
            @Override
            public void resolveGroundWallCollision(
                    FrameCollisionPlan plan,
                    AbstractPlayableSprite sprite) {
                sprite.setGSpeed((short) 0);
            }
        };
        gameplayMode.attachLevelManagers(
                gameplayMode.getWaterSystem(),
                gameplayMode.getParallaxManager(),
                gameplayMode.getTerrainCollisionManager(),
                leakedCollision,
                gameplayMode.getSpriteManager(),
                gameplayMode.getLevelManager());
    }
}
