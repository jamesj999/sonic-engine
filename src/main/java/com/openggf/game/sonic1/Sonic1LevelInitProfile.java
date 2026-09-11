package com.openggf.game.sonic1;

import com.openggf.game.AbstractLevelInitProfile;
import com.openggf.game.InitStep;
import com.openggf.game.LevelLoadContext;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.events.Sonic1LevelEventManager;
import com.openggf.game.sonic1.objects.Sonic1StomperDoorObjectInstance;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.SkippedPresentationPlcLifecycle;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.game.sonic1.constants.Sonic1Constants;

import java.util.List;
import java.io.IOException;

/**
 * Sonic 1 level initialization profile.
 * <p>
 * Aligned to the S1 {@code Level:} routine at {@code sonic.asm:2956} (44 steps
 * across phases A-L). The teardown steps undo the state set up by that routine.
 * <p>
 * S1-specific characteristics:
 * <ul>
 *   <li>Direct Nemesis decompression for title card art (no PLC queue)</li>
 *   <li>Unified collision model (single index, no dual-path switching)</li>
 *   <li>Single-phase data load ({@code LevelDataLoad} — all geometry in one call)</li>
 *   <li>Water only in LZ/SBZ3 ({@code LZWaterFeatures})</li>
 *   <li>Object placement BEFORE game state clear (phases J→K)</li>
 *   <li>Unique 4-frame VBla delay before fade-in (phase L, step 41)</li>
 *   <li>No sidekick (Tails) — step 19 (SpawnSidekick) omitted</li>
 * </ul>
 *
 * @see <a href="docs/plans/2026-02-27-rom-driven-init-profiles-design.md">
 *      Design doc: Sonic 1 Level Init Profile (44 steps)</a>
 */
public class Sonic1LevelInitProfile extends AbstractLevelInitProfile {
    private final Sonic1LevelEventManager levelEventManager;
    private final Sonic1SwitchManager switchManager;
    private final Sonic1ConveyorState conveyorState;

    public Sonic1LevelInitProfile(Sonic1LevelEventManager levelEventManager,
                                  Sonic1SwitchManager switchManager,
                                  Sonic1ConveyorState conveyorState) {
        this.levelEventManager = levelEventManager;
        this.switchManager = switchManager;
        this.conveyorState = conveyorState;
    }

    @Override
    public int freshMainPlayablePreludeFrames() {
        // GM_Level runs ObjPosLoad + ExecuteObjects once after creating the
        // fresh Sonic slot, before clearing v_frame_counter and entering
        // Level_MainLoop. That pass initializes Sonic's native control,
        // floor/status, animation, and mapping state.
        return 1;
    }

    @Override
    public int preLevelFadeOutFrames() {
        // GM_Level runs ClearPLC then PaletteFadeOut before it decompresses
        // the title-card art and queues the level PLCs (sonic.asm:2710-2737).
        // PaletteFadeOut is an unconditional 22-frame WaitForVBlank loop
        // (move.w #22-1,d4 — docs/s1disasm/_inc/Palette Fading.asm:134-149)
        // whose RunPLC calls only ever see the just-cleared queue, so the
        // returning level's drain begins 22 rows after the game-mode handoff.
        return 22;
    }

    @Override
    public int preLevelMainLoopDelayFrames() {
        // Level_Delay runs 4 WaitForVBlank rows (move.w #4-1,d1 —
        // docs/s1disasm/sonic.asm:2957-2963) and the PalFadeIn_Alt call that
        // follows it (docs/s1disasm/sonic.asm:2966) runs 22 more
        // (move.w #22-1,d4 — docs/s1disasm/_inc/Palette Fading.asm:32-51),
        // with no further wait between the fade and Level_MainLoop. The
        // Level_LoadObj ExecuteObjects pass that stages Sonic's tiles
        // (docs/s1disasm/sonic.asm:2895-2897) sits immediately before the
        // delay loop, so the V-int that performs the transfer is the tail's
        // first row: the level's first main-loop row minus 26.
        return 4 + 22;
    }

