package com.openggf.game.sonic3k.events;

import com.openggf.audio.AudioManager;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioCommandTimeline;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.tests.TestEnvironment;

import com.openggf.camera.Camera;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.SidekickSpawnOffset;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.Sonic3kLevel;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kLoadBootstrap;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingJob;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.sonic3k.objects.AizBattleshipInstance;
import com.openggf.game.sonic3k.objects.AizBgTreeInstance;
import com.openggf.game.sonic3k.objects.AizBgTreeSpawnerInstance;
import com.openggf.game.sonic3k.objects.AizCollapsingLogBridgeObjectInstance;
import com.openggf.game.sonic3k.objects.AizEndBossInstance;
import com.openggf.game.sonic3k.objects.AizEndBossWaterfallChild;
import com.openggf.game.sonic3k.objects.AizIntroArtLoader;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.level.Chunk;
import com.openggf.level.resources.LoadOp;
import com.openggf.level.resources.ResourceLoader;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.HardwareBoundaryPump;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.LogCaptureHandler;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceCharacterState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@RequiresRom(SonicGame.SONIC_3K)
public class TestSonic3kAIZEvents {
    private static final int HARDWARE_DRAIN_FRAME_LIMIT = 100_000;
    private static final Sonic3kLoadBootstrap FIRE_TRANSITION_BOOTSTRAP =
            new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, null);
    private HeadlessTestFixture fixture;

    private static Sonic3kAIZEvents newFireTransitionEvents() {
        AtomicInteger vblankCounter = new AtomicInteger();
        return new Sonic3kAIZEvents(FIRE_TRANSITION_BOOTSTRAP, vblankCounter::getAndIncrement);
    }

    private static RecordingAizEvents newRecordingFireTransitionEvents(AudioManager audio) {
        AtomicInteger vblankCounter = new AtomicInteger();
        return new RecordingAizEvents(
                FIRE_TRANSITION_BOOTSTRAP, vblankCounter, audio);
    }

    private static final class RecordingAizEvents extends Sonic3kAIZEvents {
        private final AudioManager audio;

        private RecordingAizEvents(
                Sonic3kLoadBootstrap bootstrap,
                AtomicInteger vblankCounter,
                AudioManager audio) {
            super(bootstrap, vblankCounter::getAndIncrement);
            this.audio = audio;
        }

        @Override
        protected AudioManager audio() {
            return audio;
        }
    }

    private static void updateWithHardware(
            Sonic3kAIZEvents events, int act, int frame) {
        serviceFireTransitionBoundary(HardwareServiceBoundary.VINT_SERVICE);
        serviceFireTransitionBoundary(HardwareServiceBoundary.PRE_MAIN_LOOP);
        events.update(act, frame);
        serviceFireTransitionBoundary(HardwareServiceBoundary.POST_OBJECTS);
    }

    private static void updateFireTransitionWithHardware(
            Sonic3kAIZEvents events, int act, int frame) {
        serviceFireTransitionBoundary(HardwareServiceBoundary.VINT_SERVICE);
        serviceFireTransitionBoundary(HardwareServiceBoundary.PRE_MAIN_LOOP);
        events.update(act, frame);
        serviceFireTransitionBoundary(HardwareServiceBoundary.POST_OBJECTS);
    }

    private static void serviceFireTransitionBoundary(
            HardwareServiceBoundary boundary) {
        HardwareBoundaryPump.service(boundary);
    }

    private static boolean hasActiveObject(Class<?> type) {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .anyMatch(type::isInstance);
    }

    private static void drainKosModuleHardware() {
        var timing = GameServices.hardwareTiming();
        int frames = 0;
        int incomplete = timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE);
        while (incomplete > 0 && frames++ < HARDWARE_DRAIN_FRAME_LIMIT) {
            // Kos_modules_left is decremented at exactly one site in the ROM:
            // Process_Kos_Module_Queue (docs/skdisasm/sonic3k.asm:2750-2752). LevelLoop
            // reaches it at 7908, immediately after the object pass (ExecuteObjects,
            // 7900-7906) — that is the POST_OBJECTS boundary. Process_Kos_Queue (7887)
            // services the *decompression* queue and cannot reach 2750, so PRE_MAIN_LOOP
            // must never retire a module archive.
            serviceFireTransitionBoundary(HardwareServiceBoundary.PRE_MAIN_LOOP);
            assertEquals(incomplete,
                    timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE),
                    "Process_Kos_Queue (sonic3k.asm:7887) must not publish KosM "
                            + "readiness — only Process_Kos_Module_Queue decrements "
                            + "Kos_modules_left (2750-2752)");
            serviceFireTransitionBoundary(HardwareServiceBoundary.POST_OBJECTS);
            int afterPost = timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE);
            assertTrue(afterPost <= incomplete,
                    "KosM readiness must never regress across a LevelLoop iteration");
            // One call to Process_Kos_Module_Queue per iteration, and each call takes at
            // most one of its two mutually exclusive branches: submit (2741, which only
            // sets bit 7) or DMA-and-decrement (2750-2752). The shift-out tail
            // (2778-2788) re-enters Process_Kos_Module_Queue_Init for the *next* archive
            // (2694-2713), which reloads Kos_modules_left rather than retiring another.
            assertTrue(incomplete - afterPost <= 1,
                    "at most one KosM archive may retire per LevelLoop iteration — "
                            + "Process_Kos_Module_Queue runs once at 7908 and decrements "
                            + "Kos_modules_left at most once per call");
            incomplete = afterPost;
        }
        assertEquals(0, incomplete,
                "the KosM queue must drain through its loop-tail state step");
    }

    @BeforeEach
    public void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        AizIntroArtLoader.reset();
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
    }

    @AfterEach
    public void tearDown() {
        AizIntroArtLoader.reset();
        SessionManager.clear();
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
    }

    @Test
    public void introArtFallbackDoesNotLogWarningsWhenRomBackedAssetsAreUnavailable() {
        Logger logger = Logger.getLogger(AizIntroArtLoader.class.getName());
        LogCaptureHandler handler = new LogCaptureHandler();
        boolean useParentHandlers = logger.getUseParentHandlers();
        Level previousLevel = logger.getLevel();
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        try {
            AizIntroArtLoader.reset();
            AizIntroArtLoader.loadAllIntroArt(new TestObjectServices());
            assertEquals(0, handler.countAtOrAbove(Level.WARNING));
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(useParentHandlers);
            logger.setLevel(previousLevel);
            AizIntroArtLoader.reset();
        }
    }

    @Test
    public void fireTransitionOnRomBackedAizLevelDoesNotLogWarnings() {
        Logger zoneLogger = Logger.getLogger(Sonic3kZoneEvents.class.getName());
        Logger introLogger = Logger.getLogger(AizPlaneIntroInstance.class.getName());
        LogCaptureHandler zoneHandler = new LogCaptureHandler();
        LogCaptureHandler introHandler = new LogCaptureHandler();
        boolean zoneUseParentHandlers = zoneLogger.getUseParentHandlers();
        boolean introUseParentHandlers = introLogger.getUseParentHandlers();
        Level previousZoneLevel = zoneLogger.getLevel();
        Level previousIntroLevel = introLogger.getLevel();
        zoneLogger.addHandler(zoneHandler);
        introLogger.addHandler(introHandler);
        zoneLogger.setUseParentHandlers(false);
        introLogger.setUseParentHandlers(false);
        zoneLogger.setLevel(Level.ALL);
        introLogger.setLevel(Level.ALL);
        try {
            Camera camera = GameServices.camera();
            camera.setX((short) 0x2F10);
            camera.setY((short) 0x0200);

            var events = newFireTransitionEvents();
            events.init(0);
            events.setEventsFg5(true);

            for (int i = 0; i < HARDWARE_DRAIN_FRAME_LIMIT
                    && !events.isAct2TransitionRequested(); i++) {
                updateFireTransitionWithHardware(events, 0, i);
            }

            assertTrue(events.isAct2TransitionRequested());
            assertEquals(0, zoneHandler.countAtOrAbove(Level.WARNING));
            assertEquals(0, introHandler.countAtOrAbove(Level.WARNING));
        } finally {
            zoneLogger.removeHandler(zoneHandler);
            introLogger.removeHandler(introHandler);
            zoneLogger.setUseParentHandlers(zoneUseParentHandlers);
            introLogger.setUseParentHandlers(introUseParentHandlers);
            zoneLogger.setLevel(previousZoneLevel);
            introLogger.setLevel(previousIntroLevel);
        }
    }

    @Test
    public void initWithIntroSkipDoesNotSpawnIntroObject() {
        Camera camera = GameServices.camera();
        var events = new Sonic3kAIZEvents(
                new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, null));
        events.init(0);
        assertEquals(0, events.getEventRoutine());
    }

    @Test
    public void initForAct1WithNormalBootstrapRequestsIntro() {
        Camera camera = GameServices.camera();
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        // When bootstrap is NORMAL and act is 0, intro should be requested
        assertTrue(events.shouldSpawnIntro(0));
    }

    @Test
    public void initForAct2DoesNotRequestIntro() {
        Camera camera = GameServices.camera();
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        assertFalse(events.shouldSpawnIntro(1));
    }

    @Test
    public void shakeSetupPublishesPriorOffsetBeforePreparingNextFrame() throws Exception {
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.setScreenShakeOffsetYRaw(3);
        events.setScreenShakeAppliedOffsetYRaw(0);
        events.setScreenShakeTimer(0);

        Method tick = Sonic3kAIZEvents.class.getDeclaredMethod("tickScreenShake");
        tick.setAccessible(true);
        tick.invoke(events);

        assertEquals(3, events.getScreenShakeOffsetY(),
                "AIZ2_ScreenEvent consumes the offset prepared by the prior background event");
        assertEquals(0, events.getScreenShakeOffsetYRaw(),
                "ShakeScreen_Setup prepares the following frame after ScreenEvents consumes the old value");
    }

    @Test
    public void act1ResizeLocksCameraMinXAtFirePaletteGate() {
        Camera camera = GameServices.camera();
        var events = new Sonic3kAIZEvents(
                new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, null));
        events.init(0);
        camera.setX((short) 0x2D80);
        camera.setY((short) 0x02E0);
        camera.setMinX((short) 0x1308);
        camera.setFrozen(true);
        assertTrue(AizPlaneIntroInstance.isMainLevelPhaseActive(), "test precondition: AIZ main-level phase is active");
        assertEquals(0x2D80, camera.getX() & 0xFFFF, "test precondition: camera is at the resize gate");

        updateWithHardware(events, 0, 0);

        assertEquals(0x2D80, camera.getMinX() & 0xFFFF,
                "AIZ1_Resize loc_1C594 writes Camera_min_X_pos=$2D80 at the fire palette gate");
    }

    @Test
    public void act1ResizeOwnerQueuesMainLevelKosmAndAppliesItsPreparedPayload() throws IOException {
        Camera camera = GameServices.camera();
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        camera.setX((short) 0x1400);
        camera.setFrozen(true);
        Sonic3kLevel level = (Sonic3kLevel) GameServices.level().getCurrentLevel();
        int expectedModuleSource = 0x3A944E;
        int expectedDirectSource =
                GameServices.rom().getRom().read32BitAddr(
                        Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                                + Sonic3kConstants.LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX
                                * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE
                                + 12) & 0x00FF_FFFF;
        ResourceLoader loader = new ResourceLoader(
                GameServices.rom().getRom());
        byte[] expectedDirectBlocks = loader.loadSingle(
                LoadOp.kosinskiBase(expectedDirectSource));
        byte[] expectedMainLevelPatterns = loader.loadSingle(
                LoadOp.kosinskiMBase(expectedModuleSource));
        int chunkStart = 0x0268 / Chunk.CHUNK_SIZE_IN_ROM;
        int chunkCount =
                expectedDirectBlocks.length / Chunk.CHUNK_SIZE_IN_ROM;
        int patternCount = expectedMainLevelPatterns.length
                / Pattern.PATTERN_SIZE_IN_ROM;
        int[][] chunksBeforePublication =
                snapshotChunkRange(level, chunkStart, chunkCount);
        byte[][] patternsBeforePublication =
                snapshotPatterns(level, 0x00BE, patternCount);
        int[][] expectedPublishedChunks = applyChunkPayload(
                chunksBeforePublication, expectedDirectBlocks);
        byte[][] expectedPublishedPatterns =
                decodePatternPayload(expectedMainLevelPatterns);
        assertFalse(Arrays.deepEquals(
                        chunksBeforePublication, expectedPublishedChunks),
                "test fixture must distinguish deferred main-level chunks from their payload");
        assertFalse(Arrays.deepEquals(
                        patternsBeforePublication,
                        expectedPublishedPatterns),
                "test fixture must distinguish deferred main-level patterns from their payload");

        events.update(0, 0);
        assert2dArrayEquals(
                chunksBeforePublication,
                snapshotChunkRange(level, chunkStart, chunkCount),
                "the queue-submission scan must preserve every deferred chunk byte");
        assertPatternRangeEquals(
                patternsBeforePublication, level, 0x00BE,
                "the queue-submission scan must preserve every deferred pattern byte");

        List<HardwareTimingJob.Snapshot> allJobs = GameServices.hardwareTiming()
                .capture().jobs();
        List<HardwareTimingJob.Snapshot> moduleJobs = allJobs.stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                .toList();
        List<HardwareTimingJob.Snapshot> directJobs = allJobs.stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                .toList();
        assertEquals(1, moduleJobs.size(),
                "AIZ1_Resize $1400 owner must submit its real Queue_Kos_Module job");
        assertEquals(1, directJobs.size(),
                "AIZ1_Resize $1400 owner must submit its real Queue_Kos block stream");
        assertEquals(expectedModuleSource,
                moduleJobs.getFirst().romSourceAddress());
        assertEquals(0x0BE * 32, moduleJobs.getFirst().destinationAddress());
        assertEquals(S3kKosRamDestinations.blockTableOffset(0x268),
                directJobs.getFirst().destinationAddress());
        assertEquals(expectedDirectSource,
                directJobs.getFirst().romSourceAddress());
        assertFalse(AizPlaneIntroInstance.isMainLevelPhaseActive(),
                "synchronous publication cannot shadow-decompress before KosM readiness");

        events.update(0, 1);
        assert2dArrayEquals(
                chunksBeforePublication,
                snapshotChunkRange(level, chunkStart, chunkCount),
                "repeat event scans before PRE must preserve every deferred chunk byte");
        assertPatternRangeEquals(
                patternsBeforePublication, level, 0x00BE,
                "repeat event scans before PRE must preserve every deferred pattern byte");
        assertEquals(1, GameServices.hardwareTiming()
                        .incompleteCount(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE),
                "repeated scans must not resubmit the AIZ direct stream");
        assertEquals(1, GameServices.hardwareTiming()
                        .incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE),
                "repeated scans must not resubmit the AIZ KosM parent");

        serviceHardware(HardwareServiceBoundary.POST_OBJECTS);
        assert2dArrayEquals(
                chunksBeforePublication,
                snapshotChunkRange(level, chunkStart, chunkCount),
                "POST before the direct fence must preserve every deferred chunk byte");
        assertPatternRangeEquals(
                patternsBeforePublication, level, 0x00BE,
                "POST before the module fence must preserve every deferred pattern byte");
        int frame = 2;
        while (S3kRuntimeArtCoordinator.current().directQueue().decompressionsPending()
                && frame < HARDWARE_DRAIN_FRAME_LIMIT) {
            serviceHardware(HardwareServiceBoundary.PRE_MAIN_LOOP);
            assert2dArrayEquals(
                    chunksBeforePublication,
                    snapshotChunkRange(level, chunkStart, chunkCount),
                    "direct PRE retirement must not publish any chunk prefix");
            assertPatternRangeEquals(
                    patternsBeforePublication, level, 0x00BE,
                    "direct PRE retirement must preserve the complete pattern range");
            if (S3kRuntimeArtCoordinator.current().directQueue().decompressionsPending()) {
                events.update(0, frame++);
                assert2dArrayEquals(
                        chunksBeforePublication,
                        snapshotChunkRange(level, chunkStart, chunkCount),
                        "intermediate direct consumer scans must preserve every deferred chunk byte");
                assertPatternRangeEquals(
                        patternsBeforePublication, level, 0x00BE,
                        "intermediate direct consumer scans must preserve every deferred pattern byte");
                serviceHardware(HardwareServiceBoundary.POST_OBJECTS);
                assert2dArrayEquals(
                        chunksBeforePublication,
                        snapshotChunkRange(level, chunkStart, chunkCount),
                        "intermediate POST scans must preserve every deferred chunk byte");
                assertPatternRangeEquals(
                        patternsBeforePublication, level, 0x00BE,
                        "intermediate POST scans must preserve every deferred pattern byte");
            }
        }
        assertFalse(S3kRuntimeArtCoordinator.current().directQueue().decompressionsPending(),
                "test setup must reach the final direct PRE retirement");
        assert2dArrayEquals(
                chunksBeforePublication,
                snapshotChunkRange(level, chunkStart, chunkCount),
                "final direct PRE retirement must preserve all chunks until the owning scan");
        assertPatternRangeEquals(
                patternsBeforePublication, level, 0x00BE,
                "final direct PRE retirement must preserve all patterns until their owning scan");
        assertFalse(AizPlaneIntroInstance.isMainLevelPhaseActive(),
                "direct readiness is consumer-visible only when the event scan runs");

        events.update(0, frame++);

        assertTrue(AizPlaneIntroInstance.isMainLevelPhaseActive(),
                "the first scan after final PRE retirement must publish the direct block overlay");
        assertChunkPayloadEquals(
                level, expectedDirectBlocks, chunkStart,
                "the direct-empty scan must publish every claimed 16x16 destination byte");
        int[][] chunksAfterPublication =
                snapshotChunkRange(level, chunkStart, chunkCount);
        assertFalse(events.isEventsFg5(),
                "the direct-empty scan must clear the intro Events_fg_5 redraw gate");
        assertFalse(events.isIntroNormalRefreshPending(),
                "the direct-empty scan must finish the intro redraw progression");
        assertTrue(events.isBoundariesUnlocked(),
                "the direct-empty scan must advance deformation/resize ownership");
        assertEquals(0, camera.getMinY() & 0xFFFF,
                "the direct-empty scan must publish the main-level Y boundary");
        assertPatternRangeEquals(
                patternsBeforePublication, level, 0x00BE,
                "KosM patterns must not publish during the direct-empty scan");

        // Bounded so a boundary model that stops advancing the module state step fails
        // fast instead of spinning forever. The AIZ1 archive retires in a handful of
        // frames; the limit only has to be far above that.
        int moduleFrames = 0;
        HardwareTimingJob.Snapshot parent = kosModuleParent();
        while (!parent.ready()) {
            assertTrue(moduleFrames++ < 4096,
                    "the AIZ1 KosM parent must retire within a bounded number of frames");
            serviceHardware(HardwareServiceBoundary.POST_OBJECTS);
            assert2dArrayEquals(
                    chunksAfterPublication,
                    snapshotChunkRange(level, chunkStart, chunkCount),
                    "module POST scans must not rewrite the published direct range");
            assertPatternRangeEquals(
                    patternsBeforePublication, level, 0x00BE,
                    "POST module work cannot become consumer-visible in the same frame");
            parent = kosModuleParent();
            if (parent.ready()) {
                break;
            }

            // Process_Kos_Module_Queue (docs/skdisasm/sonic3k.asm:7908) runs immediately
            // after the object pass, so the last module retires across POST_OBJECTS
            // above; Process_Kos_Queue (7887) follows it in the same loop tail and only
            // advances decompression. Readiness is still not art either way: neither
            // boundary may publish patterns, only a later consumer scan.
            serviceHardware(HardwareServiceBoundary.PRE_MAIN_LOOP);
            assert2dArrayEquals(
                    chunksAfterPublication,
                    snapshotChunkRange(level, chunkStart, chunkCount),
                    "module PRE scans must preserve the published direct range");
            assertPatternRangeEquals(
                    patternsBeforePublication, level, 0x00BE,
                    "module PRE scans must not publish any pattern prefix");
            parent = kosModuleParent();
            if (parent.ready()) {
                break;
            }

            events.update(0, frame++);
            assert2dArrayEquals(
                    chunksAfterPublication,
                    snapshotChunkRange(level, chunkStart, chunkCount),
                    "intermediate module consumer scans must preserve the direct range");
            assertPatternRangeEquals(
                    patternsBeforePublication, level, 0x00BE,
                    "intermediate KosM children must not publish partial pattern art");
        }
        assertFalse(parent.claimed(),
                "the retired KosM payload must remain owned until the following event scan");
        events.update(0, frame);
        assertTrue(GameServices.hardwareTiming().capture().jobs().stream()
                        .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                        .findFirst().orElseThrow().claimed(),
                "the scan after parent POST retirement must publish and claim the KosM payload");
        assert2dArrayEquals(
                chunksAfterPublication,
                snapshotChunkRange(level, chunkStart, chunkCount),
                "KosM publication must leave the direct range byte-exact");
        assertPatternRangeEquals(
                expectedPublishedPatterns, level, 0x00BE,
                "the KosM publication scan must expose every prepared pattern byte");
    }

    private static HardwareTimingJob.Snapshot kosModuleParent() {
        return GameServices.hardwareTiming().capture().jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                .findFirst().orElseThrow();
    }

    private static void serviceHardware(HardwareServiceBoundary boundary) {
        HardwareBoundaryPump.service(boundary);
    }

    @Test
    public void bossSmallCompletionReleasesBattleshipScrollLockFreeze() throws Exception {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x4640);
        camera.setMinX((short) 0x4640);
        camera.setMaxX((short) 0x4640);
        camera.setFrozen(false);
        camera.setHorizScrollDelay(32);

        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        setPrivateBoolean(events, "battleshipAutoScrollActive", true);

        events.updatePrePhysics(1);
        assertTrue(camera.getFrozen(), "AIZ2_DoShipLoop Scroll_lock should suppress the normal camera follow step");

        events.onBossSmallComplete();

        assertFalse(camera.getFrozen(),
                "Obj_AIZ2BossSmall clears Scroll_lock before writing Camera_max_X_pos=$6000");
        assertEquals(32, camera.getHorizScrollDelay(),
                "Scroll_lock must park H_scroll_frame_offset while the ship loop owns the camera");
        assertEquals(0x6000, camera.getMaxX() & 0xFFFF,
                "Obj_AIZ2BossSmall loc_50720 writes Camera_max_X_pos=$6000 on exit");
    }

    @Test
    public void postBombingBattleshipWrapUsesRomRepeatDistance() throws Exception {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x46BC);
        camera.setMinX((short) 0x46BC);
        camera.setMaxX((short) 0x46BC);
        camera.setFrozen(false);

        AbstractPlayableSprite sonic = fixture.sprite();
        sonic.setCentreX((short) 0x4762);

        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        setPrivateBoolean(events, "battleshipAutoScrollActive", true);
        setPrivateInt(events, "battleshipWrapX", 0x46C0);

        events.updatePrePhysics(1);

        assertEquals(0x44C0, camera.getX() & 0xFFFF,
                "AIZ2 post-bombing ship loop must subtract ROM Level_repeat_offset=$0200 at $46C0");
        assertEquals(0x4560, sonic.getCentreX() & 0xFFFF,
                "AIZ2 ship loop applies ROM Level_repeat_offset=$0200 to player x_pos; normal physics adds movement later");
    }

    @Test
    public void introObjectIsReadyBeforeFirstAizGameplayFrame() throws Exception {
        AizPlaneIntroInstance intro = AizPlaneIntroInstance.getActiveIntroInstance();
        assertNotNull(intro, "ROM SpawnLevelMainSprites installs Obj_AIZPlaneIntro before first Process_Sprites");
        assertFalse(GameServices.camera().isLevelStarted());
        assertEquals(0, intro.getRoutine(),
                "SpawnLevelMainSprites installs the object without dispatching its routine");
        assertEquals((short) 0xE918, introEventsFg1(intro),
                "the installed object retains its ROM initializer before Process_Sprites");
        assertTrue(GameServices.level().hasPendingInitialProcessSpritesPass());

        AbstractPlayableSprite sonic = fixture.sprite();
        assertEquals(0x0040, sonic.getCentreX() & 0xFFFF);
        assertEquals(0x0420, sonic.getCentreY() & 0xFFFF);
        assertFalse(sonic.isObjectControlled(),
                "object control belongs to the canonical setup dispatch, not object installation");

        List<AbstractPlayableSprite> sidekicks = GameServices.sprites().getRegisteredSidekicks();
        assertFalse(sidekicks.isEmpty(), "AIZ Sonic+Tails intro should spawn Player_2 before first frame");
        AbstractPlayableSprite tails = sidekicks.get(0);

        SidekickCpuController tailsCpu = tails.getCpuController();
        assertNotNull(tailsCpu);
        assertEquals(0x0020, tails.getCentreX() & 0xFFFF,
                "SpawnLevelMainSprites places Tails at Player_1-$20 before Tails_Control");
        assertEquals(0x0424, tails.getCentreY() & 0xFFFF);
        assertEquals(0, tails.getAnimationId());
        assertFalse(tails.isObjectControlled());
        assertFalse(tails.isObjectControlSuppressesMovement());
        assertEquals(0, tailsCpu.getDiagnosticRomCpuRoutine());
        assertFalse(tails.getAir());

        assertTrue(GameServices.level().consumePendingInitialProcessSpritesPass());

        assertEquals(2, intro.getRoutine());
        assertEquals((short) 0xE920, introEventsFg1(intro));
        assertTrue(sonic.isObjectControlled());
        assertEquals(0, sonic.getYSpeed() & 0xFFFF,
                "the canonical setup dispatch runs Obj_AIZPlaneIntro routine 0");
        assertFalse(sonic.getAir(),
                "Obj_AIZPlaneIntro routine 0 keeps Sonic grounded");
        assertEquals(0x0020, tails.getCentreX() & 0xFFFF,
                "initial Process_Sprites setup does not dispatch the later Player_2 slot");
        assertEquals(0x0424, tails.getCentreY() & 0xFFFF);
        assertEquals(2, TraceCharacterState.routineFromSprite(tails));
        assertEquals(0, tails.getAnimationId());
        assertFalse(tails.isObjectControlled());
        assertFalse(tails.isObjectControlSuppressesMovement());
        assertEquals(0x0020, sonic.getCentreX(1) & 0xFFFF,
                "Sonic_Init fills the shared position history from its temporary Player_2 centre");
        assertEquals(0x0424, sonic.getCentreY(1) & 0xFFFF);
        assertNotNull(tails.getCpuController());
        assertEquals(0, tailsCpu.getDiagnosticRomCpuRoutine());
        assertEquals(0, tails.getCpuController().targetX());
        assertEquals(0, tails.getCpuController().targetY());
        assertEquals(0, tails.getCpuController().getDiagnosticControlCounter());
        assertEquals(0, tails.getCpuController().getDiagnosticRespawnCounter());
        assertFalse(GameServices.level().consumePendingInitialProcessSpritesPass(),
                "setup authority is one-shot");

        sonic.recordFollowerHistoryForTick();
        sonic.clearFollowerHistoryRecordedFlag();

        assertEquals(0x0020, tails.getCentreX() & 0xFFFF,
                "the earlier Player 1 history boundary must not dispatch Player 2");
        assertEquals(0x0424, tails.getCentreY() & 0xFFFF);
        assertFalse(tails.isObjectControlled());
        assertEquals(0, tailsCpu.getDiagnosticRomCpuRoutine());

        GameServices.sprites().warmUpCpuSidekicksOnly(1, GameServices.level(), sonic);

        assertEquals(0x7F00, tails.getCentreX() & 0xFFFF);
        assertEquals(0, tails.getCentreY() & 0xFFFF);
        assertTrue(tails.isObjectControlled());
        assertFalse(tails.isObjectControlAllowsCpu());
        assertTrue(tails.isObjectControlSuppressesMovement());
        assertEquals(0x0A, tailsCpu.getDiagnosticRomCpuRoutine());
        assertTrue(tails.getAir());
    }

    @Test
    public void setupProcessSpritesAdvancesIntroScrollExactlyOnceBeforeLevelLoop()
            throws Exception {
        AizPlaneIntroInstance intro = AizPlaneIntroInstance.getActiveIntroInstance();
        assertNotNull(intro);
        AbstractPlayableSprite sonic = fixture.sprite();

        // SpawnLevelMainSprites installs the object, then the setup block runs
        // Process_Sprites exactly once before LevelLoop
        // (sonic3k.asm:7849-7855,8111-8128). Routine 0 initializes
        // Events_fg_1=$E918 and the common object tail adds scroll speed 8
        // (sonic3k.asm:135469-135475,135495-135508,135945-135956).
        assertEquals(0, intro.getRoutine());
        assertEquals((short) 0xE918, introEventsFg1(intro));
        assertTrue(GameServices.level().hasPendingInitialProcessSpritesPass());
        assertTrue(GameServices.level().consumePendingInitialProcessSpritesPass());
        assertEquals(2, intro.getRoutine());
        assertEquals((short) 0xE920, introEventsFg1(intro),
                "the production setup path must contribute exactly one intro accumulator update");
        assertFalse(GameServices.level().consumePendingInitialProcessSpritesPass());
        assertEquals((short) 0xE920, introEventsFg1(intro),
                "a second setup consume must be idempotent");

        // The next 430 native LevelLoop dispatches end on the accumulator's
        // negative-to-zero transition. Because the ROM tests bpl before adding,
        // Player_1 remains at $0040 on that update and moves by the live $40
        // field ($10 here) on the following dispatch.
        for (int levelLoopUpdate = 1; levelLoopUpdate <= 430; levelLoopUpdate++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        assertEquals(0, introEventsFg1(intro));
        assertEquals(0x0040, sonic.getCentreX() & 0xFFFF);
        assertEquals(0, sonic.getXSpeed());
        assertEquals(0, sonic.getYSpeed());
        assertEquals(0, sonic.getGSpeed());

        fixture.stepFrame(false, false, false, false, false);

        assertEquals(0x0050, sonic.getCentreX() & 0xFFFF,
                "the dispatch after Events_fg_1 reaches zero must move Player_1 by scroll speed");
    }

    @Test
    public void introSidekickDormantMarkerBeginsOnFirstOrdinaryPlayer2Dispatch() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();

        // Production order (Sonic3kLevelInitProfile):
        manager.initLevel(0, 0);                                           // step: initLevelEvents
        SidekickSpawnOffset offset = GameServices.module().getLevelInitProfile().sidekickSpawnOffset();
        GameServices.level().spawnSidekicks(offset.xOffset(), offset.yOffset()); // step: spawnSidekick
        manager.applyZonePlayerState();                                    // step: initZonePlayerState

        List<AbstractPlayableSprite> sidekicks = GameServices.sprites().getRegisteredSidekicks();
        assertFalse(sidekicks.isEmpty(), "AIZ Sonic+Tails intro should keep Player_2 registered");
        AbstractPlayableSprite tails = sidekicks.get(0);

        assertEquals(0x0020, tails.getCentreX() & 0xFFFF);
        assertEquals(0x0424, tails.getCentreY() & 0xFFFF);
        assertFalse(tails.isObjectControlled());
        assertFalse(tails.isObjectControlSuppressesMovement());
        assertEquals(0, tails.getCpuController().getDiagnosticRomCpuRoutine());
        assertFalse(tails.getAir());

        GameServices.sprites().warmUpCpuSidekicksOnly(
                1, GameServices.level(), fixture.sprite());

        assertEquals(0x7F00, tails.getCentreX() & 0xFFFF,
                "Tails_Control loc_13A10 parks Tails on her first ordinary dispatch");
        assertEquals(0, tails.getCentreY() & 0xFFFF);
        assertTrue(tails.isObjectControlled());
        assertFalse(tails.isObjectControlAllowsCpu());
        assertTrue(tails.isObjectControlSuppressesMovement());
        assertEquals(0x0A, tails.getCpuController().getDiagnosticRomCpuRoutine());
        assertTrue(tails.getAir());
    }

    @Test
    public void updateFallbackDoesNotDuplicateExistingIntroObject() {
        assertEquals(1, countActiveIntroObjects(),
                "ROM SpawnLevelMainSprites installs exactly one Obj_AIZPlaneIntro object");
        AizPlaneIntroInstance intro = GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(AizPlaneIntroInstance.class::isInstance)
                .map(AizPlaneIntroInstance.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(5, intro.getSlotIndex(),
                "SpawnLevelMainSprites writes the intro parent to Dynamic_object_RAM+2 (SST slot 5)");

        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        updateWithHardware(events, 0, 0);

        assertEquals(1, countActiveIntroObjects(),
                "AIZ intro update fallback must reuse the fixed intro object slot");
    }

    @Test
    public void fireCurtainStateIsInactiveOutsideTransition() {
        Camera camera = GameServices.camera();
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);

        FireCurtainRenderState state = events.getFireCurtainRenderState(224);
        assertFalse(state.active());
        assertEquals(0, state.coverHeightPx());
    }

    @Test
    public void eventsFg5StartsFireTransitionAndAppliesSeamlessFlow() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = newFireTransitionEvents();
        events.init(0);
        events.setEventsFg5(true);

        updateWithHardware(events, 0, 0);
        assertTrue(events.isFireTransitionActive());
        assertFalse(events.isAct2TransitionRequested());

        for (int i = 1; i < HARDWARE_DRAIN_FRAME_LIMIT
                && !events.isAct2TransitionRequested(); i++) {
            updateFireTransitionWithHardware(events, 0, i);
        }

        assertTrue(events.isAct2TransitionRequested());
        assertEquals(0, GameServices.level().getCurrentZone());
        assertEquals(1, GameServices.level().getCurrentAct());
        assertTrue(GameServices.level().getCurrentLevel() instanceof Sonic3kLevel);
        assertNull(GameServices.level().consumeSeamlessTransitionRequest());
    }

    @Test
    public void eventsFg5RunsFireRiseOnTheTriggeringBackgroundEventTick() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = newFireTransitionEvents();
        events.init(0);
        events.setEventsFg5(true);

        updateWithHardware(events, 0, 0);

        assertTrue(events.isFireTransitionActive());
        assertEquals(1, events.getFireTransitionFrames());
        assertEquals(0x0022_4000, events.getFireBgCopyFixed());
    }

    @Test
    public void fireMusicRestoreFollowsRomEscapeTimerAcrossActReload() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        AudioManager audio = mock(AudioManager.class);
        RecordingAizEvents events = newRecordingFireTransitionEvents(audio);
        events.init(0);
        events.setEventsFg5(true);
        AudioCommandTimeline audioTimeline = GameServices.audio().commandTimeline();
        int audioEntriesBeforeTransition = audioTimeline.entries().size();

        // AIZMinibossCutscene_StartEscape arms $120 and returns. The first
        // decrement occurs on the following object pass, and Restore_LevelMusic
        // runs only when that counter becomes negative. The act reload must
        // carry the remaining timer because the escape object is destroyed by
        // the reload.
        int frame = 0;
        while (!events.isAct2TransitionRequested()
                && frame < HARDWARE_DRAIN_FRAME_LIMIT) {
            updateFireTransitionWithHardware(events, 0, frame);
            frame++;
        }
        assertTrue(events.isAct2TransitionRequested());
        verify(audio, never()).playMusic(Sonic3kMusic.AIZ1.id);
        assertFalse(audioTimeline.entries().subList(audioEntriesBeforeTransition,
                        audioTimeline.entries().size()).stream()
                .map(entry -> entry.command())
                .anyMatch(command -> command instanceof AudioCommand.PlayMusic play
                        && play.musicId() == Sonic3kMusic.AIZ1.id),
                "AIZ1 music must not be started by the act reload");

        events.init(1);
        for (int continuationFrame = 0; continuationFrame <= 0x120; continuationFrame++) {
            updateFireTransitionWithHardware(events, 1, frame + continuationFrame);
        }
        verify(audio).playMusic(Sonic3kMusic.AIZ2.id);
    }

    @Test
    public void eventsFg5TransitionWritesProgressionSaveForActiveSlot() throws Exception {
        SessionManager.clear();

        String gameCode = "test_aiz_transition_save";
        Path saveDir = Path.of("saves").resolve(gameCode);
        deleteRecursively(saveDir);

        Sonic3kGameModule sessionModule = new Sonic3kGameModule() {
            @Override
            public com.openggf.game.save.SaveSnapshotProvider getSaveSnapshotProvider() {
                return (reason, ctx) -> Map.of("marker", "aiz_transition");
            }
        };

        SaveSessionContext saveContext = SaveSessionContext.forSlot(
                gameCode, 1, new SelectedTeam("sonic", List.of("tails")), 0, 0);
        SessionManager.openGameplaySession(sessionModule, saveContext);
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = newFireTransitionEvents();
        events.init(0);
        events.setEventsFg5(true);

        for (int i = 1; i < HARDWARE_DRAIN_FRAME_LIMIT
                && !events.isAct2TransitionRequested(); i++) {
            updateWithHardware(events, 0, i);
        }

        assertTrue(events.isAct2TransitionRequested());
        com.openggf.game.save.SessionSaveRequests.flushPendingSaves(); // in-game saves write off-frame
        assertTrue(Files.exists(saveDir.resolve("slot1.json")));
        deleteRecursively(saveDir);
    }

    @Test
    public void fireTransitionAppliesMutationBeforeActReload() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        events.setEventsFg5(true);

        boolean sawMutationBeforeReload = false;
        for (int i = 0; i < 260 && !events.isAct2TransitionRequested(); i++) {
            updateWithHardware(events, 0, i);
            if (events.isFireTransitionActive()
                    && !events.isAct2TransitionRequested()
                    && events.getFireTransitionBgY() >= 0x190) {
                sawMutationBeforeReload = true;
            }
        }

        assertTrue(sawMutationBeforeReload, "Expected mutation applied during fire transition before reload");
    }

    @Test
    public void fireTransitionKeepsLiveTerrainTablesUntilActReload() {
        Sonic3kLevel level = (Sonic3kLevel) GameServices.level().getCurrentLevel();

        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        events.setEventsFg5(true);

        int[][] blocksBefore = snapshotBlocks(level);
        int[][] chunksBefore = snapshotChunks(level);

        boolean reachedFireMutationHandoff = false;
        for (int i = 0; i < 260 && !events.isAct2TransitionRequested(); i++) {
            updateWithHardware(events, 0, i);
            if (events.isFireTransitionActive() && events.getFireTransitionBgY() >= 0x190) {
                reachedFireMutationHandoff = true;
                break;
            }
        }

        assertTrue(reachedFireMutationHandoff, "Expected AIZ1 fire mutation handoff before act reload");
        assert2dArrayEquals(blocksBefore, snapshotBlocks(level),
                "AIZ1 fire handoff must not expose AIZ2 block terrain before Load_Level/LoadSolids");
        assert2dArrayEquals(chunksBefore, snapshotChunks(level),
                "AIZ1 fire handoff must not expose AIZ2 chunk terrain before Load_Level/LoadSolids");
    }

    @Test
    public void fireTransitionPublishesClaimedAct2ArtOnlyAfterPostReadiness() {
        Sonic3kLevel level = (Sonic3kLevel) GameServices.level().getCurrentLevel();
        int transitionArtTileCount = Math.min(level.getPatternCount(), 0x1FC + 237);
        byte[][] patternsBefore = snapshotPatterns(level, 0, transitionArtTileCount);

        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = newFireTransitionEvents();
        events.init(0);
        events.setEventsFg5(true);

        for (int frame = 0; frame < 320
                && !events.isFireTransitionMutationRequested(); frame++) {
            updateWithHardware(events, 0, frame);
        }

        assertTrue(events.isFireTransitionMutationRequested(),
                "Expected the fire handoff to queue the AIZ2 KosM overlays");
        assertPatternRangeEquals(patternsBefore, level, 0,
                "Queuing AIZ2 KosM overlays must not publish a synchronous decompression shadow");

        drainKosModuleHardware();

        List<HardwareTimingJob.Snapshot> readyOverlays =
                GameServices.hardwareTiming().capture().jobs().stream()
                        .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                        .filter(job -> !job.claimed())
                        .filter(job -> job.destinationAddress() == 0
                                || job.destinationAddress() == 0x1FC * Pattern.PATTERN_SIZE_IN_ROM)
                        .toList();
        assertEquals(2, readyOverlays.size());
        assertTrue(readyOverlays.stream().allMatch(HardwareTimingJob.Snapshot::ready),
                "Both AIZ2 KosM payloads must cross POST readiness before publication");
        assertPatternRangeEquals(patternsBefore, level, 0,
                "Ready-but-unclaimed AIZ2 KosM payloads must remain invisible");

        HardwareTimingJob.Snapshot primary = readyOverlays.stream()
                .filter(job -> job.destinationAddress() == 0)
                .findFirst()
                .orElseThrow();
        HardwareTimingJob.Snapshot secondary = readyOverlays.stream()
                .filter(job -> job.destinationAddress() == 0x1FC * Pattern.PATTERN_SIZE_IN_ROM)
                .findFirst()
                .orElseThrow();

        for (int frame = 320; frame < 640 && !events.isAct2TransitionRequested(); frame++) {
            assertPatternRangeEquals(patternsBefore, level, 0,
                    "The owner must retain both ready handles until its publication dispatch");
            updateWithHardware(events, 0, frame);
        }

        assertTrue(events.isAct2TransitionRequested());
        assertPatternPayloadEquals(level, primary.preparedPayload(), 0);
        assertPatternPayloadEquals(level, secondary.preparedPayload(), 0x1FC);

        java.util.Map<Long, HardwareTimingJob.Snapshot> overlaysAfterClaim =
                GameServices.hardwareTiming().capture().jobs().stream()
                        .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                        .collect(java.util.stream.Collectors.toMap(
                                job -> job.handle().ordinal(), job -> job));
        assertTrue(overlaysAfterClaim.get(primary.handle().ordinal()).claimed());
        assertTrue(overlaysAfterClaim.get(secondary.handle().ordinal()).claimed());
    }

    @Test
    public void postFireHazeOnlyEnablesAfterBurnHandoff() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = newFireTransitionEvents();
        events.init(0);
        assertFalse(events.isPostFireHazeActive());

        events.setEventsFg5(true);
        updateFireTransitionWithHardware(events, 0, 0);
        assertFalse(events.isPostFireHazeActive());

        for (int i = 1; i < HARDWARE_DRAIN_FRAME_LIMIT
                && !events.isAct2TransitionRequested(); i++) {
            updateFireTransitionWithHardware(events, 0, i);
        }

        assertTrue(events.isAct2TransitionRequested());
        assertFalse(events.isPostFireHazeActive());

        var act2Events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        act2Events.init(1);
        assertFalse(act2Events.isPostFireHazeActive());

        for (int i = 0; i < 240 && !act2Events.isPostFireHazeActive(); i++) {
            updateWithHardware(act2Events, 1, i);
        }
        assertTrue(act2Events.isPostFireHazeActive());
    }

    @Test
    public void fireCurtainRenderStateCarriesAcrossSeamlessReload() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = newFireTransitionEvents();
        events.init(0);
        events.setEventsFg5(true);

        for (int i = 0; i < HARDWARE_DRAIN_FRAME_LIMIT
                && !events.isAct2TransitionRequested(); i++) {
            updateFireTransitionWithHardware(events, 0, i);
        }

        assertTrue(events.isAct2TransitionRequested());
        FireCurtainRenderState beforeReload = events.getFireCurtainRenderState(224);
        assertTrue(beforeReload.active());
        assertEquals(224, beforeReload.coverHeightPx());
        // sourceWorldX cycles through 0x1000..0x1060 matching ROM's Camera_X_pos_BG_copy
        assertTrue(beforeReload.sourceWorldX() >= 0x1000 && beforeReload.sourceWorldX() <= 0x1060, "sourceWorldX should be in cycling range");

        var act2Events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        act2Events.init(1);

        FireCurtainRenderState afterReload = act2Events.getFireCurtainRenderState(224);
        assertTrue(afterReload.active());
        assertEquals(224, afterReload.coverHeightPx());
        assertEquals(beforeReload.wavePhase(), afterReload.wavePhase());
        // ROM AIZ1BGE_Finish performs the whole act reload without ever writing
        // Camera_Y_pos_BG_copy (sonic3k.asm:104727-104802), so the AIZ1_FireRise
        // ramp carries across the reload untouched; AIZ2BGE_WaitFire's
        // $180 + (bgY & $7F) re-seat (sonic3k.asm:105070-105076) is the only
        // thing that ever brings it back into the fire zone.
        assertEquals(beforeReload.sourceWorldY(), afterReload.sourceWorldY());
        assertEquals(FireCurtainStage.AIZ2_REDRAW, afterReload.stage());
    }

    @Test
    public void fireCurtainIsFullScreenWhenFireMutationStarts() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        events.setEventsFg5(true);

        FireCurtainRenderState state = FireCurtainRenderState.inactive();
        for (int i = 0; i < 320 && events.getFireTransitionBgY() < 0x190; i++) {
            updateWithHardware(events, 0, i);
            state = events.getFireCurtainRenderState(224);
        }

        assertTrue(state.active());
        assertTrue(events.getFireTransitionBgY() >= 0x190);
        assertEquals(224, state.coverHeightPx(), "Curtain should fully cover the screen by the mutation handoff");
        assertEquals(FireCurtainStage.AIZ1_REFRESH, state.stage());
    }

    @Test
    public void fireCurtainCoverHeightIsMonotonicDuringRise() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        events.setEventsFg5(true);

        int previous = 0;
        for (int i = 0; i < 80 && !events.isAct2TransitionRequested(); i++) {
            updateWithHardware(events, 0, i);
            FireCurtainRenderState state = events.getFireCurtainRenderState(224);
            if (!state.active() || (state.stage() != FireCurtainStage.AIZ1_RISING
                    && state.stage() != FireCurtainStage.AIZ1_REFRESH)) {
                continue;
            }
            assertTrue(state.coverHeightPx() >= previous, "cover height regressed at frame " + i);
            previous = state.coverHeightPx();
        }
    }

    @Test
    public void fireCurtainStartsImmediatelyAndReachesFullCoverByMutation() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        events.setEventsFg5(true);

        updateWithHardware(events, 0, 0);
        FireCurtainRenderState initial = events.getFireCurtainRenderState(224);
        assertTrue(initial.active());
        // First frame just starts the transition (bgY=0x20); fire tiles at BG Y >= 0x100
        // are not visible yet.  The rise advances on subsequent frames.
        assertTrue(initial.coverHeightPx() >= 0, "Curtain should be active on first frame");

        // The lerp phase slowly converges bgY toward 0x68 (1/32 per frame).
        // Fire tiles start at BG Y=0x100, so cover = bgY + 224 - 0x100.
        // After ~10 frames bgY reaches ~0x30 and cover exceeds 16.
        FireCurtainRenderState state = initial;
        int i = 1;
        for (; i < 15; i++) {
            updateWithHardware(events, 0, i);
            state = events.getFireCurtainRenderState(224);
        }
        assertTrue(state.coverHeightPx() >= 16, "Curtain should begin covering within the lerp phase");

        for (; i < 240 && state.stage() == FireCurtainStage.AIZ1_RISING; i++) {
            updateWithHardware(events, 0, i);
            state = events.getFireCurtainRenderState(224);
        }

        assertEquals(224, state.coverHeightPx(), "Curtain should be fully screen-covering by the mutation handoff");
        assertTrue(state.stage() == FireCurtainStage.AIZ1_REFRESH
                || state.stage() == FireCurtainStage.AIZ1_FINISH);
    }

    @Test
    public void fireCurtainStateExposesDeterministicTwentyColumnWaveData() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        events.setEventsFg5(true);
        updateWithHardware(events, 0, 8);

        FireCurtainRenderState state = events.getFireCurtainRenderState(224);
        assertTrue(state.active());
        assertEquals(20, state.columnWaveOffsetsPx().length);

        boolean hasVariation = false;
        int first = state.columnWaveOffsetsPx()[0];
        for (int i = 1; i < state.columnWaveOffsetsPx().length; i++) {
            if (state.columnWaveOffsetsPx()[i] != first) {
                hasVariation = true;
                break;
            }
        }
        assertTrue(hasVariation, "Expected wavy fire-column offsets");
    }

    @Test
    public void fireCurtainHandoffAccessorIsPureWithinTheSameFrame() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        events.setEventsFg5(true);

        for (int i = 0; i < HARDWARE_DRAIN_FRAME_LIMIT
                && !events.isAct2TransitionRequested(); i++) {
            updateWithHardware(events, 0, i);
        }

        var act2Events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        act2Events.init(1);

        FireCurtainRenderState a = act2Events.getFireCurtainRenderState(224);
        FireCurtainRenderState b = act2Events.getFireCurtainRenderState(224);

        assertEquals(a.coverHeightPx(), b.coverHeightPx());
        assertEquals(a.wavePhase(), b.wavePhase());
        assertEquals(a.frameCounter(), b.frameCounter());
    }

    @Test
    public void act2ContinuationKeepsCurtainUntilWaitFireFinishes() {
        Camera camera = GameServices.camera();
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);

        var act1Events = newFireTransitionEvents();
        act1Events.init(0);
        act1Events.setEventsFg5(true);
        for (int i = 0; i < HARDWARE_DRAIN_FRAME_LIMIT
                && !act1Events.isAct2TransitionRequested(); i++) {
            updateFireTransitionWithHardware(act1Events, 0, i);
        }

        var act2Events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        act2Events.init(1);

        FireCurtainRenderState state = act2Events.getFireCurtainRenderState(224);
        assertTrue(state.active());
        assertEquals(FireCurtainStage.AIZ2_REDRAW, state.stage());

        boolean sawWaitFire = false;
        boolean sawUnlatchedWaitFire = false;
        boolean sawLatchedWaitFire = false;
        boolean sawAiz2SourceStrip = false;
        int latchedWaitFireSourceY = -1;
        for (int i = 0; i < 240 && act2Events.getFireCurtainRenderState(224).active(); i++) {
            updateWithHardware(act2Events, 1, i);
            state = act2Events.getFireCurtainRenderState(224);
            if (state.stage() == FireCurtainStage.AIZ2_WAIT_FIRE) {
                sawWaitFire = true;
                if (state.sourceWorldX() == 0x0200) {
                    sawLatchedWaitFire = true;
                    sawAiz2SourceStrip = true;
                    latchedWaitFireSourceY = state.sourceWorldY();
                    assertFalse(state.wrapFireTiles(),
                            "The latched WaitFire outro must stop wrapping the cached fire body");
                } else {
                    sawUnlatchedWaitFire = true;
                    assertTrue(state.wrapFireTiles(),
                            "The pre-latch WaitFire continuation must keep the cached plane wrapped");
                }
            }
        }

        assertTrue(sawWaitFire, "Expected to reach AIZ2 WaitFire continuation");
        assertTrue(sawUnlatchedWaitFire, "Expected to observe WaitFire before its ROM release latch");
        assertTrue(sawLatchedWaitFire, "Expected to observe WaitFire after its ROM release latch");
        assertTrue(sawAiz2SourceStrip, "Expected WaitFire to switch to the $200 source strip");
        assertTrue(latchedWaitFireSourceY >= 0x180 && latchedWaitFireSourceY < 0x310,
                "The latched WaitFire source Y must remain in the ROM's finite release interval");
        assertFalse(act2Events.getFireCurtainRenderState(224).active(), "Curtain should eventually clear after AIZ2 WaitFire");
    }

    /**
     * When arriving at AIZ2 through the AIZ1 fire transition, SonicResize1
     * must NOT skip the miniboss path (SonicResize2). The ROM gates this on
     * Apparent_zone_and_act != AIZ2 â€” during the fire transition, the apparent
     * zone is still AIZ1.
     *
     * This was the root cause of the "AIZ1 mid-act transition snapping" bug:
     * unconditionally setting Camera_min_X_pos = $F50 at cameraX >= $2E0
     * snapped the player to the miniboss arena immediately after the spikes.
     */
    @Test
    public void aiz2FromFireTransitionDoesNotSkipMinibossPath() {
        Camera camera = GameServices.camera();

        // Simulate arrival from AIZ1 fire transition: run act 1 fire sequence
        camera.setX((short) 0x2F10);
        camera.setY((short) 0x0200);
        var act1Events = newFireTransitionEvents();
        act1Events.init(0);
        act1Events.setEventsFg5(true);
        for (int i = 0; i < HARDWARE_DRAIN_FRAME_LIMIT
                && !act1Events.isAct2TransitionRequested(); i++) {
            updateFireTransitionWithHardware(act1Events, 0, i);
        }
        assertTrue(act1Events.isAct2TransitionRequested(), "Fire transition should have requested act 2");

        // Begin act 2 with pending fire sequence (came from fire transition)
        var act2Events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        act2Events.init(1);

        // Simulate player past the first spikes â€” cameraX just past $2E0
        camera.setX((short) 0x0300);
        camera.setY((short) 0x0200);
        camera.setMinX((short) 0);

        // Run update to trigger SonicResize1
        // Wait for the fire curtain to clear first so resize runs
        for (int i = 0; i < 240; i++) {
            updateWithHardware(act2Events, 1, i);
        }

        // minX must NOT have been snapped to $F50 (the miniboss lock)
        int minX = camera.getMinX() & 0xFFFF;
        assertTrue(minX < 0x0F50, "Camera minX should NOT be locked to miniboss area ($F50) after fire transition, was 0x"
                + Integer.toHexString(minX));
    }

    /**
     * When entering AIZ2 directly (level select / death restart), SonicResize1
     * SHOULD skip the miniboss path because the miniboss has already been defeated.
     * ROM: Apparent_zone_and_act == AIZ2 â†’ skip to SonicResize3.
     */
    @Test
    public void aiz2DirectEntrySkipsMinibossPath() {
        Camera camera = GameServices.camera();

        // Direct entry: no pending fire sequence
        Sonic3kAIZEvents.resetGlobalState();
        // ROM: LevelSelect_StartZone (sonic3k.asm:10222) and
        // Load_Starpost_Settings (sonic3k.asm:61760) set Apparent_zone_and_act
        // = $0001 for direct AIZ2 entry; the engine mirrors this through
        // LevelManager.setApparentAct.
        GameServices.level().setApparentAct(1);
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(1);  // Act 2 directly, no fire transition

        // Camera past $2E0 (first spikes area)
        camera.setX((short) 0x0300);
        camera.setY((short) 0x0200);
        camera.setMinX((short) 0);

        // Run update to trigger SonicResize1
        updateWithHardware(events, 1, 0);

        // minX SHOULD be set to $F50 (skipping miniboss area)
        int minX = camera.getMinX() & 0xFFFF;
        assertEquals(0x0F50, minX, "Camera minX should be locked to $F50 for direct AIZ2 entry");
    }

    /**
     * Reload-resume gating: when AIZ2 is loaded with no pending fire sequence
     * but apparentAct == 0 (e.g., trace reload-resume after the AIZ1 fire
     * transition was committed), SonicResize1 must NOT skip the miniboss
     * path.  This is the regression caught by the AIZ trace at F7171:
     * the old heuristic set enteredAsAct2 = true whenever
     * pendingFireSequence == null, which flipped the engine into the
     * post-miniboss branch even though ROM's Apparent_zone_and_act stayed
     * at 0.  ROM cite: sonic3k.asm:39046-39058 (AIZ2_SonicResize1).
     */
    @Test
    public void aiz2ReloadResumeWithApparentAct0DoesNotSkipMinibossPath() {
        Camera camera = GameServices.camera();

        Sonic3kAIZEvents.resetGlobalState();
        // ROM: AIZ1_AIZ2_Transition (sonic3k.asm:104627) does not write
        // Apparent_zone_and_act; it stays at AIZ1=$0000 across the
        // continuation.  The engine's seamless transition coordinator
        // preserves apparentAct, matching this.
        GameServices.level().setApparentAct(0);
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(1); // Act 2 reload-resume, no pending fire sequence

        camera.setX((short) 0x0300);
        camera.setY((short) 0x0200);
        camera.setMinX((short) 0);

        updateWithHardware(events, 1, 0);

        int minX = camera.getMinX() & 0xFFFF;
        assertTrue(minX < 0x0F50,
                "Camera minX must NOT be locked to miniboss area on reload-resume with apparentAct=0, was 0x"
                        + Integer.toHexString(minX));
    }

    @Test
    public void aiz2BattleshipBombingStartsFromEventsFg4Handoff() {
        Camera camera = GameServices.camera();
        Sonic3kAIZEvents.resetGlobalState();
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(1);

        camera.setX((short) 0x3C00);
        camera.setY((short) 0x0200);
        events.setDynamicResizeRoutine(8);
        updateWithHardware(events, 1, 0);
        assertEquals(0x0A, events.getDynamicResizeRoutine(), "Stage $08 should only prepare battleship art");
        assertTrue(events.isEventsFg5(), "Stage $08 should raise Events_fg_5 for BG setup");
        assertFalse(events.isEventsFg4(), "Stage $08 must not trigger the bombing screen event");
        assertFalse(events.isBattleshipAutoScrollActive(), "Bombing should not start at the art-load gate");
        List<HardwareTimingJob.Snapshot> resizeJobs = GameServices.hardwareTiming()
                .capture().jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                .toList();
        assertEquals(2, resizeJobs.size(),
                "AIZ2_SonicResize4 must own both battleship module submissions");
        assertEquals(0x3B48C6, resizeJobs.get(0).romSourceAddress());
        assertEquals(0x1FC * 32, resizeJobs.get(0).destinationAddress());
        assertEquals(0x399CC4, resizeJobs.get(1).romSourceAddress());
        assertEquals(0x500 * 32, resizeJobs.get(1).destinationAddress());
        assertFalse(events.isBattleshipTerrainLoaded(),
                "terrain publication must await the prepared resize-owner payload");

        camera.setX((short) 0x4000);
        updateWithHardware(events, 1, 1);
        assertEquals(0x0C, events.getDynamicResizeRoutine(), "Stage $0A should lock min Y");
        assertFalse(events.isEventsFg4(), "Stage $0A must not trigger the bombing screen event");
        assertFalse(events.isBattleshipAutoScrollActive(), "Bombing should not start at the vertical-lock gate");

        updateWithHardware(events, 1, 2);
        assertEquals(0x0E, events.getDynamicResizeRoutine(), "Stage $0C should lock max Y");
        assertFalse(events.isEventsFg4(), "Stage $0C must not trigger the bombing screen event");
        assertFalse(events.isBattleshipAutoScrollActive(), "Bombing should wait for the $4160 gate");

        camera.setX((short) 0x4160);
        updateWithHardware(events, 1, 3);
        assertEquals(0x10, events.getDynamicResizeRoutine(), "Stage $0E should advance to terminal state");
        assertFalse(events.isEventsFg4(), "AIZ2_ScreenEvent should consume Events_fg_4 in the same frame");
        assertTrue(events.isBattleshipAutoScrollActive(), "AIZ2_ScreenEvent should start the bombing sequence");
        assertEquals(0x4160, camera.getX() & 0xFFFF,
                "ScreenEvents should arm SpecialEvents without running the ship loop in the same frame");
        var objectManager = GameServices.level().getObjectManager();
        assertFalse(objectManager.getActiveObjects().stream()
                        .anyMatch(AizBattleshipInstance.class::isInstance),
                "AIZ2SE_ShipRefresh must finish on the following ScreenEvents pass before allocating the ship");

        events.updatePrePhysics(1);
        assertEquals(0x4164, camera.getX() & 0xFFFF,
                "the following SpecialEvents pass should perform the first +4 ship-loop step");

        updateWithHardware(events, 1, 4);
        assertFalse(events.isEventsFg4(), "AIZ2_ScreenEvent should consume Events_fg_4");
        assertTrue(events.isBattleshipAutoScrollActive(), "Battleship bombing should remain active after the handoff");
        assertTrue(objectManager.getActiveObjects().stream()
                        .anyMatch(AizBattleshipInstance.class::isInstance),
                "the completed ShipRefresh pass should allocate the battleship");
        assertTrue(GameServices.hardwareTiming().incompleteCount(
                        HardwareWorkKind.KOS_MODULE_QUEUE) > 0,
                "ShipRefresh allocation is independent of the still-running KosM art owner");
    }

    @Test
    public void aiz2BattleshipRemainsActiveAfterScreenEventSpawnsIt() {
        Camera camera = GameServices.camera();
        Sonic3kAIZEvents.resetGlobalState();
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(1);

        camera.setX((short) 0x4160);
        camera.setY((short) 0x0200);
        events.setDynamicResizeRoutine(0x0E);
        updateWithHardware(events, 1, 0);
        updateWithHardware(events, 1, 1);

        var objectManager = GameServices.level().getObjectManager();
        assertTrue(objectManager.getActiveObjects().stream()
                        .anyMatch(AizBattleshipInstance.class::isInstance),
                "Screen event should spawn the battleship object");

        objectManager.update(camera.getX(), null, List.of(), 2, false);

        assertTrue(objectManager.getActiveObjects().stream()
                        .anyMatch(object -> object instanceof AizBattleshipInstance ship && !ship.isDestroyed()),
                "Battleship must survive normal object processing while it scrolls in from the sky");
    }

    @Test
    public void aiz2PostBombingShipLoopUsesRomRepeatOffset() throws Exception {
        Camera camera = GameServices.camera();
        Sonic3kAIZEvents.resetGlobalState();
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(1);
        setPrivateBoolean(events, "battleshipAutoScrollActive", true);
        events.onBattleshipComplete();

        AbstractPlayableSprite sonic = fixture.sprite();
        camera.setFocusedSprite(sonic);
        List<AbstractPlayableSprite> sidekicks = GameServices.sprites().getSidekicks();
        assertFalse(sidekicks.isEmpty(), "test precondition: AIZ fixture should include a sidekick");
        AbstractPlayableSprite tails = sidekicks.getFirst();
        Tails extraSidekick = new Tails("extra_tails", (short) 0x4700, (short) 0x0200);
        extraSidekick.setCpuControlled(true);
        GameServices.sprites().addSprite(extraSidekick, "tails");

        camera.setX((short) 0x46BC);
        camera.setMinX((short) 0x46BC);
        camera.setMaxX((short) 0x46BC);
        sonic.setCentreXPreserveSubpixel((short) 0x4762);
        tails.setCentreXPreserveSubpixel((short) 0x46C8);
        extraSidekick.setCentreXPreserveSubpixel((short) 0x4700);
        sonic.setAnimationId(Sonic3kAnimationIds.WAIT);
        tails.setAnimationId(Sonic3kAnimationIds.WAIT);
        extraSidekick.setAnimationId(Sonic3kAnimationIds.WAIT);

        events.updatePrePhysics(1);

        assertEquals(0x44C0, camera.getX() & 0xFFFF,
                "AIZ2_DoShipLoop subtracts the ROM $200 repeat distance from Camera_X_pos");
        assertEquals(0x4560, sonic.getCentreX() & 0xFFFF,
                "sub_50318 clamps the wrapped player to Camera_X_pos+$A0 before movement");
        assertEquals(0x44D8, tails.getCentreX() & 0xFFFF,
                "AIZ2_DoShipLoop wraps native P2, then clamps it to Camera_X_pos+$18");
        assertEquals(0x4500, extraSidekick.getCentreX() & 0xFFFF,
                "AIZ2_DoShipLoop must preserve all-engine sidekick participation for extra sidekicks");
        assertEquals(Sonic3kAnimationIds.WALK.id(), sonic.getAnimationId(),
                "sub_50318 must clear an idle Player 1 animation to Walk before clamping");
        assertEquals(Sonic3kAnimationIds.WALK.id(), tails.getAnimationId(),
                "sub_50318 must clear an idle native Player 2 animation to Walk before clamping");
        assertEquals(Sonic3kAnimationIds.WALK.id(), extraSidekick.getAnimationId(),
                "the all-engine sidekick extension must apply the same native animation write");
        assertEquals(0x200, events.getLevelRepeatOffset(),
                "post-bombing wraps must expose the ROM Level_repeat_offset value");
    }

    @Test
    public void aiz2TreeSpawnerStopsBeforeRomEntriesHiddenByForestPriorityMask() throws Exception {
        Field field = AizBgTreeSpawnerInstance.class.getDeclaredField("TREE_SCRIPT");
        field.setAccessible(true);
        int[][] script = (int[][]) field.get(null);

        assertEquals(15, script.length,
                "Engine-visible AIZ2 tree script should omit the two ROM entries hidden past the forest mask");
        assertEquals(0x4CA, script[14][0], "last visible tree threshold should match AIZMakeTreeScript entry 15");
    }

    @Test
    public void aiz2TreeObjectsUseTheirRomDeletePredicatesInsteadOfGenericCull() {
        AizBgTreeSpawnerInstance spawner = new AizBgTreeSpawnerInstance();
        AizBgTreeInstance tree = new AizBgTreeInstance(0x44D0);

        assertTrue(spawner.isPersistent(),
                "Obj_AIZ2MakeTree has no MarkObjGone tail; it must survive until the script terminator");
        assertTrue(tree.isPersistent(),
                "Obj_AIZ2BGTree deletes only at Camera_X_pos >= $4880, not the generic object window");
    }

    @Test
    public void lowRiskAizEventPlayerLoopsUseExplicitAllEnginePlayerQuery() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java"));

        assertAizMethodUsesAllEnginePlayers(source, "updateBattleshipAutoScroll");
        assertAizMethodUsesAllEnginePlayers(source, "setTransitionControlLock");
    }

    @Test
    public void aiz2EndBossSpawnsFromEventsAtSonicWaterfallLock() {
        HeadlessTestFixture aiz2 = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 1)
                .startPosition((short) 0x4860, (short) 0x015A)
                .startPositionIsCentre()
                .build();
        Camera camera = aiz2.camera();
        camera.setX((short) 0x4880);
        camera.setY((short) 0x015A);

        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kAIZEvents events = manager.getAizEvents();
        assertNotNull(events, "AIZ event handler should be active for AIZ2");

        updateWithHardware(events, 1, 0);

        assertTrue(GameServices.level().getObjectManager().getActiveObjects().stream()
                        .anyMatch(AizEndBossInstance.class::isInstance),
                "AIZ2 end-boss handoff must allocate independently of its object-owned art queue");
    }

    @Test
    public void aiz2EndBossSplashChildrenRunThroughLiveEventAndSlotTimeline() throws Exception {
        HeadlessTestFixture aiz2 = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 1)
                .startPosition((short) 0x4860, (short) 0x015A)
                .startPositionIsCentre()
                .build();
        Camera camera = aiz2.camera();
        camera.setX((short) 0x4880);
        camera.setY((short) 0x015A);

        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kAIZEvents events = manager.getAizEvents();
        assertNotNull(events, "AIZ event handler should be active for AIZ2");
        ObjectManager objects = GameServices.level().getObjectManager();

        int frame = 0;
        runAiz2BossFrame(events, aiz2, frame++);
        List<AizEndBossInstance> liveBosses = objects.getActiveObjects().stream()
                .filter(AizEndBossInstance.class::isInstance)
                .map(AizEndBossInstance.class::cast)
                .toList();
        assertEquals(1, liveBosses.size(),
                "the real object pass must materialize the layout boss before the event fallback runs");
        AizEndBossInstance boss = liveBosses.getFirst();

        AizEndBossWaterfallChild emerge = null;
        List<Integer> occupiedBeforeEmerge = List.of();
        while (emerge == null && frame < 140) {
            List<Integer> occupiedBeforeFrame = liveManagedSlots(objects);
            runAiz2BossFrame(events, aiz2, frame++);
            emerge = liveWaterfall(objects, 0);
            if (emerge != null) {
                occupiedBeforeEmerge = occupiedBeforeFrame;
            }
        }
        assertNotNull(emerge,
                "the live event -> boss -> ChildObjDat route must create subtype 0");
        assertTrue(readPrivateBoolean(emerge, "initialized"),
                "the higher-slot child must consume init in the allocation pass");
        assertFalse(readPrivateBoolean(emerge, "drawEligible"),
                "AIZEndBossWaterfall_Init returns without Draw_Sprite");
        assertEquals(0x24, emerge.getMappingFrameForTest());
        assertNthFreeForwardSlot(occupiedBeforeEmerge, boss, emerge, 4);
        int emergeSlot = emerge.getSlotIndex();

        for (int animationDispatch = 1; animationDispatch <= 12; animationDispatch++) {
            runAiz2BossFrame(events, aiz2, frame++);
            assertTrue(objects.getActiveObjects().contains(emerge),
                    "subtype 0 must remain in its SST through raw-animation entry "
                            + animationDispatch);
        }
        assertTrue(readPrivateBoolean(emerge, "drawEligible"),
                "the dispatch after init must reach the Draw_Sprite-capable animation routine");
        runAiz2BossFrame(events, aiz2, frame++);
        assertTrue(objects.getActiveObjects().contains(emerge),
                "the $F4 callback must leave Go_Delete_Sprite in the same SST for one pass");
        assertEquals(emergeSlot, emerge.getSlotIndex());
        assertFalse(emerge.isDestroyed());
        assertFalse(readPrivateBoolean(emerge, "drawEligible"),
                "the Go_Delete marker row must not publish Draw_Sprite");
        runAiz2BossFrame(events, aiz2, frame++);
        assertFalse(objects.getActiveObjects().contains(emerge),
                "Delete_Current_Sprite must clear the subtype-0 SST on its next dispatch");

        AizEndBossWaterfallChild drop = null;
        List<Integer> occupiedBeforeDrop = List.of();
        while (drop == null && frame < 520) {
            List<Integer> occupiedBeforeFrame = liveManagedSlots(objects);
            runAiz2BossFrame(events, aiz2, frame++);
            drop = liveWaterfall(objects, 2);
            if (drop != null) {
                occupiedBeforeDrop = occupiedBeforeFrame;
            }
        }
        assertNotNull(drop, "the live retreat path must create subtype 2");
        assertTrue(readPrivateBoolean(drop, "initialized"));
        assertFalse(readPrivateBoolean(drop, "drawEligible"));
        assertEquals(0x24, drop.getMappingFrameForTest());
        assertNthFreeForwardSlot(occupiedBeforeDrop, boss, drop, 1);
        int dropSlot = drop.getSlotIndex();
        int dropStartY = drop.getY();

        for (int animationDispatch = 1; animationDispatch <= 13; animationDispatch++) {
            runAiz2BossFrame(events, aiz2, frame++);
        }
        assertTrue(drop.isDroppingForTest(),
                "the thirteenth emerge-script dispatch must install the falling routine");
        assertEquals(dropStartY, drop.getY(),
                "AIZEndBossWaterfall_StartDrop installs velocity without moving on its callback row");

        for (int dropDispatch = 1; dropDispatch <= 13; dropDispatch++) {
            runAiz2BossFrame(events, aiz2, frame++);
            assertEquals(dropStartY + dropDispatch * 8, drop.getY(),
                    "MoveSprite2 must apply y_vel=$800 before each drop-animation dispatch");
        }
        assertTrue(objects.getActiveObjects().contains(drop));
        assertEquals(dropSlot, drop.getSlotIndex());
        assertFalse(drop.isDestroyed(),
                "the subtype-2 $F4 callback must retain Go_Delete_Sprite for one pass");
        assertFalse(drop.isDroppingForTest());
        assertFalse(readPrivateBoolean(drop, "drawEligible"));
        runAiz2BossFrame(events, aiz2, frame);
        assertFalse(objects.getActiveObjects().contains(drop),
                "Delete_Current_Sprite must clear the subtype-2 SST on its next dispatch");
    }

    @Test
    public void aiz2EndBossLockKeepsFireLogBridgeLiveForArenaEntry() {
        HeadlessTestFixture aiz2 = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 1)
                .startPosition((short) 0x4860, (short) 0x015A)
                .startPositionIsCentre()
                .build();
        Camera camera = aiz2.camera();
        camera.setX((short) 0x4880);
        camera.setY((short) 0x015A);

        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kAIZEvents events = manager.getAizEvents();
        assertNotNull(events, "AIZ event handler should be active for AIZ2");

        updateWithHardware(events, 1, 0);
        GameServices.level().getObjectManager().update(camera.getX(), aiz2.sprite(), List.of(), 1, false);

        assertTrue(GameServices.level().getObjectManager().getActiveObjects().stream()
                        .anyMatch(object -> object instanceof AizCollapsingLogBridgeObjectInstance
                                && object.getX() == 0x48E0
                                && object.getY() == 0x0218),
                "ROM Obj_AIZCollapsingLogBridge loc_2AEE2 stays live at $48E0,$0218 and calls SolidObjectTop");
    }

    @Test
    public void aiz2FireLogBridgeSupportsSonicAtTraceArenaEntryPoint() {
        HeadlessTestFixture aiz2 = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 1)
                .startPosition((short) 0x4880, (short) 0x01FC)
                .startPositionIsCentre()
                .build();
        Camera camera = aiz2.camera();
        camera.setX((short) 0x4880);
        camera.setY((short) 0x015A);
        aiz2.sprite().setXSpeed((short) 0x0600);
        aiz2.sprite().setYSpeed((short) 0);
        aiz2.sprite().setGSpeed((short) 0x0600);
        aiz2.sprite().setAir(false);
        aiz2.sprite().setOnObject(false);

        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kAIZEvents events = manager.getAizEvents();
        assertNotNull(events, "AIZ event handler should be active for AIZ2");

        updateWithHardware(events, 1, 0);
        GameServices.level().getObjectManager().update(camera.getX(), aiz2.sprite(), List.of(), 1,
                false, true, true);

        assertTrue(GameServices.level().getObjectManager().getActiveObjects().stream()
                        .anyMatch(object -> object instanceof AizCollapsingLogBridgeObjectInstance
                                && object.getX() == 0x48E0
                                && object.getY() == 0x0218),
                "The fire log bridge must be active before checking its SolidObjectTop contact");
        assertTrue(aiz2.sprite().isOnObject(),
                "ROM loc_2AEE2 falls through to loc_2AF06/SolidObjectTop in the collapse-start frame");
    }

    @Test
    public void aiz2EndBossEventSpawnUsesLayoutHeightNotArenaBaseHeight() {
        HeadlessTestFixture aiz2 = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 1)
                .startPosition((short) 0x4860, (short) 0x015A)
                .startPositionIsCentre()
                .build();
        Camera camera = aiz2.camera();
        camera.setX((short) 0x4880);
        camera.setY((short) 0x015A);

        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kAIZEvents events = manager.getAizEvents();
        assertNotNull(events, "AIZ event handler should be active for AIZ2");

        updateWithHardware(events, 1, 0);

        AizEndBossInstance boss = GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(AizEndBossInstance.class::isInstance)
                .map(AizEndBossInstance.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(0x48A0, boss.getX(), "Sonic AIZ2 end boss should use the ROM layout X");
        assertEquals(0x01C0, boss.getY(), "Sonic AIZ2 end boss should use the ROM layout Y, not AIZBossSonicDat base Y");
    }

    @Test
    public void aiz2EndBossActivationKeepsSonicHighPriorityAtWaterfall() {
        HeadlessTestFixture aiz2 = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 1)
                .startPosition((short) 0x4860, (short) 0x015A)
                .startPositionIsCentre()
                .build();
        Camera camera = aiz2.camera();
        camera.setX((short) 0x4880);
        camera.setY((short) 0x015A);

        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kAIZEvents events = manager.getAizEvents();
        assertNotNull(events, "AIZ event handler should be active for AIZ2");
        List<AbstractPlayableSprite> sidekicks = GameServices.sprites().getSidekicks();
        assertFalse(sidekicks.isEmpty(), "The AIZ2 fixture should expose native Player 2 participation");
        AbstractPlayableSprite tails = sidekicks.getFirst();
        assertFalse(aiz2.sprite().isHighPriority(),
                "The fixture should prove the boss handoff publishes the late path-switch priority");
        assertFalse(tails.isHighPriority());

        updateWithHardware(events, 1, 0);
        assertTrue(aiz2.sprite().isHighPriority(),
                "The native event handoff must not wait for object-owned boss art");
        assertTrue(tails.isHighPriority(),
                "The same native boss handoff should apply to Player 2 through the participation policy");
        GameServices.level().getObjectManager().update(camera.getX(), aiz2.sprite(), List.of(), 1, false);
        GameServices.level().getZoneFeatureProvider().update(aiz2.sprite(), camera.getX(), 0);

        assertTrue(events.isBossFlag(), "Boss activation should set Boss_flag");
        assertTrue(aiz2.sprite().isHighPriority(),
                "Sonic should render high-priority in front of the AIZ2 waterfall during the boss handoff");
    }

    @Test
    public void bossFlagDefaultsFalse() {
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        assertFalse(events.isBossFlag(), "Boss flag should default to false");
    }

    @Test
    public void bossFlagCanBeSet() {
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        events.setBossFlag(true);
        assertTrue(events.isBossFlag(), "Boss flag should be true after setting");
    }

    @Test
    public void bossFlagResetsOnInit() {
        var events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        events.init(0);
        events.setBossFlag(true);
        events.init(0);
        assertFalse(events.isBossFlag(), "Boss flag should reset to false on init");
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

    private static long countActiveIntroObjects() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(AizPlaneIntroInstance.class::isInstance)
                .count();
    }

    private static int introEventsFg1(AizPlaneIntroInstance intro) throws Exception {
        Field field = AizPlaneIntroInstance.class.getDeclaredField("eventsFg1");
        field.setAccessible(true);
        return field.getInt(intro);
    }

    private static int[][] snapshotBlocks(Sonic3kLevel level) {
        int[][] snapshot = new int[level.getBlockCount()][];
        for (int i = 0; i < level.getBlockCount(); i++) {
            snapshot[i] = level.getBlock(i).saveState();
        }
        return snapshot;
    }

    private static int[][] snapshotChunks(Sonic3kLevel level) {
        int[][] snapshot = new int[level.getChunkCount()][];
        for (int i = 0; i < level.getChunkCount(); i++) {
            snapshot[i] = level.getChunk(i).saveState();
        }
        return snapshot;
    }

    private static int[][] snapshotChunkRange(
            Sonic3kLevel level, int startChunk, int chunkCount) {
        int[][] snapshot = new int[chunkCount][];
        for (int i = 0; i < chunkCount; i++) {
            snapshot[i] = level.getChunk(startChunk + i).saveState();
        }
        return snapshot;
    }

    private static byte[][] snapshotPatterns(
            Sonic3kLevel level, int startPattern, int patternCount) {
        byte[][] snapshot = new byte[patternCount][];
        for (int i = 0; i < patternCount; i++) {
            snapshot[i] = snapshotPattern(level.getPattern(startPattern + i));
        }
        return snapshot;
    }

    private static byte[] snapshotPattern(Pattern pattern) {
        byte[] pixels = new byte[Pattern.PATTERN_SIZE_IN_MEM];
        int index = 0;
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                pixels[index++] = pattern.getPixel(x, y);
            }
        }
        return pixels;
    }

    private static int[][] applyChunkPayload(
            int[][] baseline, byte[] payload) {
        assertEquals(0, payload.length % Chunk.CHUNK_SIZE_IN_ROM);
        int count = payload.length / Chunk.CHUNK_SIZE_IN_ROM;
        assertEquals(count, baseline.length);
        int[][] expected = new int[count][];
        for (int chunk = 0; chunk < count; chunk++) {
            expected[chunk] = baseline[chunk].clone();
            int payloadOffset = chunk * Chunk.CHUNK_SIZE_IN_ROM;
            for (int word = 0; word < Chunk.PATTERNS_PER_CHUNK; word++) {
                int byteOffset = payloadOffset + word * 2;
                expected[chunk][word] =
                        ((payload[byteOffset] & 0xFF) << 8)
                        | (payload[byteOffset + 1] & 0xFF);
            }
        }
        return expected;
    }

    private static void assertChunkPayloadEquals(
            Sonic3kLevel level,
            byte[] payload,
            int startChunk,
            String message) {
        assertEquals(0, payload.length % Chunk.CHUNK_SIZE_IN_ROM);
        int count = payload.length / Chunk.CHUNK_SIZE_IN_ROM;
        for (int chunk = 0; chunk < count; chunk++) {
            int[] actual = level.getChunk(startChunk + chunk).saveState();
            int payloadOffset = chunk * Chunk.CHUNK_SIZE_IN_ROM;
            for (int word = 0; word < Chunk.PATTERNS_PER_CHUNK; word++) {
                int byteOffset = payloadOffset + word * 2;
                int expected = ((payload[byteOffset] & 0xFF) << 8)
                        | (payload[byteOffset + 1] & 0xFF);
                assertEquals(
                        expected, actual[word],
                        message + " at chunk " + chunk
                                + ", word " + word);
            }
        }
    }

    private static byte[][] decodePatternPayload(byte[] payload) {
        assertEquals(0, payload.length % Pattern.PATTERN_SIZE_IN_ROM);
        int count = payload.length / Pattern.PATTERN_SIZE_IN_ROM;
        byte[][] expected = new byte[count][];
        byte[] tileBytes = new byte[Pattern.PATTERN_SIZE_IN_ROM];
        for (int i = 0; i < count; i++) {
            System.arraycopy(
                    payload,
                    i * Pattern.PATTERN_SIZE_IN_ROM,
                    tileBytes,
                    0,
                    Pattern.PATTERN_SIZE_IN_ROM);
            Pattern pattern = new Pattern();
            pattern.fromSegaFormat(tileBytes);
            expected[i] = snapshotPattern(pattern);
        }
        return expected;
    }

    private static void assertPatternRangeEquals(
            byte[][] expected, Sonic3kLevel level, int startPattern, String message) {
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], snapshotPattern(level.getPattern(startPattern + i)),
                    message + " at tile $" + Integer.toHexString(startPattern + i));
        }
    }

    private static void assertPatternPayloadEquals(
            Sonic3kLevel level, byte[] payload, int destinationPattern) {
        assertNotNull(payload);
        assertEquals(0, payload.length % Pattern.PATTERN_SIZE_IN_ROM);
        Pattern expected = new Pattern();
        byte[] tileBytes = new byte[Pattern.PATTERN_SIZE_IN_ROM];
        for (int i = 0; i < payload.length / Pattern.PATTERN_SIZE_IN_ROM; i++) {
            System.arraycopy(payload, i * Pattern.PATTERN_SIZE_IN_ROM,
                    tileBytes, 0, Pattern.PATTERN_SIZE_IN_ROM);
            expected.fromSegaFormat(tileBytes);
            assertArrayEquals(
                    snapshotPattern(expected),
                    snapshotPattern(level.getPattern(destinationPattern + i)),
                    "Published pattern must equal claimed timing payload at tile $"
                            + Integer.toHexString(destinationPattern + i));
        }
    }

    private static void assert2dArrayEquals(int[][] expected, int[][] actual, String message) {
        assertEquals(expected.length, actual.length, message + " length");
        for (int i = 0; i < expected.length; i++) {
            if (!Arrays.equals(expected[i], actual[i])) {
                throw new AssertionError(message + " at index " + i);
            }
        }
    }

    private static void runAiz2BossFrame(
            Sonic3kAIZEvents events, HeadlessTestFixture aiz2, int frame) {
        // This focused test enters directly at the Sonic arena. Dynamic_Resize
        // has not traversed all preceding AIZ2 thresholds, so retain the boss
        // lock that the production route has already established by this point.
        aiz2.camera().setX((short) 0x4880);
        aiz2.camera().setY((short) 0x015A);
        serviceFireTransitionBoundary(HardwareServiceBoundary.VINT_SERVICE);
        serviceFireTransitionBoundary(HardwareServiceBoundary.PRE_MAIN_LOOP);
        GameServices.level().getObjectManager().update(
                aiz2.camera().getX(),
                aiz2.sprite(),
                GameServices.sprites().getSidekicks(),
                frame,
                false);
        // LevelFrameStep runs DynamicLevelEvents after the S3K object pass.
        // Keeping that order lets the native layout entry win; the event-side
        // fallback sees the live boss and must not allocate a duplicate.
        events.update(1, frame);
        serviceFireTransitionBoundary(HardwareServiceBoundary.POST_OBJECTS);
    }

    private static AizEndBossWaterfallChild liveWaterfall(ObjectManager objects, int subtype) {
        return objects.getActiveObjects().stream()
                .filter(AizEndBossWaterfallChild.class::isInstance)
                .map(AizEndBossWaterfallChild.class::cast)
                .filter(child -> !child.isDestroyed() && child.getSpawn().subtype() == subtype)
                .findFirst()
                .orElse(null);
    }

    private static List<Integer> liveManagedSlots(ObjectManager objects) {
        return objects.getActiveObjects().stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(object -> !object.isDestroyed() && object.getSlotIndex() >= 0)
                .map(AbstractObjectInstance::getSlotIndex)
                .toList();
    }

    private static void assertNthFreeForwardSlot(
            List<Integer> occupiedBefore,
            AizEndBossInstance boss,
            AizEndBossWaterfallChild child,
            int allocationOrdinal) {
        int candidate = boss.getSlotIndex() + 1;
        for (int allocation = 0; allocation < allocationOrdinal; allocation++) {
            while (occupiedBefore.contains(candidate)) {
                candidate++;
            }
            if (allocation + 1 < allocationOrdinal) {
                occupiedBefore = new java.util.ArrayList<>(occupiedBefore);
                occupiedBefore.add(candidate++);
            }
        }
        assertEquals(candidate, child.getSlotIndex(),
                "CreateChild1_Normal must use the requested first-free-forward allocation");
    }

    private static boolean readPrivateBoolean(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void setPrivateBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setPrivateInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void assertAizMethodUsesAllEnginePlayers(String source, String methodName) {
        String body = methodBody(source, methodName);
        assertTrue(body.contains("ObjectPlayerQuery"),
                methodName + " should route player participation through ObjectPlayerQuery");
        assertTrue(body.contains("ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS"),
                methodName + " should declare ALL_ENGINE_PLAYERS participation explicitly");
        assertFalse(body.contains("getSidekicks()"),
                methodName + " should not directly traverse raw SpriteManager sidekicks");
    }

    private static String methodBody(String source, String methodName) {
        int methodStart = source.indexOf("private void " + methodName + "(");
        if (methodStart < 0) {
            throw new AssertionError("Missing method " + methodName);
        }
        int bodyStart = source.indexOf('{', methodStart);
        if (bodyStart < 0) {
            throw new AssertionError("Missing method body for " + methodName);
        }
        int depth = 0;
        for (int i = bodyStart; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(bodyStart, i + 1);
                }
            }
        }
        throw new AssertionError("Unterminated method body for " + methodName);
    }
}
