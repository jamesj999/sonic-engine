package com.openggf.game.sonic3k.events;

import com.openggf.tests.HardwareBoundaryPump;
import com.openggf.tests.TestEnvironment;

import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameRng;
import com.openggf.game.GameServices;
import com.openggf.game.ScrollHandlerProvider;
import com.openggf.game.ScrollHandlerProvider.ZoneConstants;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.sonic3k.objects.HczTransitionBubbleInstance;
import com.openggf.game.sonic3k.scroll.SwScrlHcz;
import com.openggf.level.ParallaxManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.scroll.ZoneScrollHandler;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kHCZEvents {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
    }

    @AfterEach
    void tearDown() throws IOException {
        SessionManager.clear();
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        deleteRecursively(Path.of("saves").resolve("test_hcz_transition_save"));
    }

    @Test
    void act1TransitionWritesProgressionSaveForActiveSlot() throws Exception {
        SessionManager.clear();

        String gameCode = "test_hcz_transition_save";
        Path saveDir = Path.of("saves").resolve(gameCode);
        deleteRecursively(saveDir);

        GameModule sessionModule = spy(new Sonic3kGameModule());
        when(sessionModule.getSaveSnapshotProvider()).thenReturn((reason, ctx) -> Map.of("marker", "hcz_transition"));
        when(sessionModule.rngFlavour()).thenReturn(GameRng.Flavour.S3K);

        SaveSessionContext saveContext = SaveSessionContext.forSlot(
                gameCode, 1, new SelectedTeam("sonic", List.of("tails")), 1, 0);
        GameplayModeContext gameplayMode = SessionManager.openGameplaySession(sessionModule, saveContext);
        TestEnvironment.activeGameplayMode();

        Sonic3kHCZEvents events = new Sonic3kHCZEvents(() -> 0);
        events.init(0);
        events.setEventsFg5(true);
        GameServices.gameState().setEndOfLevelFlag(true);

        var timing = GameServices.hardwareTiming();
        serviceBoundary(HardwareServiceBoundary.VINT_SERVICE);
        serviceBoundary(HardwareServiceBoundary.PRE_MAIN_LOOP);
        events.update(0, 0);
        assertEquals(2,
                timing.incompleteCount(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE),
                "HCZ transition must queue chunks and blocks before its KosM art");
        serviceBoundary(HardwareServiceBoundary.POST_OBJECTS);
        assertFalse(events.isTransitionRequested());

        int publicationFrame = -1;
        int transitionFrame = -1;
        for (int frame = 1;
                frame < 100_000 && !events.isTransitionRequested();
                frame++) {
            // Kos_modules_left is decremented only by Process_Kos_Module_Queue
            // (docs/skdisasm/sonic3k.asm:2750-2752), which LevelLoop reaches at 7908 --
            // after ScreenEvents (7898) and the object pass (7900-7906). Neither
            // VINT_SERVICE nor Process_Kos_Queue (7887, decompression queue only) can
            // advance it.
            int beforeFrame = timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE);
            serviceBoundary(HardwareServiceBoundary.VINT_SERVICE);
            assertEquals(beforeFrame,
                    timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE),
                    "VINT_SERVICE does not run the module state step");
            serviceBoundary(HardwareServiceBoundary.PRE_MAIN_LOOP);
            assertEquals(beforeFrame,
                    timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE),
                    "Process_Kos_Queue (7887) advances only the decompression queue and "
                            + "must not retire an HCZ2 secondary art archive");
            events.update(0, frame);
            if (publicationFrame >= 0 && events.isTransitionRequested()) {
                transitionFrame = frame;
            }
            serviceBoundary(HardwareServiceBoundary.POST_OBJECTS);
            int afterState = timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE);
            assertTrue(afterState <= beforeFrame,
                    "module readiness must never regress across a LevelLoop iteration");
            if (beforeFrame > 0 && afterState == 0) {
                assertFalse(events.isTransitionRequested(),
                        "retirement alone cannot request the transition — only the "
                                + "ScreenEvents dispatch that observes it may");
                publicationFrame = frame;
            }
        }

        assertEquals(0, timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE));
        assertTrue(events.isTransitionRequested());
        assertEquals(publicationFrame + 1, transitionFrame,
                "Process_Kos_Module_Queue (sonic3k.asm:7908) runs after ScreenEvents "
                        + "(7898), so the retiring iteration's own dispatch cannot see "
                        + "the retirement — the next one is the first that may consume it");
        // In-game saves encode on the frame but write on the save-writer thread.
        com.openggf.game.save.SessionSaveRequests.flushPendingSaves();
        assertTrue(Files.exists(saveDir.resolve("slot1.json")));
    }

    private static void serviceBoundary(HardwareServiceBoundary boundary) {
        HardwareBoundaryPump.service(boundary);
    }

    @Test
    void act2WallChasePrimesBgCollisionCameraBeforePhysics() throws Exception {
        SwScrlHcz handler = new SwScrlHcz();
        installParallaxHandler(Sonic3kZoneIds.ZONE_HCZ, handler);

        AbstractPlayableSprite player = placePlayer(0x0B00, 0x0700);
        GameServices.camera().setX((short) 0x0800);
        GameServices.camera().setY((short) 0x0600);

        Sonic3kHCZEvents events = new Sonic3kHCZEvents(() -> 0);
        events.init(1);

        tickAct2(events, 0);
        assertEquals(SwScrlHcz.Hcz2BgPhase.WALL_CHASE, handler.getHcz2BgPhase());
        assertEquals(0x05FE, handler.getBgCameraX(),
                "wall init should fall through and subtract its first fast movement step");

        tickAct2(events, 1);
        assertTrue(GameServices.gameState().isBackgroundCollisionFlag(),
                "state 4 should gate Background_collision_flag on before physics");
        assertEquals(0x05FD, handler.getBgCameraX(),
                "the armed wall should continue its 16.16 movement on the next dispatch");

        player.setCentreX((short) 0x0B20);
        tickAct2(events, 2);
        assertEquals(0x05FC, handler.getBgCameraX(),
                "BG camera X should publish the current Events_bg+$00 high word");
        assertEquals((short) 0x0100, handler.getVscrollFactorBG(),
                "HCZ2 wall-chase BG camera Y should stay at cameraY - $500");
    }

    @Test
    void act2WallChaseConsumesFgEndSignalInSameFrame() throws Exception {
        SwScrlHcz handler = new SwScrlHcz();
        installParallaxHandler(Sonic3kZoneIds.ZONE_HCZ, handler);

        placePlayer(0x0B00, 0x0700);
        GameServices.camera().setX((short) 0x0800);
        GameServices.camera().setY((short) 0x0600);

        Sonic3kHCZEvents events = new Sonic3kHCZEvents(() -> 0);
        events.init(1);

        tickAct2(events, 0);
        tickAct2(events, 1);
        assertTrue(GameServices.gameState().isBackgroundCollisionFlag(),
                "wall chase should be active before the FG end signal frame");

        GameServices.camera().setX((short) 0x0C00);
        events.updatePrePhysics(1, 2);
        events.update(1, 2);

        assertTrue(events.isEventsFg5(), "FG end signal should be raised at Camera X >= $C00");
        assertFalse(GameServices.gameState().isBackgroundCollisionFlag(),
                "BG should consume Events_fg_5 in the same frame and clear background collision");
    }

    @Test
    void act2WallChaseUsesFastSpeedAtNativeThreshold() throws Exception {
        SwScrlHcz handler = new SwScrlHcz();
        installParallaxHandler(Sonic3kZoneIds.ZONE_HCZ, handler);

        placePlayer(0x0A88, 0x0700);
        GameServices.camera().setX((short) 0x0800);
        GameServices.camera().setY((short) 0x0600);

        Sonic3kHCZEvents events = new Sonic3kHCZEvents(() -> 0);
        events.init(1);
        tickAct2(events, 0);

        assertEquals(-0x14000, events.getWallOffsetFixed(),
                "ROM cmpi/blo selects the fast wall speed when Player_1 x_pos equals $A88");
    }

    @Test
    void postTransitionCarrierUsesNativePlayersAndFollowsRomArc() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x0100, (short) 0x07F0);
        GameServices.camera().setFocusedSprite(player);
        GameServices.camera().setX((short) 0x0080);
        player.setCpuControlled(false);
        player.setInWater(true);
        GameServices.sprites().addSprite(player, "sonic");
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x0100, (short) 0x07F8);
        sidekick.setCpuControlled(true);
        sidekick.setInWater(true);
        GameServices.sprites().addSprite(sidekick, "tails");
        TestablePlayableSprite secondSidekick = new TestablePlayableSprite("knuckles", (short) 0x0100, (short) 0x07F8);
        secondSidekick.setCpuControlled(true);
        secondSidekick.setInWater(true);
        GameServices.sprites().addSprite(secondSidekick, "knuckles");

        Sonic3kHCZEvents events = new Sonic3kHCZEvents(() -> 0);
        events.init(1);
        GameServices.water().setDynamicWaterLocked(Sonic3kZoneIds.ZONE_HCZ, 1, true);

        events.startPostTransitionCutscene();

        assertFalse(GameServices.water().isDynamicWaterLocked(Sonic3kZoneIds.ZONE_HCZ, 1),
                "loc_6A7C4 clears the miniboss water lock when a carrier initializes");
        assertCarrierControl(player);
        assertCarrierControl(sidekick);
        assertFalse(secondSidekick.isObjectControlled(),
                "ROM creates carriers only for the native P1/P2 slots");

        int playerStartX = player.getCentreX();
        int playerStartY = player.getCentreY();
        events.update(1, 0);
        assertEquals(playerStartX, player.getCentreX(),
                "loc_6A7C4 installs loc_6A872 without moving on its init dispatch");
        assertEquals(playerStartY, player.getCentreY());

        events.updateRetainedCarrierObjectPass(1);
        assertNotEquals(playerStartX, player.getCentreX());
        assertEquals(playerStartY + 2, player.getCentreY());

        for (int i = 0; i < 64 && events.isCutsceneActive(); i++) {
            events.updateRetainedCarrierObjectPass(1);
        }
        assertFalse(events.isCutsceneActive());
        assertPlainCarrierRelease(player);
        assertPlainCarrierRelease(sidekick);
        assertFalse(secondSidekick.isObjectControlled());
    }

    @Test
    void postTransitionControllerSpawnsOneBubbleForEachOfFortyEightDispatches() {
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x0100, (short) 0x07F0);
        player.setCpuControlled(false);
        player.setInWater(true);
        GameServices.camera().setFocusedSprite(player);
        GameServices.camera().setX((short) 0x0080);
        GameServices.sprites().addSprite(player, "sonic");
        int firstRandom = 0x0015_0081;
        AtomicInteger randomDraws = new AtomicInteger();
        IntSupplier randomWords = () -> {
            randomDraws.incrementAndGet();
            return firstRandom;
        };

        RecordingHczEvents events = new RecordingHczEvents(randomWords);
        events.init(1);
        events.startPostTransitionCutscene();

        events.updateRetainedCarrierObjectPass(1);
        assertEquals(0, events.transitionBubbleCount(),
                "the retained carrier init dispatch sets the controller flag for the next pass");

        for (int dispatch = 0; dispatch < 0x30; dispatch++) {
            events.updateRetainedCarrierObjectPass(1);
        }

        assertEquals(0x30, events.transitionBubbleCount(),
                "Obj_Wait $2F produces $30 loc_6A2A0 child-creation dispatches");
        assertEquals(0, events.getTransitionBubbleSpawnFrames());
        HczTransitionBubbleInstance firstBubble = events.firstTransitionBubble();
        int expectedAxisX = 0x0080 + 0xA0;
        assertEquals(expectedAxisX + (byte) firstRandom, firstBubble.getX());
        assertEquals(GameServices.water().getWaterLevelY(Sonic3kZoneIds.ZONE_HCZ, 1)
                        + 8 + ((firstRandom >>> 16) & 0x1F),
                firstBubble.getY());
        assertEquals(0x30, randomDraws.get(),
                "each child must consume exactly one injected Random_Number result");

        events.updateRetainedCarrierObjectPass(1);
        assertEquals(0x30, events.transitionBubbleCount());
    }

    private static void tickAct2(Sonic3kHCZEvents events, int frame) {
        events.updatePrePhysics(1, frame);
        events.update(1, frame);
    }

    private static AbstractPlayableSprite placePlayer(int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) x, (short) y);
        GameServices.camera().setFocusedSprite(player);
        return player;
    }

    private static void assertCarrierControl(AbstractPlayableSprite sprite) {
        assertTrue(sprite.isObjectControlled());
        assertTrue(sprite.isObjectControlAllowsCpu());
        assertTrue(sprite.isObjectControlSuppressesMovement());
        assertFalse(sprite.isControlLocked());
        assertTrue(sprite.getAir());
        assertEquals(Sonic3kAnimationIds.FLOAT2.id(), sprite.getForcedAnimationId());
        assertEquals((short) 0, sprite.getXSpeed());
        assertEquals((short) 0, sprite.getYSpeed());
        assertEquals((short) 0, sprite.getGSpeed());
    }

    private static void assertPlainCarrierRelease(AbstractPlayableSprite sprite) {
        assertFalse(sprite.isObjectControlled());
        assertFalse(sprite.isObjectControlAllowsCpu());
        assertFalse(sprite.isObjectControlSuppressesMovement());
        assertFalse(sprite.isControlLocked());
        assertFalse(sprite.getAir());
        assertEquals(-1, sprite.getForcedAnimationId());
        assertEquals(5, sprite.getAnimationId());
        assertEquals(0, sprite.getAnimationFrameIndex());
        assertEquals(0, sprite.getAnimationTick());
        assertEquals((short) 0, sprite.getXSpeed());
        assertEquals((short) 0, sprite.getYSpeed());
    }

    private static void installParallaxHandler(int zoneId, ZoneScrollHandler handler) throws Exception {
        ScrollHandlerProvider provider = new ScrollHandlerProvider() {
            @Override
            public void load(com.openggf.data.Rom rom) {
            }

            @Override
            public ZoneScrollHandler getHandler(int zoneIndex) {
                return zoneIndex == zoneId ? handler : null;
            }

            @Override
            public ZoneConstants getZoneConstants() {
                return mock(ZoneConstants.class);
            }
        };
        ParallaxManager parallaxManager = GameServices.parallax();
        Field field = ParallaxManager.class.getDeclaredField("scrollProvider");
        field.setAccessible(true);
        field.set(parallaxManager, provider);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static final class RecordingHczEvents extends Sonic3kHCZEvents {
        private final List<ObjectInstance> spawnedObjects = new ArrayList<>();

        private RecordingHczEvents(IntSupplier randomWordSource) {
            super(randomWordSource);
        }

        @Override
        protected <T extends ObjectInstance> T spawnObject(Supplier<T> factory) {
            T object = factory.get();
            spawnedObjects.add(object);
            return object;
        }

        private long transitionBubbleCount() {
            return spawnedObjects.stream()
                    .filter(HczTransitionBubbleInstance.class::isInstance)
                    .count();
        }

        private HczTransitionBubbleInstance firstTransitionBubble() {
            return spawnedObjects.stream()
                    .filter(HczTransitionBubbleInstance.class::isInstance)
                    .map(HczTransitionBubbleInstance.class::cast)
                    .findFirst()
                    .orElseThrow();
        }
    }
}
