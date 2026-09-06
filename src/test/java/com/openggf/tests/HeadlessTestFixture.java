package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.game.GameServices;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.GameplaySessionFactory;
import com.openggf.game.session.GameplayTeamBootstrap;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.replay.TraceReplayFixture;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Builder-pattern test fixture that encapsulates the per-test boilerplate
 * found in headless test classes (sprite creation, camera setup, level event
 * initialization, HeadlessTestRunner wiring).
 */
public final class HeadlessTestFixture implements TraceReplayFixture {

    private final GameplayModeContext gameplayMode;
    private final HeadlessTestRunner runner;
    private final AbstractPlayableSprite sprite;
    private HardwareTimingReplayPort hardwareTimingReplayPort;
    private boolean hardwareTimingReplayClosed;

    private HeadlessTestFixture(GameplayModeContext gameplayMode, HeadlessTestRunner runner,
                                AbstractPlayableSprite sprite) {
        this.gameplayMode = gameplayMode;
        this.runner = runner;
        this.sprite = sprite;
    }

    /** Returns a new builder for constructing a fixture. */
    public static Builder builder() {
        return new Builder();
    }

    // ---- Convenience delegates ----

    public void stepFrame(boolean up, boolean down, boolean left,
                          boolean right, boolean jump) {
        runner.stepFrame(up, down, left, right, jump);
    }

    public void stepIdleFrames(int count) {
        runner.stepIdleFrames(count);
    }

    /** Step one frame using input from the loaded BK2 recording. Returns the input mask used. */
    public int stepFrameFromRecording() {
        return runner.stepFrameFromRecording();
    }

    /**
     * Step one frame with the previous BK2 input while consuming and returning
     * the current BK2 row for trace input validation.
     */
    public int stepFrameFromRecordingUsingPreviousInput() {
        return runner.stepFrameFromRecordingUsingPreviousInput();
    }

    /** Advance BK2 by one frame without processing physics (for lag frames). Returns the input mask. */
    public int skipFrameFromRecording() {
        return runner.skipFrameFromRecording();
    }

    @Override
    public void suppressFirstSidekickAnimationOnce() {
        runner.suppressFirstSidekickAnimationOnce();
    }

    /** Consume one BK2 input frame without stepping gameplay or timing counters. */
    public int consumeRecordingFrameInputOnly() {
        return runner.consumeRecordingFrameInputOnly();
    }

    /** Advance the BK2 cursor without stepping gameplay. */
    public void advanceRecordingCursor(int frameCount) {
        runner.advanceRecordingCursor(frameCount);
    }

    @Override
    public int peekRecordingInputAt(int offset) {
        return runner.peekRecordingInputAt(offset);
    }

    /** Returns the playable sprite managed by this fixture. */
    public AbstractPlayableSprite sprite() {
        return sprite;
    }

    /** Returns the camera from the active gameplay mode. */
    public Camera camera() {
        return GameServices.camera();
    }

    /**
     * Returns the active gameplay mode.
     *
     * @deprecated use {@link #gameplayMode()} in new tests.
     */
    @Deprecated(forRemoval = false)
    public GameplayModeContext runtime() {
        return gameplayMode;
    }

    @Override
    public GameplayModeContext gameplayMode() {
        return gameplayMode;
    }

    @Override
    public void installHardwareTimingReplay(
            HardwareTimingReplayPort replayPort) {
        if (hardwareTimingReplayPort != null) {
            throw new IllegalStateException(
                    "hardware timing replay is already installed");
        }
        hardwareTimingReplayPort =
                java.util.Objects.requireNonNull(replayPort, "replayPort");
        TraceHardwareTimingBoundaryObserver observer =
                new TraceHardwareTimingBoundaryObserver(replayPort);
        gameplayMode.getRewindRegistry().register(replayPort);
        gameplayMode.setHardwareTimingBoundaryObserver(observer);
        runner.installHardwareTimingReplayObserver(observer);
        gameplayMode.setHardwareTimingReplayCloseHook(
                this::closeHardwareTimingReplayRun);
    }

