package com.openggf.game.session;

import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.sprites.managers.SpriteManager;

import java.util.Objects;

public final class EditorSessionFactory {

    public EditorModeContext create(WorldSession worldSession, EngineContext engineContext) {
        return create(worldSession, engineContext, new EditorCursorState(0, 0), null);
    }

    EditorModeContext create(WorldSession worldSession,
                             EngineContext engineContext,
                             EditorCursorState cursor,
                             EditorPlaytestStash playtestStash) {
        Objects.requireNonNull(worldSession, "worldSession");
        Objects.requireNonNull(engineContext, "engineContext");
        Objects.requireNonNull(cursor, "cursor");

        Camera editorCamera = new Camera();
        SpriteManager editorSprites = new SpriteManager();
        WaterSystem editorWater = new WaterSystem();
        ParallaxManager editorParallax = new ParallaxManager();
        TerrainCollisionManager editorTerrain = new TerrainCollisionManager();
        CollisionSystem editorCollision = new CollisionSystem(editorTerrain);
        GameStateManager editorGameState = new GameStateManager();
        LevelManager editorLevelManager = new LevelManager(
                editorCamera,
                editorSprites,
                editorParallax,
                editorCollision,
                editorWater,
                editorGameState,
                engineContext,
                worldSession);

        Level currentLevel = worldSession.getCurrentLevel();
        editorLevelManager.restoreEditorLevelView(currentLevel);
        if (currentLevel != null) {
            editorCamera.setMinX((short) currentLevel.getMinX());
            editorCamera.setMaxX((short) currentLevel.getMaxX());
            editorCamera.setMinY((short) currentLevel.getMinY());
            editorCamera.setMaxY((short) currentLevel.getMaxY());
        }

        return new EditorModeContext(worldSession, cursor, playtestStash,
                editorCamera, editorSprites, editorLevelManager,
                editorParallax, editorWater, editorTerrain, editorCollision, editorGameState);
    }
}
