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
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.RomWorldPositionedObject;
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
        LevelDescriptor levelData = levelManager.levels.get(levelManager.currentZone).get(levelManager.currentAct);
        levelManager.beginSeamlessTransitionLoad(request);
        try {
            levelManager.loadActTransitionLevelData(
                    levelData.levelIndex(), deferredResources, request.mutationKey());
            deferredResources.verifyFullyConsumed();
        } finally {
            levelManager.endSeamlessTransitionLoad();
        }

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
        if (!request.preserveCheckpointUntilResults()) {
            levelManager.checkpointCoordinator.clear();
        }

        if (!request.preserveLevelGamestate()) {
            levelManager.levelGamestate = levelManager.gameModule.createLevelState();
        }

        List<TransitionSstOccupant> carriedOccupants = levelManager.objectManager == null
                ? List.of()
                : request.objectSurvivalPolicy()
                        == SeamlessLevelTransitionRequest.ObjectSurvivalPolicy.ALL_LIVE_SST
                        ? levelManager.objectManager.snapshotAllLiveSstObjectsForTransition()
                        : levelManager.objectManager.snapshotPersistentTransitionOccupants();

        int postOffsetCameraX = cam.getX() + request.cameraOffsetX();
        levelManager.rebuildManagersForActTransition(cam, carriedOccupants, request,
                postOffsetCameraX);
        levelManager.applySeamlessOffsets(request, cam);
        offsetCarriedObjectsForTransition(carriedOccupants, request);

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

        // loadLevelData only swaps immutable level data; the live object manager
        // and level-event owner are replaced later in this executor. Rebind the
        // registry after both replacements, while still inside the outer seamless
        // transition boundary, so no Act-2 keyframe can target dead Act-1 owners.
        if (gameplayMode != null) {
            gameplayMode.registerLevelAdapters(levelManager);
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

    static void offsetCarriedObjectsForTransition(List<TransitionSstOccupant> carried,
                                                   SeamlessLevelTransitionRequest request) {
        if (request == null || carried == null || carried.isEmpty()) return;
        int offsetX = request.playerOffsetX();
        int offsetY = request.playerOffsetY();
        CarriedTitlePublicationTiming titleTiming = CarriedTitlePublicationTiming.from(request);
        LevelManager currentLevelManager = GameServices.levelOrNull();
        ObjectManager callbacks = currentLevelManager != null
                ? currentLevelManager.getObjectManager() : null;
        for (TransitionSstOccupant occupant : carried) {
            ObjectInstance instance = occupant.identity();
            if (instance == null || com.openggf.level.objects.ObjectCallbackDispatch.call(
                    callbacks, instance, instance::isDestroyed)) continue;
            if (request.objectOffsetPolicy()
                    == SeamlessLevelTransitionRequest.ObjectOffsetPolicy.ROM_WORLD_OFFSET_RANGE) {
                int slot = occupant.originalSlot();
                if (!request.shouldApplyRomWorldOffset(slot, true,
                        com.openggf.level.objects.ObjectCallbackDispatch.call(callbacks,
                                instance, instance::participatesInRomWorldTransitionOffset))) continue;
                if (!(instance instanceof RomWorldPositionedObject positioned)) {
                    throw new IllegalStateException("SST slot " + slot
                            + " reports render_flags bit 2 without a native ROM position contract: "
                            + instance.getClass().getName());
                }
                runCallback(callbacks, instance, () -> {
                    positioned.offsetNativePositionWordsPreserveSubpixel(offsetX, offsetY);
                    positioned.afterRomWorldTransitionOffset(offsetX, offsetY);
                });
            } else {
                runCallback(callbacks, instance,
                        () -> instance.onCarriedAcrossSeamlessTransition(offsetX, offsetY, titleTiming));
            }
        }
    }

    private static void runCallback(ObjectManager manager, ObjectInstance instance, Runnable callback) {
        if (manager == null) callback.run();
        else com.openggf.level.objects.ObjectCallbackDispatch.run(manager, instance, callback);
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