    @Override
    public java.util.List<String> drainUnmatchedRecordedHardwareCompletions() {
        return hardwareTimingReplayPort != null
                ? hardwareTimingReplayPort.drainUnmatchedRecordedCompletions()
                : java.util.List.of();
    }

    @Override
    public void reportPendingRecordedHardwareSubmissionsAtClose() {
        if (hardwareTimingReplayPort != null) {
            hardwareTimingReplayPort.reportPendingRecordedSubmissionsAtClose();
        }
    }

    @Override
    public java.util.List<String> drainPendingRecordedHardwareSubmissions() {
        return hardwareTimingReplayPort != null
                ? hardwareTimingReplayPort.drainPendingRecordedSubmissions()
                : java.util.List.of();
    }

    @Override
    public void beginTraceRow(int traceIndex, int rawFrame) {
        runner.beginTraceRow(traceIndex, rawFrame);
    }

    @Override
    public void enterHardwareTimingGap() {
        runner.enterHardwareTimingGap();
    }

    @Override
    public void verifyHardwareTimingSegmentEdges() {
        if (hardwareTimingReplayPort != null) {
            hardwareTimingReplayPort.verifySegmentEdges();
        }
    }

    @Override
    public void handoffHardwareTimingReplay(
            HardwareTimingSchedule nextSchedule) {
        handoffHardwareTimingReplay(nextSchedule, java.util.Map.of());
    }

    @Override
    public void handoffHardwareTimingReplay(
            HardwareTimingSchedule nextSchedule,
            java.util.Map<com.openggf.game.timing.HardwareWorkKind,
                    com.openggf.game.timing.RecordedOrdinalSpan> interstitialSpans) {
        if (hardwareTimingReplayPort != null) {
            hardwareTimingReplayPort.handoffTo(nextSchedule, interstitialSpans);
        }
    }

    @Override
    public void closeHardwareTimingReplayRun() {
        if (hardwareTimingReplayClosed || hardwareTimingReplayPort == null) {
            return;
        }
        hardwareTimingReplayClosed = true;
        try {
            hardwareTimingReplayPort.verifyRunComplete();
        } finally {
            runner.clearHardwareTimingReplayObserver();
            gameplayMode.setHardwareTimingBoundaryObserver(null);
            if (gameplayMode.getRewindRegistry() != null) {
                gameplayMode.getRewindRegistry()
                        .deregister(HardwareTimingReplayPort.REWIND_KEY);
            }
            gameplayMode.clearHardwareTimingReplayCloseHook();
        }
    }

    /**
     * Closes recorded timing at a verified semantic trace prefix while leaving
     * later, unrepresented schedule edges untouched.
     */
    public void closeHardwareTimingReplayPrefix(int inclusiveRawFrame) {
        if (hardwareTimingReplayClosed || hardwareTimingReplayPort == null) {
            return;
        }
        hardwareTimingReplayClosed = true;
        try {
            hardwareTimingReplayPort.verifyPrefixComplete(inclusiveRawFrame);
        } finally {
            runner.clearHardwareTimingReplayObserver();
            gameplayMode.setHardwareTimingBoundaryObserver(null);
            if (gameplayMode.getRewindRegistry() != null) {
                gameplayMode.getRewindRegistry()
                        .deregister(HardwareTimingReplayPort.REWIND_KEY);
            }
            gameplayMode.clearHardwareTimingReplayCloseHook();
        }
    }

    /**
     * Detaches recorded timing after another replay assertion has already
     * failed. Teardown must preserve that primary failure instead of replacing
     * it with the expected "unconsumed edge" consequence of an interrupted
     * run.
     */
    @Override
    public void abortHardwareTimingReplayRun() {
        if (hardwareTimingReplayClosed || hardwareTimingReplayPort == null) {
            return;
        }
        hardwareTimingReplayClosed = true;
        runner.clearHardwareTimingReplayObserver();
        gameplayMode.setHardwareTimingBoundaryObserver(null);
        gameplayMode.clearHardwareTimingReplayCloseHook();
        if (gameplayMode.getRewindRegistry() != null) {
            gameplayMode.getRewindRegistry()
                    .deregister(HardwareTimingReplayPort.REWIND_KEY);
        }
    }

