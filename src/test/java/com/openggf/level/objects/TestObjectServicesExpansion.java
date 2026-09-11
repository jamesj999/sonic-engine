package com.openggf.level.objects;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.game.BonusStageProvider;
import com.openggf.game.BonusStageType;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the expanded ObjectServices methods delegate to the correct singletons.
 */
class TestObjectServicesExpansion {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @Test
    void defaultObjectServices_camera_returnsSingleton() {
        DefaultObjectServices services = sessionServices();
        assertSame(GameServices.camera(), services.camera(),
                "camera() should delegate to GameServices.camera()");
    }

    @Test
    void defaultObjectServices_levelManager_returnsRuntimeLevelManager() {
        DefaultObjectServices services = sessionServices();
        assertSame(GameServices.level(), services.levelManager(),
                "levelManager() should delegate to the runtime-owned level manager");
    }

    @Test
    void defaultObjectServices_gameState_returnsSingleton() {
        DefaultObjectServices services = sessionServices();
        assertSame(GameServices.gameState(), services.gameState(),
                "gameState() should delegate to GameServices.gameState()");
    }

    @Test
    void defaultObjectServices_worldSession_returnsRuntimeWorldSession() {
        DefaultObjectServices services = sessionServices();
        assertSame(GameServices.worldSession(), services.worldSession(),
                "worldSession() should delegate to the runtime-owned world session");
    }

    @Test
    void defaultObjectServices_gameModule_returnsRuntimeModule() {
        DefaultObjectServices services = sessionServices();
        assertSame(GameServices.module(), services.gameModule(),
                "gameModule() should delegate to the runtime-owned module");
    }

    @Test
    void defaultObjectServices_processServices_returnRuntimeEngineServicesMembers() {
        DefaultObjectServices services = sessionServices();
        EngineContext engineServices = EngineServices.current();

        assertSame(engineServices, services.engineServices());
        assertSame(engineServices.configuration(), services.configuration());
        assertSame(engineServices.debugOverlay(), services.debugOverlay());
        assertSame(engineServices.roms(), services.romManager());
        assertSame(engineServices.crossGameFeatures(), services.crossGameFeatures());
    }

    @Test
    void defaultObjectServices_sidekicks_returnsUnmodifiableList() {
        DefaultObjectServices services = sessionServices();
        var sidekicks = services.sidekicks();
        assertNotNull(sidekicks);
        assertThrows(UnsupportedOperationException.class, () -> sidekicks.add(null));
    }

    @Test
    void defaultObjectServices_requiresGameplayMode() {
        assertThrows(NullPointerException.class,
                () -> new DefaultObjectServices(null, EngineServices.current()));
    }

    @Test
    void bootstrapObjectServices_delegatesExpandedApiToGameServices() {
        BootstrapObjectServices services = new BootstrapObjectServices();

        assertSame(GameServices.level(), services.levelManager());
        assertSame(GameServices.camera(), services.camera());
        assertSame(GameServices.gameState(), services.gameState());
        assertSame(SonicConfigurationService.getInstance(), services.configuration());
        assertSame(DebugOverlayManager.getInstance(), services.debugOverlay());
        assertSame(RomManager.getInstance(), services.romManager());
        assertSame(CrossGameFeatureProvider.getInstance(), services.crossGameFeatures());
        assertNotNull(services.engineServices());
    }

    @Test
    void bootstrapObjectServices_usesRuntimeOwnedMutationPipeline() {
        BootstrapObjectServices services = new BootstrapObjectServices();

        assertSame(GameServices.zoneLayoutMutationPipeline(), services.zoneLayoutMutationPipeline(),
                "bootstrap object services must not create a private mutation pipeline when runtime exists");
    }

