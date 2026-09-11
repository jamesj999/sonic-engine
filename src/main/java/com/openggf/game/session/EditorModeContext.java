package com.openggf.game.session;

import com.openggf.camera.Camera;
import com.openggf.game.GameMode;
import com.openggf.game.GameStateManager;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.sprites.managers.SpriteManager;

import java.util.Objects;

public final class EditorModeContext implements ModeContext {
    private final WorldSession worldSession;
    private EditorCursorState cursor;
    private final EditorPlaytestStash playtestStash;
    private final Camera camera;
    private final SpriteManager spriteManager;
    private final LevelManager levelManager;
    private final ParallaxManager parallaxManager;
    private final WaterSystem waterSystem;
    private final TerrainCollisionManager terrainCollisionManager;
    private final CollisionSystem collisionSystem;
    private final GameStateManager gameStateManager;

    public EditorModeContext(WorldSession worldSession, EditorCursorState cursor) {
        this(worldSession, cursor, null);
    }

    public EditorModeContext(WorldSession worldSession, EditorCursorState cursor, EditorPlaytestStash playtestStash) {
        this(worldSession, cursor, playtestStash, null, null, null,
                null, null, null, null, null);
    }

    EditorModeContext(WorldSession worldSession,
                      EditorCursorState cursor,
                      EditorPlaytestStash playtestStash,
                      Camera camera,
                      SpriteManager spriteManager,
                      LevelManager levelManager,
                      ParallaxManager parallaxManager,
                      WaterSystem waterSystem,
                      TerrainCollisionManager terrainCollisionManager,
                      CollisionSystem collisionSystem,
                      GameStateManager gameStateManager) {
        this.worldSession = Objects.requireNonNull(worldSession, "worldSession");
        this.cursor = Objects.requireNonNull(cursor, "cursor");
        this.playtestStash = playtestStash;
        this.camera = camera;
        this.spriteManager = spriteManager;
        this.levelManager = levelManager;
        this.parallaxManager = parallaxManager;
        this.waterSystem = waterSystem;
        this.terrainCollisionManager = terrainCollisionManager;
        this.collisionSystem = collisionSystem;
        this.gameStateManager = gameStateManager;
    }

    public WorldSession getWorldSession() {
        return worldSession;
    }

    public EditorCursorState getCursor() {
        return cursor;
    }

    public void setCursor(EditorCursorState cursor) {
        this.cursor = Objects.requireNonNull(cursor, "cursor");
    }

    public EditorPlaytestStash getPlaytestStash() {
        return playtestStash;
    }

    public boolean hasPlaytestStash() {
        return playtestStash != null;
    }

    public boolean isEditorRuntimeReady() {
        return camera != null
                && spriteManager != null
                && levelManager != null;
    }

    public Camera getCamera() {
        return camera;
    }

    public SpriteManager getSpriteManager() {
        return spriteManager;
    }

    public LevelManager getLevelManager() {
        return levelManager;
    }

    @Override
    public GameMode getGameMode() {
        return GameMode.EDITOR;
    }

    @Override
    public void destroy() {
        if (levelManager != null) {
            levelManager.resetGameplayState();
        }
        if (spriteManager != null) {
            spriteManager.resetState();
        }
        if (collisionSystem != null) {
            collisionSystem.resetState();
        }
        if (terrainCollisionManager != null) {
            terrainCollisionManager.resetState();
        }
        if (parallaxManager != null) {
            parallaxManager.resetState();
        }
        if (waterSystem != null) {
            waterSystem.reset();
        }
        if (gameStateManager != null) {
            gameStateManager.resetState();
        }
        if (camera != null) {
            camera.resetState();
        }
    }
}
