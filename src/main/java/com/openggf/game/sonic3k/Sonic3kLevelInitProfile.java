package com.openggf.game.sonic3k;

import com.openggf.game.AbstractLevelInitProfile;
import com.openggf.game.InitStep;
import com.openggf.game.InitialProcessSpritesLifecycle;
import com.openggf.game.LevelLoadContext;
import com.openggf.game.SidekickSpawnOffset;
import com.openggf.game.StaticFixup;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;

import java.util.List;
import com.openggf.game.GameServices;

/**
 * Sonic 3&K level initialization profile.
 * <p>
 * Aligned to the S3K {@code Level:} routine at {@code sonic3k.asm:7505} (65 steps
 * across phases A-Q). The teardown steps undo the state set up by that routine.
 * <p>
 * S3K-specific post-load characteristics:
 * <ul>
 *   <li>Sidekick X offset: -32px (ROM: {@code $20}), not S2's -40px</li>
 * </ul>
 */
public class Sonic3kLevelInitProfile extends AbstractLevelInitProfile {
    private final Sonic3kLevelEventManager levelEventManager;

    public Sonic3kLevelInitProfile(Sonic3kLevelEventManager levelEventManager) {
        this.levelEventManager = levelEventManager;
    }

    @Override
    public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
        List<InitStep> steps = buildCoreSteps(ctx);
        // Post-load assembly: only when requested
        if (ctx.isIncludePostLoadAssembly()) {
            steps.add(restoreCheckpointStep(ctx));
            steps.add(spawnPlayerStep(ctx));
            steps.add(resetPlayerStateStep(ctx));
            steps.add(initCameraStep());
            steps.add(initLevelEventsStep());
            steps.add(spawnSidekickStep());
            steps.add(initZonePlayerStateStep());
            steps.add(requestInitialProcessSpritesStep(ctx));
            if (!isPreviewCapture(ctx)) {
                steps.add(requestTitleCardStep(ctx));
            }
        }
        return List.copyOf(steps);
    }

    /**
     * ROM: SpawnLevelMainSprites zone-specific player state (sonic3k.asm:8132).
     * Runs after sidekick spawn so both main player and sidekicks exist.
     * Sets falling animation, airborne flag, and jumping for zone intros
     * (HCZ1, MGZ1, and LRZ1 non-Knuckles; SSZ has no corresponding branch).
     */
    protected InitStep initZonePlayerStateStep() {
        return new InitStep("InitZonePlayerState",
            "S3K: SpawnLevelMainSprites — zone-specific player animation/air state",
            levelEventManager::applyZonePlayerState);
    }

    @Override
    public InitialProcessSpritesLifecycle initialProcessSpritesLifecycle() {
        return InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE;
    }

    /**
     * ROM fresh-level assembly creates players and zone state before the
     * initial Load_Sprites/Process_Sprites setup sequence
     * (docs/skdisasm/sonic3k.asm:7849-7855, 7889-7906).
     */
    private InitStep requestInitialProcessSpritesStep(LevelLoadContext ctx) {
        return new InitStep("RequestInitialProcessSprites",
                "S3K: arm post-load Load_Sprites then Process_Sprites setup",
                () -> ctx.requestInitialProcessSpritesFromProfile(initialProcessSpritesLifecycle()));
    }

    /** S3K sidekick: -32px X, +4px Y (ROM: {@code player_pos - $20}, {@code player_pos + 4}). */
    @Override
    public SidekickSpawnOffset sidekickSpawnOffset() {
        return new SidekickSpawnOffset(-32, 4);
    }

    @Override
    public boolean preserveFreshGroundedStatusUntilFirstDispatch() {
        // ROM fresh-start frame 0 is already routine 2. Grounded control
        // selects Wait before Player_AnglePos detaches at the platform edge;
        // Animate_Sonic then publishes mapping $BA without an air-gravity tick
        // (sonic3k.asm:24740-24771; AniSonic05).
        return true;
    }

    @Override
    protected InitStep spawnSidekickStep() {
        return new InitStep("SpawnSidekick",
            "S3K: SpawnLevelMainSprites_SpawnPlayers — Tails at player_pos - $20, +4 Y",
            () -> {
                SidekickSpawnOffset offset = sidekickSpawnOffset();
                GameServices.level().spawnSidekicks(offset.xOffset(), offset.yOffset());
            });
    }

    /** S3K: title card request follows the normal post-load path. */
    @Override
    protected InitStep requestTitleCardStep(LevelLoadContext ctx) {
        return new InitStep("RequestTitleCard",
            "S3K: Obj_TitleCard",
            () -> GameServices.level().requestTitleCardIfNeeded(ctx));
    }

    @Override
    protected InitStep levelEventTeardownStep() {
        return new InitStep("ResetS3kLevelEvents",
                "Undoes S3K LevelSetupArray dispatch and per-zone event handlers (AIZ, HCZ, etc.)",
                levelEventManager::resetState);
    }

    @Override
    protected InitStep perTestLeadStep() {
        return new InitStep("ResetAizIntroPhaseState",
                "Resets all AizPlaneIntroInstance static phase state (scroll, terrain swap, decompression)",
                AizPlaneIntroInstance::resetIntroPhaseState);
    }

    @Override
    protected List<StaticFixup> gameSpecificFixups() {
        return List.of(
                new StaticFixup("ResetAizIntroPhaseState",
                        "AIZ intro phase state persists across level loads",
                        AizPlaneIntroInstance::resetIntroPhaseState)
        );
    }
}
