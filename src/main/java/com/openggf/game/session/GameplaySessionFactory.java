package com.openggf.game.session;

import com.openggf.camera.Camera;
import com.openggf.game.GameModule;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.graphics.FadeManager;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.timer.TimerManager;

import java.util.Objects;

/**
 * Session-owned composition root for disposable gameplay managers.
 */
public final class GameplaySessionFactory {

    private GameplaySessionFactory() {
    }

    public static void attachManagers(GameplayModeContext gameplayMode,
                                      EngineContext services) {
        Objects.requireNonNull(gameplayMode, "gameplayMode");
        Objects.requireNonNull(services, "services");

        Camera camera = new Camera();
        TimerManager timers = new TimerManager();
        GameStateManager gameState = new GameStateManager();
        GameModule sessionModule = gameplayMode.getWorldSession() != null
                ? gameplayMode.getWorldSession().getGameModule()
                : null;
        if (sessionModule != null) {
            gameState.configureSpecialStageProgress(
                    sessionModule.getSpecialStageCycleCount(),
                    sessionModule.getChaosEmeraldCount());
        }
        FadeManager fadeManager = new FadeManager();
        GameRng rng = new GameRng(sessionModule != null
                ? sessionModule.rngFlavour()
                : GameRng.Flavour.S1_S2);
        SolidExecutionRegistry solidExecutionRegistry = new DefaultSolidExecutionRegistry();
        gameplayMode.attachGameplayManagers(camera, timers, gameState, fadeManager,
                rng, solidExecutionRegistry, services.profiler(), services.audio());

        WaterSystem waterSystem = new WaterSystem();
        ParallaxManager parallaxManager = new ParallaxManager();
        TerrainCollisionManager terrainCollisionManager = new TerrainCollisionManager();
        CollisionSystem collisionSystem = new CollisionSystem(terrainCollisionManager);
        SpriteManager spriteManager = new SpriteManager();
        LevelManager levelManager = new LevelManager(
                camera, spriteManager, parallaxManager, collisionSystem, waterSystem, gameState, services,
                gameplayMode.getWorldSession());
        gameplayMode.attachLevelManagers(waterSystem, parallaxManager, terrainCollisionManager,
                collisionSystem, spriteManager, levelManager);

        ZoneRuntimeRegistry zoneRuntimeRegistry = new ZoneRuntimeRegistry();
        PaletteOwnershipRegistry paletteOwnershipRegistry = new PaletteOwnershipRegistry();
        AnimatedTileChannelGraph animatedTileChannelGraph = new AnimatedTileChannelGraph();
        SpecialRenderEffectRegistry specialRenderEffectRegistry = new SpecialRenderEffectRegistry();
        AdvancedRenderModeController advancedRenderModeController = new AdvancedRenderModeController();
        ZoneLayoutMutationPipeline zoneLayoutMutationPipeline = new ZoneLayoutMutationPipeline();
        gameplayMode.attachSharedRegistries(zoneRuntimeRegistry, paletteOwnershipRegistry,
                animatedTileChannelGraph, specialRenderEffectRegistry,
                advancedRenderModeController, zoneLayoutMutationPipeline);
    }
}