    /** Returns the underlying headless test runner. */
    public HeadlessTestRunner runner() {
        return runner;
    }

    /** Returns the number of frames stepped so far. */
    public int frameCount() {
        return runner.getFrameCounter();
    }

    // ---- Builder ----

    public static final class Builder {

        private SharedLevel sharedLevel;
        private int zone = -1;
        private int act = -1;
        private short startX;
        private short startY;
        private Bk2Movie bk2Movie;
        private int bk2FrameOffset;
        private boolean startPositionIsCentre;
        private boolean customStartPositionProvided;
        private boolean freshLevelStartLifecycle;
        private HardwareReadinessAdmissionPolicy hardwareAdmissionPolicy =
                HardwareReadinessAdmissionPolicy.LIVE;

        private Builder() {}

        public Builder withSharedLevel(SharedLevel level) {
            this.sharedLevel = level;
            return this;
        }

        public Builder withZoneAndAct(int zone, int act) {
            this.zone = zone;
            this.act = act;
            return this;
        }

        public Builder startPosition(short x, short y) {
            this.startX = x;
            this.startY = y;
            this.customStartPositionProvided = true;
            return this;
        }

        /**
         * Treat start position as ROM centre coordinates (like $D008/$D00C).
         * Uses setCentreX/Y after construction, matching
         * LevelManager.spawnPlayerAtStartPosition() behaviour.
         */
        public Builder startPositionIsCentre() {
            this.startPositionIsCentre = true;
            return this;
        }

        public Builder withRecording(Path bk2Path) throws IOException {
            this.bk2Movie = new Bk2MovieLoader().load(bk2Path);
            return this;
        }

        public Builder withRecordingStartFrame(int bk2FrameOffset) {
            this.bk2FrameOffset = bk2FrameOffset;
            return this;
        }

        /**
         * Marks this fixture as representing the first ordinary dispatch after
         * a fresh level start. The active {@link com.openggf.game.LevelInitProfile}
         * decides whether that lifecycle permits the fixture's synthetic
         * pre-frame terrain snap.
         */
        public Builder withFreshLevelStartLifecycle() {
            this.freshLevelStartLifecycle = true;
            return this;
        }

        public Builder withHardwareReadinessAdmissionPolicy(
                HardwareReadinessAdmissionPolicy admissionPolicy) {
            this.hardwareAdmissionPolicy =
                    java.util.Objects.requireNonNull(
                            admissionPolicy, "admissionPolicy");
            return this;
        }