    @Test
    void defaultObjectServices_bonusStageActionsFollowLiveProviderOnOwningSession() {
        // DefaultObjectServices resolves bonus-stage forwarding through the
        // OWNING GameplayModeContext's CURRENT activeBonusStageProvider,
        // rather than a value snapshotted at construction time. This matters
        // because the level's ObjectManager/DefaultObjectServices is built
        // during normal level load, while bonus-stage entry registers the
        // provider on that SAME session afterwards (GameLoop
        // .doEnterBonusStage: setActiveBonusStageProvider precedes the fresh
        // loadZoneAndAct-built ObjectManager in production, but trace-replay
        // bootstraps that reuse a pre-built fixture level register the
        // provider AFTER services already exist -- TraceReplaySessionBootstrap
        // .applyBonusStageEntry). A frozen snapshot would make an object's
        // requestBonusStageExit() (e.g. Obj_PachinkoEnergyTrap's
        // escape-through-top trigger) permanently no-op in that ordering.
        GameplayModeContext gameplay = TestEnvironment.activeGameplayMode();

        // Services built BEFORE any bonus-stage provider is registered still
        // reach the provider once it is later set on the SAME session.
        DefaultObjectServices services = sessionServices(gameplay);
        CountingBonusStageProvider providerA = new CountingBonusStageProvider();
        gameplay.setActiveBonusStageProvider(providerA);

        services.requestBonusStageExit();
        services.addBonusStageRings(7);
        services.setBonusStageShield(com.openggf.game.ShieldType.LIGHTNING);

        assertEquals(1, providerA.requestExitCount,
                "requestBonusStageExit should call the session's current provider");
        assertEquals(7, providerA.ringsAdded,
                "addBonusStageRings should add rings on the session's current provider");
        assertEquals(1, providerA.shieldsSet,
                "setBonusStageShield should forward to the session's current provider");

        // A later re-registration on the SAME session (e.g. exiting and
        // re-entering a bonus stage) is also picked up live, not pinned to
        // whichever provider happened to be active at construction time.
        CountingBonusStageProvider providerB = new CountingBonusStageProvider();
        gameplay.setActiveBonusStageProvider(providerB);

        services.requestBonusStageExit();

        assertEquals(1, providerA.requestExitCount,
                "the former provider must not receive calls once replaced");
        assertEquals(1, providerB.requestExitCount,
                "requestBonusStageExit should follow the session's newly-registered provider");
    }

    private static final class CountingBonusStageProvider implements BonusStageProvider {
        int requestExitCount;
        int ringsAdded;
        int shieldsSet;

        @Override
        public boolean hasBonusStages() {
            return true;
        }

        @Override
        public BonusStageType selectBonusStage(int ringCount) {
            return null;
        }

        @Override
        public void onEnter(BonusStageType type, com.openggf.game.BonusStageState savedState) {
        }

        @Override
        public void onExit() {
        }

        @Override
        public void onFrameUpdate() {
        }

        @Override
        public boolean isStageComplete() {
            return false;
        }

        @Override
        public void requestExit() {
            requestExitCount++;
        }

        @Override
        public int getZoneId(BonusStageType type) {
            return -1;
        }

        @Override
        public int getMusicId(BonusStageType type) {
            return -1;
        }

        @Override
        public com.openggf.game.BonusStageState getSavedState() {
            return null;
        }

        @Override
        public com.openggf.game.BonusStageProvider.BonusStageRewards getRewards() {
            return com.openggf.game.BonusStageProvider.BonusStageRewards.none();
        }

        @Override
        public void addRings(int count) {
            ringsAdded += count;
        }

        @Override
        public void addLife() {
        }

        @Override
        public void setAwardedShield(com.openggf.game.ShieldType type) {
            shieldsSet++;
        }
    }

    private DefaultObjectServices sessionServices() {
        return sessionServices(TestEnvironment.activeGameplayMode());
    }

    private DefaultObjectServices sessionServices(GameplayModeContext gameplayMode) {
        return new DefaultObjectServices(gameplayMode, EngineServices.current());
    }
}

