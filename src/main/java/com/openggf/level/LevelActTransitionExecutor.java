package com.openggf.level;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.OscillationManager;
import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.level.animation.SeamlessTransitionAnimationClock;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.level.resources.DeferredLevelResourceTracker;

import java.io.IOException;
import java.util.List;

/**
 * Owns ROM-aligned in-place act-transition execution.
 */
final class LevelActTransitionExecutor {
    private final LevelManager levelManager;
    private boolean executedDuringFrame;
    private boolean rewindBoundaryDuringFrame;
    private boolean oscillationAdvancedDuringFrame;

    LevelActTransitionExecutor(LevelManager levelManager) {
        this.levelManager = levelManager;
    }

    void execute(SeamlessLevelTransitionRequest request) throws IOException {
        if (request == null) {
            return;
        }
        if (request.runtimeArtAdmissionPolicy()
                        == RuntimeArtAdmissionPolicy.RESOURCE_HANDOFF_OWNER
                && request.resourceHandoffId() == null) {
            throw new IllegalStateException(
                    "resource-handoff runtime-art admission requires a handoff");
        }

        SeamlessTransitionResourceHandoffRegistry handoffRegistry =
                GameServices.seamlessTransitionResourceHandoffs();
        ClaimedTransitionHandoff claimed = new ClaimedTransitionHandoff(
                request.resourceHandoffId() != null
                        ? handoffRegistry.claim(request.resourceHandoffId())
                        : null);
        try {
            executeClaimed(request, claimed);
            // The ROM's own seamless advance -- AIZ1BGE_Finish writes
            // Current_zone_and_act and calls Load_Level from inside the
            // level's background-event dispatch
            // (docs/skdisasm/sonic3k.asm:104733-104746), leaving neither the
            // level mode nor the recorded run segment. This routine is the
            // single owner of that identity change, so it is where a run
            // replay observes the identity reached; a no-op outside a run.
            com.openggf.TraceSessionLauncher.observeRunSeamlessLevelAdvance(
                    levelManager);
            executedDuringFrame = true;
            rewindBoundaryDuringFrame = true;
        } catch (IOException | RuntimeException failure) {
            if (claimed.handoff != null
                    && !claimed.transferComplete
                    && request.resourceHandoffId() != null) {
                handoffRegistry.recordFailedTransfer(
                        request.resourceHandoffId(), claimed.handoff);
            }
            throw failure;
        }
    }

    void markOscillationAdvancedDuringFrame() {
        oscillationAdvancedDuringFrame = true;
    }

    boolean consumeExecutedDuringFrame() {
        boolean executed = executedDuringFrame;
        executedDuringFrame = false;
        return executed;
    }

    boolean consumeRewindBoundaryDuringFrame() {
        boolean boundary = rewindBoundaryDuringFrame;
        rewindBoundaryDuringFrame = false;
        return boundary;
    }

    boolean consumeOscillationAdvancedDuringFrame() {
        boolean advanced = oscillationAdvancedDuringFrame;
        oscillationAdvancedDuringFrame = false;
        return advanced;
    }

    void resetFrameMarkers() {
        executedDuringFrame = false;
        rewindBoundaryDuringFrame = false;
        oscillationAdvancedDuringFrame = false;
    }