        public HeadlessTestFixture build() {
            if (sharedLevel == null && zone < 0) {
                throw new IllegalStateException(
                        "HeadlessTestFixture.Builder requires either withSharedLevel() or withZoneAndAct() before build()");
            }

            // Recorded admission must own the context before any level load
            // can submit hardware work.
            GameplayModeContext existing = SessionManager.getCurrentGameplayMode();
            if (hardwareAdmissionPolicy
                    == HardwareReadinessAdmissionPolicy.RECORDED
                    && (existing == null
                    || existing.hardwareTiming().admissionPolicy()
                    != HardwareReadinessAdmissionPolicy.RECORDED)) {
                GameplayModeContext reopened =
                        SessionManager.reopenGameplaySession(
                                HardwareReadinessAdmissionPolicy.RECORDED);
                GameplaySessionFactory.attachManagers(
                        reopened,
                        com.openggf.game.session.EngineServices.current());
                GameModuleRegistry.setCurrent(
                        reopened.getWorldSession().getGameModule());
            }

            // 1. Reset transient per-test state
            TestEnvironment.resetPerTest();
            GraphicsManager.getInstance().initHeadless();

            // 2. Shared-level tests rely on the config snapshot that was active
            // when the level was originally loaded. @RequiresRom rebuilds the
            // runtime before each test method, which restores default config.
            if (sharedLevel != null) {
                SonicConfigurationService config = SonicConfigurationService.getInstance();
                config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, sharedLevel.skipIntros());
                config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, sharedLevel.mainCharCode());
                config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, sharedLevel.sidekickCharCode());
                config.setConfigValue(SonicConfiguration.LOAD_TIME_SIMULATION, sharedLevel.loadTimeSimulation());
                // WorldSession caches its load-time mode when it opens, and the
                // per-test runtime rebuild opened this one under default config
                // before the snapshot above was re-applied. Reopen it so the
                // mode the level was loaded under governs hardware pacing too;
                // the shared-level reload below re-attaches the level.
                GameplayModeContext current = SessionManager.getCurrentGameplayMode();
                if (hardwareAdmissionPolicy != HardwareReadinessAdmissionPolicy.RECORDED
                        && current != null
                        && current.getWorldSession().loadTimeSimulationMode()
                        != com.openggf.game.timing.LoadTimeSimulationMode.parse(
                                sharedLevel.loadTimeSimulation())) {
                    SessionManager.clear();
                    TestEnvironment.activeGameplayMode();
                }
            }

            // 3. Register the active gameplay team before any load path that
            // needs it. Fresh zone/act loads and shared-level reloads both
            // expect the main sprite to exist before
            // LevelManager.spawnPlayerAtStartPosition(), and Sonic 2 traces
            // rely on Tails being present from the same bootstrap phase.
            boolean needsSharedLevelReload = sharedLevel != null
                    && GameServices.level().getCurrentLevel() == null;

            AbstractPlayableSprite sprite = null;
            if (sharedLevel == null || needsSharedLevelReload) {
                sprite = GameplayTeamBootstrap.registerActiveTeam(
                        GameServices.module(),
                        GameServices.sprites(),
                        SonicConfigurationService.getInstance())
                        .mainSprite();
            }

            // 4. Load or rewire the requested level.
            if (sharedLevel == null) {
                try {
                    GameServices.level().loadZoneAndAct(zone, act);
                } catch (IOException e) {
                    throw new UncheckedIOException(
                            "Failed to load zone " + zone + " act " + act, e);
                }
            } else if (GameServices.level().getCurrentLevel() == null) {
                try {
                    GameServices.level().loadZoneAndAct(sharedLevel.zone(), sharedLevel.act());
                } catch (IOException e) {
                    throw new UncheckedIOException(
                            "Failed to reload shared level zone " + sharedLevel.zone()
                                    + " act " + sharedLevel.act(), e);
                }
            } else {
                // Re-wire CollisionSystem after per-test reset when using SharedLevel.
                // resetPerTest() clears CollisionSystem.objectManager, but the SharedLevel
                // path skips level reload (which normally restores the wiring).
                ObjectManager om = GameServices.level().getObjectManager();
                if (om != null) {
                    GameServices.collision().setObjectManager(om);
                }
            }

            // 5. Create/register the active team if the shared-level reuse path
            // skipped the normal load bootstrap.
            if (sprite == null) {
                sprite = GameplayTeamBootstrap.registerActiveTeam(
                        GameServices.module(),
                        GameServices.sprites(),
                        SonicConfigurationService.getInstance())
                        .mainSprite();
            }
            if (sharedLevel != null && !needsSharedLevelReload) {
                // resetPerTest() replaces the playable roster while retaining
                // the shared level's ObjectManager. Rebind the new sprites so
                // the spawner's objects and their dynamic children use that
                // retained manager just as they do after a fresh load.
                //
                // This is deliberately NOT conditioned on
                // playerCapability().elementalShieldsEnabled(). That capability
                // is true only for S3K (GameRules.java:60,208,363), and the
                // spawner owns three unrelated objects, only one of which is a
                // shield: spawnShield, spawnInvincibilityStars and spawnSplash
                // (AbstractPlayableSprite.java:1276,1424 / 1313,1544 /
                // 5234,5298). With the capability in the condition, S1 and S2
                // reached here with sharedLevel=true and needsReload=false and
                // were left with a null spawner, so none of the three objects
                // was ever created under trace replay. S3K was unaffected for a
                // different reason than the gate: its fixtures take the
                // fresh-load path (sharedLevel=false), where LevelManager wires
                // the spawner itself and this branch is never reached.
                GameServices.level().refreshPlayablePowerUpSpawners();
            }
            if (sprite.getAnimationProfile() == null && GameServices.level() != null) {
                GameServices.level().refreshPlayableSpriteArt();
            }

            // 6. Preserve existing builder semantics for explicit custom starts by
            // reapplying the requested coordinates after any level load. When a
            // shared level is already loaded, resetPerTest() has cleared the
            // sprite roster but not re-run LevelManager.spawnPlayerAtStartPosition();
            // place the freshly registered team at the level's ROM start before
            // sidekick anchoring and ground snap.
            if (customStartPositionProvided) {
                if (startPositionIsCentre) {
                    sprite.setCentreX(startX);
                    sprite.setCentreY(startY);
                } else {
                    sprite.setX(startX);
                    sprite.setY(startY);
                }
            } else if (sharedLevel != null && !needsSharedLevelReload) {
                int[] start = GameServices.module()
                        .getZoneRegistry()
                        .getStartPosition(sharedLevel.zone(), sharedLevel.act());
                sprite.setCentreX((short) start[0]);
                sprite.setCentreY((short) start[1]);
            }

            // 7. Re-anchor registered sidekicks to the current player position.
            GameplayTeamBootstrap.repositionRegisteredSidekicks(
                    GameServices.module(),
                    GameServices.level());

            // 8. Wire GroundSensor
            GroundSensor.setLevelManager(GameServices.level());

            // 9. Initialize camera via production path
            GameServices.level().initCameraForLevel();

            // 10. Initialize level events via production path
            GameServices.level().initLevelEventsForLevel();

            // 10b. Re-apply S3K zone player state after sidekick reposition.
            // ROM's SpawnLevelMainSprites_SpawnPlayers (sonic3k.asm:8335-8427)
            // sets sidekick position FIRST, then SpawnLevelMainSprites
            // (sonic3k.asm:8132-8205) sets the in-air status for zones like
            // MGZ1 / HCZ1 / LRZ1 non-Knuckles. repositionRegisteredSidekicks at step
            // 7 clears the in-air bit via spawnSidekicks, so the zone-event
            // handler must run again to restore the falling-intro state.
            var levelEventProvider = GameServices.module().getLevelEventProvider();
            if (levelEventProvider instanceof com.openggf.game.sonic3k.Sonic3kLevelEventManager s3kLem) {
                s3kLem.applyZonePlayerState();
            }

            // 11. Refresh sidekick CPU bounds after camera/event init. The
            // level-load and reanchor paths can snapshot camera bounds before
            // initCameraForLevel()/initLevelEventsForLevel() have finalized
            // them; title-card sidekick prelude ticks must see the finalized
            // bounds.
            TraceReplaySessionBootstrap.refreshSidekickCpuBoundsFromCamera();

            // 12. Initial ground snap. ROM runs terrain probes during title card
            // frames (~120 frames) which snap the player to ground and set the
            // correct terrain angle. Tests skip the title card, so do one probe
            // to establish ground attachment. Uses threshold=14 (S1 always uses
            // 14; S2/S3K at speed=0 would use min(0+4,14)=4, but 14 is safe for
            // a static snap at spawn).
            boolean preserveFreshGroundedStatus = freshLevelStartLifecycle
                    && GameServices.module().getLevelInitProfile()
                            .preserveFreshGroundedStatusUntilFirstDispatch();
            if (!preserveFreshGroundedStatus) {
                GameServices.collision().resolveGroundAttachment(
                        sprite, 14, () -> false);
            }

            // 13. Resolve the active session context and create runner
            GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
            HeadlessTestRunner runner = new HeadlessTestRunner(sprite);

            // 14. Wire BK2 recording if provided
            if (bk2Movie != null) {
                runner.setBk2Movie(bk2Movie, bk2FrameOffset);
            }

            return new HeadlessTestFixture(gameplayMode, runner, sprite);
        }
    }
}