    @Override
    public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
        List<InitStep> steps = buildCoreSteps(ctx);
        // GM_Level clears v_misc_variables before resource setup
        // (sonic.asm:2737-2740). v_random lives inside that block
        // (_Variables.asm:143), so deaths, restarts, and normal act loads all
        // begin with a zero RNG seed. Keep this S1-owned: S2/S3K have their
        // own RAM-clear and RNG lifetime rules.
        steps.add(1, new InitStep("ResetRng",
                "S1: clear v_random with v_misc_variables",
                () -> {
                    var rng = GameServices.rngOrNull();
                    if (rng != null) {
                        rng.setSeed(0L);
                    }
                }));
        steps.add(4, new InitStep("QueueInitialPlcs",
                "S1 Level: ClearPLC, level-header primary AddPLC, AddPLC Main2",
                () -> queueInitialPlcs(ctx)));
        // Post-load assembly: 6 steps (no SpawnSidekick — S1 has no Tails)
        if (ctx.isIncludePostLoadAssembly()) {
            steps.add(restoreCheckpointStep(ctx));
            steps.add(spawnPlayerStep(ctx));
            steps.add(resetPlayerStateStep(ctx));
            steps.add(initCameraStep());
            steps.add(initLevelEventsStep());
            if (!isPreviewCapture(ctx)) {
                steps.add(requestTitleCardStep(ctx));
            }
        }
        return List.copyOf(steps);
    }

    private void queueInitialPlcs(LevelLoadContext ctx) {
        if (ctx.getLevel() == null) {
            return;
        }
        Sonic1PlcService plcService = GameServices.module().getGameService(Sonic1PlcService.class);
        if (plcService == null) {
            return;
        }
        try {
            int header = Sonic1Constants.LEVEL_HEADERS_ADDR + ctx.getLevel().getZoneIndex() * 16;
            int primary = (GameServices.rom().getRom().read32BitAddr(header) >>> 24) & 0xFF;
            plcService.transact(primary == 0
                    ? new Sonic1PlcService.Operation[] {Sonic1PlcService.clear(), Sonic1PlcService.appendOperation(1)}
                    : new Sonic1PlcService.Operation[] {Sonic1PlcService.clear(), Sonic1PlcService.appendOperation(primary), Sonic1PlcService.appendOperation(1)});
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to queue S1 initial PLCs", failure);
        }
    }

    @Override
    public void completeInitialPresentationPlcs() {
        Sonic1PlcService plcService =
                GameServices.module().getGameService(Sonic1PlcService.class);
        var levelManager = GameServices.levelOrNull();
        if (plcService == null
                || levelManager == null
                || levelManager.getCurrentLevel() == null) {
            return;
        }
        try {
            completeInitialPresentationPlcs(
                    GameServices.rom().getRom(),
                    plcService,
                    levelManager.getCurrentLevel().getZoneIndex());
            // Level_StartGame bumps the fixed title-card elements out of their
            // move-in routine and enters Level_MainLoop with them still alive
            // (docs/s1disasm/sonic.asm:2969-2995). Their post-release tail
            // re-queues the explosion/animal art 69 ordinary level frames later
            // (docs/s1disasm/_incObj/34 Title Cards.asm:122-168), so arm it here
            // rather than from the card renderer, which a headless run omits.
            levelEventManager.armTitleCardArtReloadAtLevelStart(
                    levelManager.getCurrentZone(), levelManager.getCurrentAct(),
                    levelManager.getCurrentLevel().getZoneIndex());
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Failed to access S1 ROM for initial presentation PLCs",
                    failure);
        }
    }

    static void completeInitialPresentationPlcs(
            com.openggf.data.Rom rom, Sonic1PlcService plcService, int zoneIndex) {
        SkippedPresentationPlcLifecycle.drain(
                plcService, PlcLifecyclePhase.LEVEL_TITLE_CARD,
                plcService::isBusy);
        try {
            int header = Sonic1Constants.LEVEL_HEADERS_ADDR + zoneIndex * 16;
            int secondary = (rom.read32BitAddr(header + 4) >>> 24) & 0xFF;
            if (secondary != 0) {
                plcService.append(secondary);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Failed to queue S1 post-title-card PLC", failure);
        }
        SkippedPresentationPlcLifecycle.runIterations(
                plcService, PlcLifecyclePhase.PALETTE_FADE, 22);
    }

    @Override
    protected InitStep levelEventTeardownStep() {
        return new InitStep("ResetS1LevelEvents",
            "Undoes S1 per-zone event handlers (GHZ/MZ/SYZ/LZ/SLZ/SBZ/FZ events)",
            levelEventManager::resetState);
    }

    @Override
    protected InitStep perTestLeadStep() {
        return new InitStep("ResetS1LevelEvents",
            "Undoes S1 per-zone event handlers, switch state, conveyor state, SBZ3 stomper door",
            () -> {
                levelEventManager.resetState();
                switchManager.reset();
                conveyorState.reset();
                Sonic1StomperDoorObjectInstance.resetSbz3Flag();
            });
    }
}
