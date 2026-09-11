package com.openggf.game;

import com.openggf.camera.Camera;
import com.openggf.graphics.FadeManager;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.timer.TimerManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared teardown and per-test reset logic for all game profiles.
 * <p>
 * Each Mega Drive Sonic game has a well-documented level initialization
 * routine ({@code Level:} in the disassembly). The teardown steps here
 * are the <em>inverse</em> of those ROM init phases — they undo what
 * the ROM's {@code Level:} routine sets up:
 * <table>
 *   <tr><th>Teardown step</th><th>Undoes ROM phase</th></tr>
 *   <tr><td>ResetAudio</td><td>Palette &amp; Music (S1 Phase D, S2 Phase C, S3K Phase F)</td></tr>
 *   <tr><td><em>levelEventTeardownStep</em></td><td>Zone-specific setup / title card state</td></tr>
 *   <tr><td>ResetParallax</td><td>DeformBgLayer / DeformLayers (S1 Phase G, S2 Phase E, S3K Phase H)</td></tr>
 *   <tr><td>ResetLevelManager</td><td>Level geometry loading (S1 Phase G, S2 Phase E, S3K Phase I)</td></tr>
 *   <tr><td>ResetSprites</td><td>Player &amp; object spawning (S1 Phase I-J, S2 Phase G, S3K Phase O)</td></tr>
 *   <tr><td>ResetCollision</td><td>Collision index loading (S1 Phase H, S2 Phase F, S3K Phase K)</td></tr>
 *   <tr><td>ResetCamera</td><td>Level boundaries / camera init (S1 Phase G, S2 Phase E, S3K Phase H)</td></tr>
 *   <tr><td>ResetGraphics</td><td>VDP / hardware setup (S1 Phase B, S2 Phase B, S3K Phase D)</td></tr>
 *   <tr><td>ResetFade</td><td>Palette fade state (S1 Phase A, S2 Phase A, S3K Phase A)</td></tr>
 *   <tr><td>ResetGameState</td><td>Game state init (S1 Phase K, S2 Phase H, S3K Phase N)</td></tr>
 *   <tr><td>ResetTimers</td><td>First-frame timing (S1 Phase J, S2 Phase I, S3K Phase P)</td></tr>
 *   <tr><td>ResetWater</td><td>Water initialization (S1 Phase C, S2 Phase B, S3K Phase E/L)</td></tr>
 * </table>
 * <p>
 * Per-test reset is a subset that clears transient gameplay state while
 * preserving loaded level data (geometry, art, collision indices).
 * <p>
 * Subclasses provide only the game-specific steps via three hooks:
 * {@link #levelEventTeardownStep()}, {@link #perTestLeadStep()}, and
 * {@link #gameSpecificFixups()}.
 *
 * @see <a href="docs/plans/2026-02-27-rom-driven-init-profiles-design.md">
 *      ROM-Driven Init Profiles Design (full ROM step-by-step reference)</a>
 */
public abstract class AbstractLevelInitProfile implements LevelInitProfile {

    /** Game-specific level event manager reset (teardown index 1). */
    protected abstract InitStep levelEventTeardownStep();

    /** Game-specific first step for per-test reset. */
    protected abstract InitStep perTestLeadStep();

    /** Game-specific post-teardown fixups. Override to add game-specific ones. */
    protected List<StaticFixup> gameSpecificFixups() {
        return List.of();
    }

    protected boolean isPreviewCapture(LevelLoadContext ctx) {
        return ctx != null && ctx.getLoadMode() == LevelLoadMode.PREVIEW_CAPTURE;
    }

    // ── IOException helper ───────────────────────────────────────────────

    /** Runnable that may throw {@link IOException}. */
    @FunctionalInterface
    protected interface IORunnable {
        void run() throws IOException;
    }

    /**
     * Creates an {@link InitStep} whose action wraps an {@link IORunnable},
     * converting any {@link IOException} to {@link UncheckedIOException}.
     */
    protected static InitStep ioStep(String name, String desc, IORunnable action) {
        return new InitStep(name, desc, () -> {
            try { action.run(); } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    // ── Shared core level-load steps ─────────────────────────────────────

    /** Resets runtime-owned managers only when a runtime is active. */
    private static void resetParallaxIfAvailable() {
        ParallaxManager manager = GameServices.parallaxOrNull();
        if (manager != null) manager.resetState();
    }

    private static void resetLevelManagerIfAvailable() {
        LevelManager manager = GameServices.levelOrNull();
        if (manager != null) manager.resetState();
    }

    private static void resetSpritesIfAvailable() {
        SpriteManager manager = GameServices.spritesOrNull();
        if (manager != null) manager.resetState();
    }

    private static void resetCollisionIfAvailable() {
        CollisionSystem manager = GameServices.collisionOrNull();
        if (manager != null) manager.resetState();
    }

    private static void resetCameraIfAvailable() {
        Camera manager = GameServices.cameraOrNull();
        if (manager != null) manager.resetState();
    }

    private static void resetFadeIfAvailable() {
        FadeManager manager = GameServices.fadeOrNull();
        if (manager != null) manager.resetState();
    }

    private static void resetGameStateIfAvailable() {
        GameStateManager manager = GameServices.gameStateOrNull();
        if (manager != null) manager.resetState();
    }

    private static void resetTimersIfAvailable() {
        TimerManager manager = GameServices.timersOrNull();
        if (manager != null) manager.resetState();
    }

    private static void resetWaterIfAvailable() {
        WaterSystem manager = GameServices.waterOrNull();
        if (manager != null) manager.reset();
    }

    /**
     * Re-derives the {@link WaterSystem} config from the already-loaded level.
     * <p>
     * The per-test reset clears {@code WaterSystem.waterConfigs} via
     * {@link #resetWaterIfAvailable()} but, unlike the full level-reload path,
     * does not re-run the {@code InitWater} load step. This restores the water
     * config exactly as the production level-load profile populates it (via
     * {@link LevelManager#initWater()}), so the engine evolves its water state
     * natively rather than running with water permanently disabled. No-op when
     * no level is loaded yet (e.g. the first reset before any level load).
     */
    private static void reloadWaterIfLevelLoaded() {
        LevelManager levelManager = GameServices.levelOrNull();
        if (levelManager == null || levelManager.getCurrentLevel() == null) {
            return;
        }
        try {
            levelManager.initWater();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Builds the 13 core level-load steps that are identical across all
     * three game profiles. Each step delegates to the corresponding
     * {@link LevelManager} method.
     * <p>
     * Order matches the ROM's {@code Level:} routine: module init, audio,
     * geometry, animation, objects, camera bounds, gameplay state, rings,
     * zone features, art, player/checkpoint, water, then background renderer.
     *
     * @param ctx the level-load context accumulated across steps
     * @return mutable list for callers to append post-load assembly steps
     */
    protected List<InitStep> buildCoreSteps(LevelLoadContext ctx) {
        List<InitStep> steps = new ArrayList<>(20);
        steps.add(ioStep("InitGameModule",
                "Create Game instance, fade out, clear PLC",
                () -> GameServices.level().initGameModule(ctx.getLevelIndex())));
        if (!isPreviewCapture(ctx)) {
            steps.add(ioStep("InitAudio",
                    "Play level music from zone playlist",
                    () -> GameServices.level().initAudio(ctx.getLevelIndex())));
        }
        steps.add(ioStep("LoadLevelData",
                "Load level geometry, tiles, collision indices",
                () -> ctx.setLevel(GameServices.level().loadLevelData(ctx.getLevelIndex()))));
        steps.add(new InitStep("InitAnimatedContent",
                "Pattern animation scripts and palette cycling",
                () -> GameServices.level().initAnimatedContent()));
        steps.add(ioStep("InitObjectSystem",
                "Create ObjectManager, wire CollisionSystem, reset camera bounds, register rewind adapters",
                () -> GameServices.level().initObjectSystem()));
        steps.add(new InitStep("InitGameplayState",
                "OscillateNumInit, clear game state, HUD update flags",
                () -> GameServices.level().initGameplayState()));
        steps.add(new InitStep("InitRings",
                "Initial ring placement and pattern caching",
                () -> GameServices.level().initRings()));
        steps.add(ioStep("InitZoneFeatures",
                "Zone-specific features (water surface, bumpers, etc.)",
                () -> GameServices.level().initZoneFeatures()));
        steps.add(new InitStep("InitArt",
                "Zone PLC, character art, shared HUD/ring/monitor patterns",
                () -> GameServices.level().initArt()));
        steps.add(new InitStep("InitPlayerAndCheckpoint",
                "Player spawn state and checkpoint clear",
                () -> GameServices.level().initPlayerAndCheckpoint()));
        steps.add(ioStep("InitWater",
                "Water system loading for water zones",
                () -> GameServices.level().initWater()));
        steps.add(new InitStep("InitBackgroundRenderer",
                "Engine-specific: pre-allocate BG FBO",
                () -> GameServices.level().initBackgroundRenderer()));
        return steps;
    }

    // Not final: subclasses provide game-specific level load steps.
    @Override
    public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
        return List.of();
    }

    // ── Post-load assembly step factories ────────────────────────────────
    // Steps 14-20: post-resource-load assembly (checkpoint, player spawn,
    // state reset, camera, level events, sidekick, title card).
    // Subclasses override individual factories for game-specific behavior.

    /** Step 14: Restore checkpoint state after loadLevel() clears it. */
    protected InitStep restoreCheckpointStep(LevelLoadContext ctx) {
        return new InitStep("RestoreCheckpoint",
            "S1: Lamp_LoadInfo, S2: Obj79_LoadData, S3K: Saved_zone_and_act restore",
            () -> GameServices.level().restoreCheckpointState(ctx));
    }

    /** Step 15: Set player position from checkpoint or level start. */
    protected InitStep spawnPlayerStep(LevelLoadContext ctx) {
        return new InitStep("SpawnPlayer",
            "S1/S2: StartLocations / Obj79_LoadData, S3K: Get_PlayerStart",
            () -> GameServices.level().spawnPlayerAtStartPosition(ctx));
    }

    /** Step 16: Reset player state for level start. */
    protected InitStep resetPlayerStateStep(LevelLoadContext ctx) {
        return new InitStep("ResetPlayerState",
            "S2: InitPlayers state clear, S3K: object constructor defaults",
            () -> GameServices.level().resetPlayerForLevelStart(ctx));
    }

    /** Step 17: Initialize camera for level start. */
    protected InitStep initCameraStep() {
        return new InitStep("InitCamera",
            "S1/S2: SetScreen/InitCameraValues, S3K: Get_LevelSizeStart",
            () -> GameServices.level().initCameraForLevel());
    }

    /** Step 18: Initialize level events for dynamic boundary updates. */
    protected InitStep initLevelEventsStep() {
        return new InitStep("InitLevelEvents",
            "All: LevelEventProvider.initLevel(zone, act)",
            () -> GameServices.level().initLevelEventsForLevel());
    }

    /** Step 19: Spawn sidekick near the main player. Override for game-specific offset. */
    protected InitStep spawnSidekickStep() {
        return new InitStep("SpawnSidekick",
            "S2: InitPlayers multi-char, S3K: SpawnLevelMainSprites_SpawnPlayers",
            () -> {
                SidekickSpawnOffset offset = sidekickSpawnOffset();
                GameServices.level().spawnSidekicks(offset.xOffset(), offset.yOffset());
            });
    }

    /** Step 20: Request title card display. */
    protected InitStep requestTitleCardStep(LevelLoadContext ctx) {
        return new InitStep("RequestTitleCard",
            "S1/S2: title card loop, S3K: Obj_TitleCard",
            () -> GameServices.level().requestTitleCardIfNeeded(ctx));
    }

    /**
     * Returns the standard 7 post-load assembly steps (14-20).
     * Subclasses can call this and filter/modify as needed.
     */
    protected List<InitStep> postLoadAssemblySteps(LevelLoadContext ctx) {
        List<InitStep> steps = new ArrayList<>(7);
        steps.add(restoreCheckpointStep(ctx));
        steps.add(spawnPlayerStep(ctx));
        steps.add(resetPlayerStateStep(ctx));
        steps.add(initCameraStep());
        steps.add(initLevelEventsStep());
        steps.add(spawnSidekickStep());
        if (!isPreviewCapture(ctx)) {
            steps.add(requestTitleCardStep(ctx));
        }
        return List.copyOf(steps);
    }

    @Override
    public final List<InitStep> levelTeardownSteps() {
        return List.of(
            // Undoes S1:Phase D / S2:Phase C / S3K:Phase F (PlayMusic)
            new InitStep("ResetAudio", "Undoes PlayMusic / bgm_Fade",
                () -> GameServices.audio().resetState()),

            new InitStep("ResetCrossGameFeatures", "Undoes CrossGameFeatureProvider.initialize()",
                () -> GameServices.crossGameFeatures().resetState()),

            // Game-specific: undoes zone event handlers, boss arena state
            levelEventTeardownStep(),

            // Undoes S1:Phase G / S2:Phase E / S3K:Phase H (DeformBgLayer/DeformLayers)
            new InitStep("ResetParallax", "Undoes DeformBgLayer init",
                AbstractLevelInitProfile::resetParallaxIfAvailable),
            // Undoes S1:Phase G / S2:Phase E / S3K:Phase I (LevelDataLoad/LoadZoneTiles)
            new InitStep("ResetLevelManager", "Undoes LevelDataLoad / LoadZoneTiles / LoadLevelLoadBlock",
                AbstractLevelInitProfile::resetLevelManagerIfAvailable),

            // Undoes S1:Phase I-J / S2:Phase G / S3K:Phase O (InitPlayers/SpawnLevelMainSprites)
            new InitStep("ResetSprites", "Undoes InitPlayers / SpawnLevelMainSprites",
                AbstractLevelInitProfile::resetSpritesIfAvailable),

            // Undoes S1:Phase H / S2:Phase F / S3K:Phase K (ConvertCollisionArray/LoadSolids)
            new InitStep("ResetCollision", "Undoes ConvertCollisionArray / LoadCollisionIndexes / LoadSolids",
                AbstractLevelInitProfile::resetCollisionIfAvailable),

            // Undoes S1:Phase G / S2:Phase E / S3K:Phase H (LevelSizeLoad/Get_LevelSizeStart)
            new InitStep("ResetCamera", "Undoes LevelSizeLoad / Get_LevelSizeStart",
                AbstractLevelInitProfile::resetCameraIfAvailable),
            // Undoes S1:Phase B / S2:Phase B / S3K:Phase D (VDP register config)
            new InitStep("ResetGraphics", "Undoes VDP register / ClearScreen / Clear_DisplayData",
                () -> GameServices.graphics().resetState()),
            // Undoes S1:Phase A / S2:Phase A / S3K:Phase A (PaletteFadeOut/Pal_FadeToBlack)
            new InitStep("ResetFade", "Undoes PaletteFadeOut / Pal_FadeToBlack",
                AbstractLevelInitProfile::resetFadeIfAvailable),

            // Undoes S1:Phase K / S2:Phase H / S3K:Phase N (game state clear)
            new InitStep("ResetGameState", "Undoes ring/timer/lives init from Level:",
                AbstractLevelInitProfile::resetGameStateIfAvailable),
            // Undoes S1:Phase J / S2:Phase I / S3K:Phase P (first frame timing)
            new InitStep("ResetTimers", "Undoes Level_frame_counter / demo timer",
                AbstractLevelInitProfile::resetTimersIfAvailable),
            // Undoes S1:Phase C / S2:Phase B / S3K:Phase E,L (water init)
            new InitStep("ResetWater", "Undoes LZWaterFeatures / WaterEffects / Handle_Onscreen_Water_Height",
                AbstractLevelInitProfile::resetWaterIfAvailable),

            new InitStep("ResetDebugOverlay", "Clears overlay toggle states and pending debug text",
                () -> GameServices.debugOverlay().resetState())
        );
    }

    @Override
    public final List<InitStep> perTestResetSteps() {
        return List.of(
            perTestLeadStep(),

            new InitStep("ResetCrossGameFeatures", "Undoes CrossGameFeatureProvider.initialize()",
                () -> GameServices.crossGameFeatures().resetState()),

            new InitStep("ResetParallax", "Undoes DeformBgLayer init",
                AbstractLevelInitProfile::resetParallaxIfAvailable),
            new InitStep("ResetSprites", "Undoes InitPlayers / SpawnLevelMainSprites",
                AbstractLevelInitProfile::resetSpritesIfAvailable),
            new InitStep("ResetCollision", "Undoes ConvertCollisionArray / LoadSolids",
                AbstractLevelInitProfile::resetCollisionIfAvailable),
            new InitStep("ResetCamera", "Undoes LevelSizeLoad / Get_LevelSizeStart",
                AbstractLevelInitProfile::resetCameraIfAvailable),
            new InitStep("ResetFade", "Undoes PaletteFadeOut / Pal_FadeToBlack",
                AbstractLevelInitProfile::resetFadeIfAvailable),
            new InitStep("ResetGameState", "Undoes ring/timer/lives init from Level:",
                AbstractLevelInitProfile::resetGameStateIfAvailable),
            new InitStep("ResetTimers", "Undoes Level_frame_counter / demo timer",
                AbstractLevelInitProfile::resetTimersIfAvailable),
            new InitStep("ResetWater", "Undoes LZWaterFeatures / WaterEffects / Handle_Onscreen_Water_Height",
                AbstractLevelInitProfile::resetWaterIfAvailable),
            // ResetWater clears WaterSystem.waterConfigs (the level-load-time water
            // config), but unlike levelTeardownSteps (which is followed by a full
            // level reload), the per-test reset reuses the already-loaded Level
            // without re-running the InitWater load step. Without this reload the
            // water config stays empty, so WaterSystem.hasWater() returns false and
            // the per-frame Sonic_Water / Tails_Water path (ROM Obj01_InWater /
            // Obj02_InWater, docs/s2disasm/s2.asm:36369-36393, 39528-39556) never
            // fires. That silently disabled the ARZ2 sidekick water-entry velocity
            // reduction (asr x_vel once / y_vel twice) in trace replay. Re-derive the
            // water config from the loaded level here, exactly as the production
            // level-load profile does, so the engine evolves natively.
            new InitStep("ReloadWater", "Re-derives WaterSystem config from the loaded level (mirrors InitWater)",
                AbstractLevelInitProfile::reloadWaterIfLevelLoaded),
            new InitStep("ResetDebugOverlay", "Clears overlay toggle states and pending debug text",
                () -> GameServices.debugOverlay().resetState())
        );
    }

    @Override
    public final List<StaticFixup> postTeardownFixups() {
        return gameSpecificFixups();
    }

    /** Empty profile used as safe default by {@link GameModule#getLevelInitProfile()}. */
    public static final LevelInitProfile EMPTY = new LevelInitProfile() {
        @Override public List<InitStep> levelLoadSteps(LevelLoadContext ctx) { return List.of(); }
        @Override public List<InitStep> levelTeardownSteps() { return List.of(); }
        @Override public List<InitStep> perTestResetSteps() { return List.of(); }
        @Override public List<StaticFixup> postTeardownFixups() { return List.of(); }
    };
}
