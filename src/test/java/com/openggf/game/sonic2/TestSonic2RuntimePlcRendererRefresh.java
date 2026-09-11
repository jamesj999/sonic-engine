package com.openggf.game.sonic2;

import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.snapshot.PlcProgressSnapshot;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.events.Sonic2ARZEvents;
import com.openggf.game.sonic2.events.Sonic2ZoneEvents;
import com.openggf.game.sonic2.events.Sonic2WFZEvents;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@RequiresRom(SonicGame.SONIC_2)
@ExtendWith(SingletonResetExtension.class)
class TestSonic2RuntimePlcRendererRefresh {
    private Sonic2ObjectArtProvider provider;
    private LevelManager levelManager;
    private TestableZoneEvents events;
    private PatternSpriteRenderer preExistingRenderer;
    private int preExistingPatternBase;

    @BeforeEach
    void setUp() throws Exception {
        GraphicsManager.getInstance().initHeadless();
        GameServices.module().createGame(TestEnvironment.currentRom());
        GameplayModeContext gameplay = TestEnvironment.activeGameplayMode();
        provider = (Sonic2ObjectArtProvider) GameServices.module().getObjectArtProvider();
        provider.loadArtForZone(Sonic2ZoneConstants.ROM_ZONE_WFZ);

        levelManager = spy(gameplay.getLevelManager());
        setObjectRenderManager(levelManager, new ObjectRenderManager(provider));
        gameplay.attachLevelManagers(
                gameplay.getWaterSystem(),
                gameplay.getParallaxManager(),
                gameplay.getTerrainCollisionManager(),
                gameplay.getCollisionSystem(),
                gameplay.getSpriteManager(),
                levelManager);
        setCurrentLevel(levelManager, gameplay, mock(Level.class));
        levelManager.refreshObjectArtPatterns();
        preExistingRenderer = provider.getRenderer(ObjectArtKeys.MONITOR);
        preExistingPatternBase = preExistingRenderer.getPatternBase();
        clearInvocations(levelManager);
        events = new TestableZoneEvents();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void runtimePlcPublishesNewRendererInSameFrameAndRefreshesOnce() {
        assertNull(provider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER));

        events.requestForTest(Sonic2Constants.PLC_TORNADO);

        PatternSpriteRenderer renderer =
                provider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER);
        assertNotNull(renderer);
        assertTrue(renderer.isReady(), "the event-triggered renderer must be cache-ready immediately");
        assertSame(preExistingRenderer, provider.getRenderer(ObjectArtKeys.MONITOR));
        assertEquals(preExistingPatternBase, preExistingRenderer.getPatternBase(),
                "appending runtime PLC art must not relocate already-cached renderers");
        assertTrue(renderer.getPatternBase() > preExistingPatternBase,
                "the runtime PLC renderer should append after the initial WFZ allocation");
        verify(levelManager, times(1)).refreshObjectArtPatterns();
    }

    @Test
    void runtimePlcAlsoAppendsTheRomLogicalQueueWhenEagerArtWasAlreadyAvailable() {
        Sonic2PlcService plcService = GameServices.module().getGameService(Sonic2PlcService.class);
        assertFalse(plcService.isBusy(), "the fixture begins with no logical PLC work");

        events.requestForTest(Sonic2Constants.PLC_TORNADO);

        assertTrue(plcService.isBusy(),
                "an event PLC request must append ROM logical work even when eager renderer art is cached");
    }

    @Test
    void repeatedRuntimePlcKeepsRendererIdentityAndPatternBaseWithoutAnotherRefresh() {
        events.requestForTest(Sonic2Constants.PLC_TORNADO);
        PatternSpriteRenderer first = provider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER);
        int firstBase = first.getPatternBase();

        events.requestForTest(Sonic2Constants.PLC_TORNADO);

        PatternSpriteRenderer repeated = provider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER);
        assertSame(first, repeated);
        assertTrue(repeated.isReady());
        assertTrue(firstBase >= 0);
        assertEquals(firstBase, repeated.getPatternBase());
        verify(levelManager, times(1)).refreshObjectArtPatterns();
    }

    @Test
    void rewindReplayThroughZoneEventReusesRendererWithoutDuplicateRefreshOrAllocation() {
        PlcProgressSnapshot beforeRequest = provider.capture();

        events.requestForTest(Sonic2Constants.PLC_TORNADO);
        PatternSpriteRenderer first = provider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER);
        int firstBase = first.getPatternBase();
        int firstPatternCount = provider.getRegularPatternCount();
        int firstEpoch = provider.capture().loadEpoch();
        verify(levelManager, times(1)).refreshObjectArtPatterns();

        provider.restore(beforeRequest);
        assertEquals(beforeRequest.loadEpoch(), provider.capture().loadEpoch());

        events.requestForTest(Sonic2Constants.PLC_TORNADO);

        assertSame(first, provider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER));
        assertEquals(firstBase, first.getPatternBase());
        assertEquals(firstPatternCount, provider.getRegularPatternCount(),
                "replay must retain immutable sheets instead of appending duplicates");
        assertEquals(firstEpoch, provider.capture().loadEpoch(),
                "replay should advance once from the restored PLC epoch");
        verify(levelManager, times(1)).refreshObjectArtPatterns();
    }

    @Test
    void realWfzOneShotRestoresAndReplaysThroughGameplayRegistry() {
        Sonic2LevelEventManager levelEvents =
                (Sonic2LevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic2WFZEvents wfz = levelEvents.getWfzEvents();
        levelEvents.initLevel(Sonic2LevelEventManager.ZONE_WFZ, 0);
        wfz.setWfzSubRoutine(2);
        GameServices.camera().setY((short) 0x500);

        RewindRegistry registry = SessionManager.getCurrentGameplayMode().getRewindRegistry();
        SessionManager.getCurrentGameplayMode().registerPlcArtAdapter(provider);
        registry.deregister("level-event");
        registry.register(levelEvents);
        CompositeSnapshot beforeTornado = registry.capture();
        assertNotNull(beforeTornado.get("level-event"));
        assertNotNull(beforeTornado.get("s2-plc-art"));
        assertEquals(0, levelEvents.getEventRoutine());

        int epochBefore = provider.capture().loadEpoch();
        levelEvents.update();

        PatternSpriteRenderer first = provider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER);
        assertNotNull(first);
        assertTrue(first.isReady());
        int firstBase = first.getPatternBase();
        int firstPatternCount = provider.getRegularPatternCount();
        int epochAfter = provider.capture().loadEpoch();
        assertEquals(epochBefore + 1, epochAfter);
        assertEquals(2, levelEvents.getEventRoutine());
        assertEquals(4, wfz.getWfzSubRoutine());
        assertEquals(Sonic2Constants.PLC_TORNADO, wfz.getLastRequestedPlcIdForTest());
        verify(levelManager, times(1)).refreshObjectArtPatterns();

        registry.restore(beforeTornado);
        assertEquals(0, levelEvents.getEventRoutine());
        assertEquals(2, wfz.getWfzSubRoutine(),
                "whole-registry restore must re-arm the WFZ Tornado one-shot");
        assertEquals(epochBefore, provider.capture().loadEpoch());

        levelEvents.update();

        assertEquals(2, levelEvents.getEventRoutine());
        assertEquals(4, wfz.getWfzSubRoutine());
        assertEquals(epochAfter, provider.capture().loadEpoch());
        assertSame(first, provider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER));
        assertTrue(first.isReady());
        assertEquals(firstBase, first.getPatternBase());
        assertEquals(firstPatternCount, provider.getRegularPatternCount());
        verify(levelManager, times(1)).refreshObjectArtPatterns();
    }

    @Test
    void preflightFailureLeavesBothRendererAndLogicalQueueUnpublished() throws Exception {
        OverflowingSonic2ObjectArtProvider overflowingProvider =
                new OverflowingSonic2ObjectArtProvider();
        Sonic2RuntimeFixture fixture = installSonic2RuntimeWithProvider(overflowingProvider);
        Sonic2WFZEvents wfz = fixture.levelEvents().getWfzEvents();
        fixture.levelEvents().initLevel(Sonic2LevelEventManager.ZONE_WFZ, 0);
        wfz.setWfzSubRoutine(2);
        GameServices.camera().setY((short) 0x500);
        Sonic2PlcService plcService = GameServices.module().getGameService(Sonic2PlcService.class);
        int epochBefore = overflowingProvider.capture().loadEpoch();
        int patternCountBefore = overflowingProvider.getRegularPatternCount();
        int rendererCountBefore = overflowingProvider.getRendererKeys().size();
        overflowingProvider.enableOverflow();
        RecordingLogHandler logHandler = new RecordingLogHandler();
        Logger logger = Logger.getLogger(Sonic2ZoneEvents.class.getName());
        java.util.logging.Level previousLevel = logger.getLevel();
        logger.setLevel(java.util.logging.Level.FINE);
        logger.addHandler(logHandler);
        try {
            assertDoesNotThrow(fixture.levelEvents()::update);
        } finally {
            logger.removeHandler(logHandler);
            logger.setLevel(previousLevel);
        }

        PatternSpriteRenderer failedRenderer =
                overflowingProvider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER);
        int epochAfterFailure = overflowingProvider.capture().loadEpoch();
        assertNull(failedRenderer, "failed preflight must not publish renderer art");
        assertFalse(plcService.isBusy(), "failed renderer preflight must not publish logical PLC work");
        assertEquals(epochBefore, epochAfterFailure);
        int rendererCountAfterFailure = overflowingProvider.getRendererKeys().size();
        assertEquals(rendererCountBefore, rendererCountAfterFailure);
        assertEquals(2, wfz.getWfzSubRoutine(),
                "a rejected one-shot publication must leave its semantic gate armed");
        assertTrue(logHandler.messages().stream().anyMatch(message ->
                        message.contains("S2 PLC request " + Sonic2Constants.PLC_TORNADO)
                                && message.contains("Object patterns exceed reserved atlas range")),
                "the event path should log the non-fatal preflight failure");
        verify(fixture.levelManager(), never()).refreshObjectArtPatterns();

        overflowingProvider.disableOverflow();
        assertDoesNotThrow(fixture.levelEvents()::update);

        assertEquals(4, wfz.getWfzSubRoutine(), "the successful retry consumes the one-shot once");
        assertTrue(plcService.isBusy());
        assertNotNull(overflowingProvider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER));
        verify(fixture.levelManager(), times(1)).refreshObjectArtPatterns();

        assertDoesNotThrow(fixture.levelEvents()::update);
        assertEquals(4, wfz.getWfzSubRoutine(), "a successful one-shot does not submit twice");
        verify(fixture.levelManager(), times(1)).refreshObjectArtPatterns();
    }

    @Test
    void failedEventRetryRetainsPendingCueWithoutSuppressingCurrentDleRoutine() throws Exception {
        OverflowingSonic2ObjectArtProvider overflowingProvider =
                new OverflowingSonic2ObjectArtProvider();
        Sonic2RuntimeFixture fixture = installSonic2RuntimeWithProvider(overflowingProvider);
        Sonic2ARZEvents arz = new Sonic2ARZEvents();
        arz.init(1);
        arz.setEventRoutine(4);
        arz.setPendingPlcIdForRewind(Sonic2Constants.PLC_ARZ_BOSS);
        overflowingProvider.enableOverflow();

        arz.update(1, 0);

        assertEquals(1, arz.getBossSpawnDelay(),
                "retry bookkeeping must not suppress ARZ routine 4's delay tick");
        assertEquals(Sonic2Constants.PLC_ARZ_BOSS, arz.getPendingPlcIdForRewind());
        assertFalse(GameServices.module().getGameService(Sonic2PlcService.class).isBusy());
        verify(fixture.levelManager(), never()).refreshObjectArtPatterns();
    }

    @Test
    void successfulEventRetryClearsPendingCueAndRunsCurrentDleRoutineExactlyOnce() throws Exception {
        OverflowingSonic2ObjectArtProvider retryProvider =
                new OverflowingSonic2ObjectArtProvider();
        Sonic2RuntimeFixture fixture = installSonic2RuntimeWithProvider(retryProvider);
        Sonic2ARZEvents arz = new Sonic2ARZEvents();
        arz.init(1);
        arz.setEventRoutine(4);
        arz.setPendingPlcIdForRewind(Sonic2Constants.PLC_ARZ_BOSS);
        Sonic2PlcService plcService =
                GameServices.module().getGameService(Sonic2PlcService.class);

        arz.update(1, 0);

        assertEquals(1, arz.getBossSpawnDelay());
        assertEquals(-1, arz.getPendingPlcIdForRewind());
        assertTrue(plcService.isBusy());
        var submittedQueue = plcService.capture();
        verify(fixture.levelManager(), times(1)).refreshObjectArtPatterns();

        arz.update(1, 1);

        assertEquals(2, arz.getBossSpawnDelay(),
                "the current DLE routine must execute once on each frame");
        assertEquals(submittedQueue, plcService.capture(),
                "the successful retry must not replay the original one-shot producer");
        verify(fixture.levelManager(), times(1)).refreshObjectArtPatterns();
    }

    @Test
    void requestWithoutGameplayRuntimeReturnsWithoutLoading() {
        SessionManager.clear();

        assertDoesNotThrow(() -> events.requestForTest(Sonic2Constants.PLC_TORNADO));
        assertNull(provider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER));
    }

    @Test
    void requestWithoutLoadedLevelReturnsWithoutLoading() throws Exception {
        setCurrentLevel(levelManager, SessionManager.getCurrentGameplayMode(), null);

        assertDoesNotThrow(() -> events.requestForTest(Sonic2Constants.PLC_TORNADO));
        assertNull(provider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER));
    }

    @Test
    void ioFailureFromRuntimePlcRemainsNonFatal() throws Exception {
        Sonic2ObjectArtProvider failingProvider = new Sonic2ObjectArtProvider() {
            @Override
            public PreparedPlc preparePlcs(int... plcIds) throws IOException {
                throw new IOException("synthetic PLC read failure");
            }
        };
        RuntimeFixture failingRuntime = installRuntimeWithProvider(failingProvider);

        assertDoesNotThrow(() -> new TestableZoneEvents().requestForTest(Sonic2Constants.PLC_TORNADO));
        assertNull(failingProvider.getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER));
        verify(failingRuntime.levelManager(), never()).refreshObjectArtPatterns();
    }

    @Test
    void nonSonic2ObjectArtProviderIsAcceptedWithoutRefresh() throws Exception {
        ObjectArtProvider otherProvider = mock(ObjectArtProvider.class);
        RuntimeFixture otherRuntime = installRuntimeWithProvider(otherProvider);

        assertDoesNotThrow(() -> new TestableZoneEvents().requestForTest(Sonic2Constants.PLC_TORNADO));
        verify(otherRuntime.levelManager(), never()).refreshObjectArtPatterns();
    }

    private static RuntimeFixture installRuntimeWithProvider(ObjectArtProvider artProvider)
            throws Exception {
        GameModule module = mock(GameModule.class, delegatesTo(GameServices.module()));
        when(module.getObjectArtProvider()).thenReturn(artProvider);
        GameplayModeContext gameplay = SessionManager.openGameplaySession(module);
        TestEnvironment.activeGameplayMode();
        LevelManager manager = spy(gameplay.getLevelManager());
        gameplay.attachLevelManagers(
                gameplay.getWaterSystem(),
                gameplay.getParallaxManager(),
                gameplay.getTerrainCollisionManager(),
                gameplay.getCollisionSystem(),
                gameplay.getSpriteManager(),
                manager);
        setCurrentLevel(manager, gameplay, mock(Level.class));
        clearInvocations(manager);
        return new RuntimeFixture(manager);
    }

    private static Sonic2RuntimeFixture installSonic2RuntimeWithProvider(
            Sonic2ObjectArtProvider artProvider) throws Exception {
        GameModule module = mock(GameModule.class, delegatesTo(GameServices.module()));
        when(module.getObjectArtProvider()).thenReturn(artProvider);
        GameplayModeContext gameplay = SessionManager.openGameplaySession(module);
        TestEnvironment.activeGameplayMode();
        artProvider.loadArtForZone(Sonic2ZoneConstants.ROM_ZONE_WFZ);
        LevelManager manager = spy(gameplay.getLevelManager());
        setObjectRenderManager(manager, new ObjectRenderManager(artProvider));
        gameplay.attachLevelManagers(
                gameplay.getWaterSystem(),
                gameplay.getParallaxManager(),
                gameplay.getTerrainCollisionManager(),
                gameplay.getCollisionSystem(),
                gameplay.getSpriteManager(),
                manager);
        setCurrentLevel(manager, gameplay, mock(Level.class));
        manager.refreshObjectArtPatterns();
        clearInvocations(manager);
        gameplay.registerPlcArtAdapter(artProvider);
        Sonic2LevelEventManager levelEvents =
                (Sonic2LevelEventManager) module.getLevelEventProvider();
        RewindRegistry registry = gameplay.getRewindRegistry();
        registry.deregister("level-event");
        registry.register(levelEvents);
        return new Sonic2RuntimeFixture(manager, levelEvents);
    }

    private static void setObjectRenderManager(LevelManager manager, ObjectRenderManager renderManager)
            throws ReflectiveOperationException {
        Field field = LevelManager.class.getDeclaredField("objectRenderManager");
        field.setAccessible(true);
        field.set(manager, renderManager);
    }

    private static void setCurrentLevel(LevelManager manager, GameplayModeContext gameplay, Level level)
            throws ReflectiveOperationException {
        Field field = LevelManager.class.getDeclaredField("level");
        field.setAccessible(true);
        field.set(manager, level);
        gameplay.getWorldSession().setCurrentLevel(level);
    }

    private static final class TestableZoneEvents extends Sonic2ZoneEvents {
        @Override
        public void update(int act, int frameCounter) {
        }

        private void requestForTest(int plcId) {
            requestSonic2Plc(plcId);
        }
    }

    private record RuntimeFixture(LevelManager levelManager) {
    }

    private record Sonic2RuntimeFixture(
            LevelManager levelManager,
            Sonic2LevelEventManager levelEvents) {
    }

    private static final class OverflowingSonic2ObjectArtProvider extends Sonic2ObjectArtProvider {
        private boolean overflow;

        private void enableOverflow() {
            overflow = true;
        }

        private void disableOverflow() {
            overflow = false;
        }

        private int actualRegularPatternCount() {
            return super.getRegularPatternCount();
        }

        @Override
        public int getRegularPatternCount() {
            return overflow ? com.openggf.graphics.PatternAtlasRange.OBJECTS.size() + 1
                    : super.getRegularPatternCount();
        }
    }

    private static final class RecordingLogHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private List<String> messages() {
            return List.copyOf(messages);
        }
    }
}