    private void executeClaimed(
            SeamlessLevelTransitionRequest request,
            ClaimedTransitionHandoff claimed) throws IOException {
        SeamlessTransitionResourceHandoff handoff = claimed.handoff;
        DeferredLevelResourceTracker deferredResources =
                handoff != null
                        ? handoff.deferredResources().newTracker()
                        : DeferredLevelResourceTracker.none();

        Camera cam = levelManager.camera;

        GameStateManager gameState = GameServices.gameState();
        boolean endOfLevelActive = gameState.isEndOfLevelActive();
        boolean endOfLevelFlag = gameState.isEndOfLevelFlag();
        gameState.resetForLevel();
        if (request.preserveEndOfLevelActive()) {
            gameState.setEndOfLevelActive(endOfLevelActive);
        }
        if (request.preserveEndOfLevelFlag()) {
            gameState.setEndOfLevelFlag(endOfLevelFlag);
        }

        // NOTE: preserveMusic() needs no action here. An in-place act transition
        // never runs the level-init profile, so InitAudio — the only consumer of
        // the suppress-next-music flag — never fires. Setting the flag here left
        // it latched and silenced the *next* real level load instead (music then
        // stayed off until a respawn or another load cleared it).

        var transitionRuntime = GameServices.zoneRuntimeRegistry().current();
        boolean transitionOwnsLoopTail =
                transitionRuntime.advancesOscillationOnSeamlessTransition();
        if (transitionOwnsLoopTail) {
            levelManager.markActTransitionOscillationAdvancedDuringFrame();
            OscillationManager.advanceForSeamlessTransition();
        }

        levelManager.writeCurrentZone(request.targetZone());
        levelManager.writeCurrentAct(request.targetAct());

        if (levelManager.levels.isEmpty()) {
            levelManager.gameModule = GameServices.module();
            levelManager.refreshZoneList();
        }
        LevelData levelData = levelManager.levels.get(levelManager.currentZone).get(levelManager.currentAct);
        levelManager.loadLevelData(
                levelData.getLevelIndex(), deferredResources);
        deferredResources.verifyFullyConsumed();

        if (request.mutationKey() != null && !request.mutationKey().isBlank()) {
            levelManager.applySeamlessMutation(request.mutationKey());
        }
        // A prepared load built its tilemaps from the post-mutation layout;
        // swap them in now so the first render does not rebuild both layers.
        if (levelManager.hasPrebuiltTilemaps()) {
            levelManager.swapToPrebuiltTilemaps();
        }

        levelManager.initAnimatedContent();
        if (transitionOwnsLoopTail
                && levelManager.animatedPatternManager
                        instanceof SeamlessTransitionAnimationClock clock) {
            clock.advanceForSeamlessTransition();
        }

        ObjectArtProvider artProvider = levelManager.gameModule != null
                ? levelManager.gameModule.getObjectArtProvider()
                : null;
        if (artProvider != null) {
            RuntimeArtAdmissionLease admissionLease =
                    artProvider.prepareRuntimeArtForActTransition(
                            levelManager.currentZone,
                            request.runtimeArtAdmissionPolicy());
            if (request.runtimeArtAdmissionPolicy()
                    == RuntimeArtAdmissionPolicy.RESOURCE_HANDOFF_OWNER) {
                if (admissionLease == null) {
                    throw new IllegalStateException(
                            "resource-handoff admission did not issue a lease");
                }
                handoff = handoff.withAdmissionLease(admissionLease);
                claimed.handoff = handoff;
            }
            artProvider.registerLevelTileArt(levelManager.level, levelManager.currentZone);
            if (levelManager.objectRenderManager != null) {
                levelManager.objectRenderManager.ensurePatternsCached(
                        levelManager.graphicsManager, LevelManager.OBJECT_PATTERN_BASE);
            }
        }

        levelManager.initWater(true);
        levelManager.checkpointCoordinator.clear();

        if (!request.preserveLevelGamestate()) {
            levelManager.levelGamestate = levelManager.gameModule.createLevelState();
        }

        List<ObjectInstance> persistentDynamicObjects = levelManager.objectManager != null
                ? levelManager.objectManager.snapshotPersistentDynamicObjectsForTransition()
                : List.of();

        levelManager.rebuildManagersForActTransition(
                cam,
                persistentDynamicObjects,
                request.preserveEndOfLevelActive());
        levelManager.applySeamlessOffsets(request, cam);
        levelManager.offsetCarriedObjectsForTransition(persistentDynamicObjects, request);

        levelManager.restoreCameraBoundsForCurrentLevel(cam);
        levelManager.applyPostTransitionCameraOverrides(request, cam);
        if (!request.preserveOffsetCameraPosition()) {
            cam.updatePosition(true);
        }

        resetSidekickCpuBoundsAfterTransition(cam);
        levelManager.initLevelEventsForCurrentZoneAct();
        var gameplayMode = com.openggf.game.session.SessionManager
                .getCurrentGameplayMode();
        if (gameplayMode != null) {
            gameplayMode.rebindActTransitionManagerAdapters(
                    levelManager.objectManager, levelManager.ringManager);
        }
        if (handoff != null) {
            handoff.transferAfterTargetInit();
            claimed.transferComplete = true;
        }

        try {
            levelManager.reinitializeZoneFeaturesForActTransition();
        } catch (IOException e) {
            LevelManager.LOGGER.warning("Failed to reinitialize zone features: " + e.getMessage());
        }

        if (request.musicOverrideId() >= 0) {
            levelManager.audioManager.playMusic(request.musicOverrideId());
        }

        if (request.showInLevelTitleCard()) {
            levelManager.requestInLevelTitleCard(
                    levelManager.currentZone,
                    levelManager.currentAct,
                    request.resetLevelGamestateAtInLevelTitleCardDisplay(),
                    request.inLevelTitleCardResetAdditionalDispatches(),
                    request.inLevelTitleCardResetPhaseOneDispatchOverlap(),
                    request.lockPlayerControlForInLevelTitleCard(),
                    request.inLevelTitleCardExitAdditionalDispatches(),
                    request.inLevelTitleCardExitPhaseOneDispatchOverlap());
        }
    }

    private void resetSidekickCpuBoundsAfterTransition(Camera cam) {
        for (AbstractPlayableSprite sidekick : levelManager.spriteManager.getSidekicks()) {
            SidekickCpuController cpu = sidekick.getCpuController();
            if (cpu != null) {
                cpu.setLevelBounds(
                        (int) cam.getMinX(),
                        (int) cam.getMaxX(),
                        (int) Math.max(cam.getMaxY(), cam.getMaxYTarget()));
            }
        }
    }

    private static final class ClaimedTransitionHandoff {
        private SeamlessTransitionResourceHandoff handoff;
        private boolean transferComplete;

        private ClaimedTransitionHandoff(
                SeamlessTransitionResourceHandoff handoff) {
            this.handoff = handoff;
        }
    }
}
