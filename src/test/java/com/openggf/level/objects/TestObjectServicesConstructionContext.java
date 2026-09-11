package com.openggf.level.objects;

import com.openggf.game.session.EngineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the ThreadLocal construction context makes {@link ObjectServices}
 * available during object construction, and that it is properly cleaned up.
 */
class TestObjectServicesConstructionContext {

    /**
     * Minimal ObjectServices for testing. All methods throw to catch unintended calls.
     */
    private static final ObjectServices TEST_SERVICES = new ObjectServices() {
        @Override public ObjectManager objectManager() { return null; }
        @Override public ObjectRenderManager renderManager() { return null; }
        @Override public com.openggf.game.LevelState levelGamestate() { return null; }
        @Override public com.openggf.game.RespawnState checkpointState() { return null; }
        @Override public com.openggf.level.LevelManager levelManager() { return null; }
        @Override public com.openggf.level.Level currentLevel() { return null; }
        @Override public int romZoneId() { return 0; }
        @Override public int currentAct() { return 0; }
        @Override public int featureZoneId() { return 0; }
        @Override public int featureActId() { return 0; }
        @Override public com.openggf.game.ZoneFeatureProvider zoneFeatureProvider() { return null; }
        @Override public void playSfx(int soundId) {}
        @Override public void playSfx(com.openggf.audio.GameSound sound) {}
        @Override public void playMusic(int musicId) {}
        @Override public void fadeOutMusic() {}
        @Override public com.openggf.audio.AudioManager audioManager() { return null; }
        @Override public com.openggf.game.GameRng rng() { return new com.openggf.game.GameRng(com.openggf.game.GameRng.Flavour.S3K); }
        @Override public com.openggf.game.zone.ZoneRuntimeRegistry zoneRuntimeRegistry() { return new com.openggf.game.zone.ZoneRuntimeRegistry(); }
        @Override public com.openggf.game.zone.ZoneRuntimeState zoneRuntimeState() { return zoneRuntimeRegistry().current(); }
        @Override public com.openggf.game.palette.PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() { return null; }
        @Override public com.openggf.game.mutation.ZoneLayoutMutationPipeline zoneLayoutMutationPipeline() { return new com.openggf.game.mutation.ZoneLayoutMutationPipeline(); }
        @Override public com.openggf.game.solid.SolidExecutionRegistry solidExecutionRegistry() { return com.openggf.game.solid.SolidExecutionRegistry.inert(); }
        @Override public void spawnLostRings(com.openggf.game.PlayableEntity player, int fc) {}
        @Override public com.openggf.camera.Camera camera() { return null; }
        @Override public com.openggf.game.GameStateManager gameState() { return null; }
        @Override public com.openggf.game.session.WorldSession worldSession() { return null; }
        @Override public com.openggf.game.GameModule gameModule() { return null; }
        @Override public java.util.List<com.openggf.game.PlayableEntity> sidekicks() { return java.util.List.of(); }
        @Override public com.openggf.sprites.managers.SpriteManager spriteManager() { return null; }
        @Override public com.openggf.graphics.GraphicsManager graphicsManager() { return null; }
        @Override public com.openggf.graphics.FadeManager fadeManager() { return null; }
        @Override public com.openggf.game.session.EngineContext engineServices() { return null; }
        @Override public com.openggf.configuration.SonicConfigurationService configuration() { return null; }
        @Override public com.openggf.debug.DebugOverlayManager debugOverlay() { return null; }
        @Override public com.openggf.data.RomManager romManager() { return null; }
        @Override public com.openggf.game.CrossGameFeatureProvider crossGameFeatures() { return null; }
        @Override public com.openggf.data.Rom rom() { return null; }
        @Override public com.openggf.data.RomByteReader romReader() { return null; }
        @Override public com.openggf.level.WaterSystem waterSystem() { return null; }
        @Override public com.openggf.level.ParallaxManager parallaxManager() { return null; }
        @Override public com.openggf.physics.CollisionSystem collisionSystem() { return null; }
        @Override public void advanceToNextLevel() {}
        @Override public void requestCreditsTransition() {}
        @Override public void requestSessionSave(com.openggf.game.save.SaveReason reason) {}
        @Override public void requestSpecialStageEntry() {}
        @Override public void invalidateForegroundTilemap() {}
        @Override public boolean areAllRingsCollected() { return false; }
        @Override public void updatePalette(int idx, byte[] data) {}
        @Override public com.openggf.level.rings.RingManager ringManager() { return null; }
        @Override public void advanceZoneActOnly() {}
        @Override public void setApparentAct(int act) {}
        @Override public void advanceToSpecialStageEntryRoutine() {}
        @Override public void requestBonusStageEntry(com.openggf.game.BonusStageType type) {}
        @Override public void requestBonusStageExit() {}
        @Override public void addBonusStageRings(int count) {}
        @Override public void setBonusStageShield(com.openggf.game.ShieldType type) {}
        @Override public void requestZoneAndAct(int zone, int act) {}
        @Override public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow) {}
        @Override public int getCurrentLevelMusicId() { return 0; }
        @Override public int[] findPatternOffset(int refX, int refY, int minTileIdx, int maxTileIdx, int searchRadius) { return null; }
        @Override public void saveBigRingReturn(com.openggf.level.BigRingReturnState state) {}
        @Override public void clearLastStarPostHit() {}
    };

    @AfterEach
    void clearConstructionContext() {
        // Defense in depth: if any test path forgets to clear the ThreadLocal
        // (e.g. an assertion fires mid-try), the leak does not corrupt other
        // tests in the same fork.
        AbstractObjectInstance.CONSTRUCTION_CONTEXT.remove();
    }

    /** Test object that calls services() in its constructor. */
    private static class ConstructorAccessObject extends AbstractObjectInstance {
        final ObjectServices constructorServices;

        ConstructorAccessObject(ObjectSpawn spawn) {
            super(spawn, "TestConstructorAccess");
            // This call would previously throw IllegalStateException
            this.constructorServices = services();
        }

        @Override
        public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) {}
    }

    private static class OptionalServicesObject extends AbstractObjectInstance {
        OptionalServicesObject(ObjectSpawn spawn) { super(spawn, "OptionalServices"); }
        ObjectServices optionalServices() { return tryServices(); }
        @Override public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) {}
    }

    @Test
    void services_availableDuringConstruction_whenContextSet() {
        ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);

        AbstractObjectInstance.CONSTRUCTION_CONTEXT.set(TEST_SERVICES);
        try {
            ConstructorAccessObject obj = new ConstructorAccessObject(spawn);
            assertSame(TEST_SERVICES, obj.constructorServices,
                    "services() should return construction context during constructor");
        } finally {
            AbstractObjectInstance.CONSTRUCTION_CONTEXT.remove();
        }
    }

    @Test
    void services_usesInstanceField_afterSetServices() {
        ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);

        // Create with context
        AbstractObjectInstance.CONSTRUCTION_CONTEXT.set(TEST_SERVICES);
        ConstructorAccessObject obj;
        try {
            obj = new ConstructorAccessObject(spawn);
        } finally {
            AbstractObjectInstance.CONSTRUCTION_CONTEXT.remove();
        }

        // Now set instance-level services (different instance)
        ObjectServices instanceServices = new TestObjectServices();
        obj.setServices(instanceServices);

        // Instance field should take priority over (now-cleared) context
        assertSame(instanceServices, obj.services(),
                "services() should prefer instance field over construction context");
    }

    @Test
    void services_throwsWithoutContext_orInstanceField() {
        ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);

        // Ensure no context is set
        AbstractObjectInstance.CONSTRUCTION_CONTEXT.remove();

        // Create a simple object without context (bypassing managed path)
        AbstractObjectInstance obj = new AbstractObjectInstance(spawn, "Unmanaged") {
            @Override
            public void appendRenderCommands(java.util.List<com.openggf.graphics.GLCommand> commands) {}
        };

        IllegalStateException ex = assertThrows(IllegalStateException.class, obj::services,
                "services() should throw when neither instance field nor context is available");
        assertTrue(ex.getMessage().contains("services not available"),
                "Error message should indicate services are unavailable");
    }

    @Test
    void tryServices_returnsNullWithoutContext_orInstanceField() {
        ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);
        AbstractObjectInstance.CONSTRUCTION_CONTEXT.remove();
        OptionalServicesObject obj = new OptionalServicesObject(spawn);
        assertNull(obj.optionalServices());
    }

    @Test
    void tryServices_returnsConstructionContext_whenContextSet() {
        ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);
        AbstractObjectInstance.CONSTRUCTION_CONTEXT.set(TEST_SERVICES);
        try {
            OptionalServicesObject obj = new OptionalServicesObject(spawn);
            assertSame(TEST_SERVICES, obj.optionalServices());
        } finally {
            AbstractObjectInstance.CONSTRUCTION_CONTEXT.remove();
        }
    }

    @Test
    void tryServices_prefersInstanceField_afterSetServices() {
        ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);
        OptionalServicesObject obj = new OptionalServicesObject(spawn);
        ObjectServices instanceServices = new TestObjectServices();
        obj.setServices(instanceServices);
        assertSame(instanceServices, obj.optionalServices());
    }

    @Test
    void constructionContext_cleanedUpAfterFinally() {
        AbstractObjectInstance.CONSTRUCTION_CONTEXT.set(TEST_SERVICES);
        AbstractObjectInstance.CONSTRUCTION_CONTEXT.remove();

        assertNull(AbstractObjectInstance.CONSTRUCTION_CONTEXT.get(),
                "ThreadLocal should be null after remove()");
    }

    @Test
    void objectManager_createDynamicObject_setsConstructionContextBeforeFactory() {
        ObjectManager manager = new ObjectManager(
                List.of(), null, -1, null, null, null, null, TEST_SERVICES);
        ObjectSpawn spawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);

        ConstructorAccessObject obj = manager.createDynamicObject(
                () -> new ConstructorAccessObject(spawn));

        assertSame(TEST_SERVICES, obj.constructorServices,
                "factory-created object should see ObjectManager services during construction");
        assertSame(TEST_SERVICES, obj.services(),
                "ObjectManager should inject services after construction");
        assertNull(AbstractObjectInstance.CONSTRUCTION_CONTEXT.get(),
                "ObjectManager should clear construction context after factory returns");
        assertTrue(manager.getActiveObjects().contains(obj),
                "created object should be registered as a dynamic object");
    }
}

