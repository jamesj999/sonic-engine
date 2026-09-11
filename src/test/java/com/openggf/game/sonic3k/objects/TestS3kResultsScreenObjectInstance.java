package com.openggf.game.sonic3k.objects;

import com.openggf.game.LevelEventProvider;
import com.openggf.game.GameModule;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelState;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.ZoneRegistry;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.events.S3kTransitionEventBridge;
import com.openggf.camera.Camera;
import com.openggf.level.CarriedTitlePublicationTiming;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestS3kResultsScreenObjectInstance {

    @Test
    void retainedResultsPublishesRestorePlayerControlWaitAnimation() {
        assertTrue(S3kResultsScreenObjectInstance
                .shouldPublishWaitAnimationOnControlRestore(0, true));
        assertTrue(S3kResultsScreenObjectInstance
                .shouldPublishWaitAnimationOnControlRestore(1, false));
        assertFalse(S3kResultsScreenObjectInstance
                .shouldPublishWaitAnimationOnControlRestore(0, false));
    }

    @Test
    void shortResultsChildTailRetainsTwoMutatedTitleCreateDispatches() {
        assertEquals(38, S3kResultsScreenObjectInstance.mutatedTitleCardResetDispatches(false));
        assertEquals(40, S3kResultsScreenObjectInstance.mutatedTitleCardResetDispatches(true));
        assertEquals(39, S3kResultsScreenObjectInstance.mutatedTitleCardResetDispatches(
                true, -1, true));
    }

    @Test
    void timingCompensationDoesNotDelayReadinessGatedCreate() throws Exception {
        // ROM Obj_LevelResultsCreate gates on Kos_modules_left alone
        // (docs/skdisasm/sonic3k.asm:62596-62598); no dispatch countdown
        // delays child creation once the queued results art is ready.
        S3kResultsScreenObjectInstance none = resultsWithTimingAdjustment("NONE", true);
        S3kResultsScreenObjectInstance compensation = resultsWithTimingAdjustment(
                "UNSUPPORTED_GROUNDED_COMPENSATION", true);

        invokeCreateGate(none);
        invokeCreateGate(compensation);

        assertEquals(-1, privateInt(none, "createGateFrames"),
                "readiness-gated create keeps no dispatch countdown");
        assertEquals(-1, privateInt(compensation, "createGateFrames"));
        assertTrue(privateBoolean(none, "usesShortResultsChildRetireTail"));
        assertTrue(privateBoolean(compensation, "usesShortResultsChildRetireTail"),
                "short-tail retirement remains independent from create-gate timing compensation");
    }

    @Test
    void productionDispatchSurfacesMissingHardwareTimingService() {
        TestObjectServices services = new TestObjectServices();
        S3kResultsScreenObjectInstance results = ObjectConstructionContext.construct(
                services,
                () -> new S3kResultsScreenObjectInstance(
                        PlayerCharacter.SONIC_AND_TAILS, 0));
        results.setServices(services);
        TestablePlayableSprite player =
                new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        assertThrows(IllegalStateException.class, () -> results.update(0, player));
    }

    @Test
    void publicationRestoresControlThroughLaterOwnerBeforeNextDispatchTitleInit()
            throws Exception {
        ActTransitionRecordingServices services =
                new ActTransitionRecordingServices(0x00, Sonic3kMusic.AIZ2.id);
        S3kResultsScreenObjectInstance results = transitionShell(
                services, PlayerCharacter.SONIC_AND_TAILS, 0);
        results.setServices(services);
        prepareFinalExitDispatch(results);

        S3kBossDefeatSignpostFlow controlOwner =
                new S3kBossDefeatSignpostFlow(
                        0x1180, 0, S3kBossDefeatSignpostFlow.CleanupAction.NONE);
        controlOwner.setServices(services);
        setPrivate(controlOwner, "initialized", true);
        setPrivateEnum(controlOwner, "phase", "AWAIT_RESULTS");

        TestablePlayableSprite player =
                new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setAirForTest(true);
        player.setAnimationId(Sonic3kAnimationIds.VICTORY);
        player.setAnimationFrameIndex(3);
        player.setAnimationTick(9);

        results.update(0, player);

        assertTrue(services.titleCard.calls.isEmpty(),
                "Obj_LevelResultsWait2 publishes _unkFAA8 before its mutated "
                        + "Obj_TitleCard init dispatch");
        assertFalse(results.isDestroyed(),
                "the published results SST must remain live for its next dispatch");
        assertTrue(player.isObjectControlled(),
                "the result publication must not consume the independent "
                        + "EndSignControl restore");

        controlOwner.update(0, player);

        assertFalse(player.isObjectControlled(),
                "the later EndSignControl slot consumes the publication in the same object pass");
        assertFalse(player.getAir());
        assertEquals(Sonic3kAnimationIds.WAIT.id(), player.getAnimationId());

        results.update(1, player);

        assertEquals(List.of("0:1"), services.titleCard.calls,
                "the mutated results owner initializes title art exactly one dispatch later");
        assertTrue(results.isDestroyed(),
                "title initialization hands ownership to the manager and retires the SST");
    }

    @Test
    void aizBossFlowDoesNotRepeatDisplacedOwnerEntriesInResultsWait()
            throws Exception {
        ActTransitionRecordingServices services =
                new ActTransitionRecordingServices(0x00, Sonic3kMusic.AIZ2.id);
        int aizWaitAdjustment = privateStaticInt(
                AizMinibossInstance.class, "RESULTS_WAIT_DURATION_ADJUSTMENT");
        S3kResultsScreenObjectInstance results =
                ObjectConstructionContext.withRewindActiveRestore(
                        () -> ObjectConstructionContext.construct(
                                services,
                                () -> new S3kResultsScreenObjectInstance(
                                        PlayerCharacter.SONIC_AND_TAILS, 0,
                                        aizWaitAdjustment, 13, 0, true)));
        results.setServices(services);
        prepareWaitDispatch(results);

        TestablePlayableSprite player =
                new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        for (int dispatch = 0; dispatch < 90; dispatch++) {
            results.update(dispatch, player);
        }

        assertEquals(4, superclassInt(results, "state"),
                "Obj_LevelResultsWait2 owns exactly the ROM's #90 countdown; "
                        + "AIZ's displaced EndSignControl entries are already "
                        + "carried by the boss/signpost bridge and must not be "
                        + "added to the results owner "
                        + "(docs/skdisasm/sonic3k.asm:62676-62690)");
    }

    @Test
    void resultsChildrenRetireFromPriorRenderFlagBeforeParentPublishes()
            throws Exception {
        ActTransitionRecordingServices services =
                new ActTransitionRecordingServices(0x00, Sonic3kMusic.AIZ2.id);
        S3kResultsScreenObjectInstance results = transitionShell(
                services, PlayerCharacter.SONIC_AND_TAILS, 0);
        results.setServices(services);
        invokeCreateElements(results);
        placeElementsAtTargets(results);
        setPrivate(results, "resultsChildrenCreated", true);
        setPrivate(results, "exitRetireDispatchesInitialized", true);
        setPrivate(results, "carriedResultsRenderRetireDispatches", 0);
        Field state = com.openggf.level.objects.AbstractResultsScreen.class
                .getDeclaredField("state");
        state.setAccessible(true);
        state.setInt(results, 4);

        TestablePlayableSprite player =
                new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        for (int dispatch = 0; dispatch < 18; dispatch++) {
            results.update(dispatch, player);
        }

        assertEquals(0, privateInt(results, "childrenRemaining"),
                "LevelResults_MoveElement deletes a child when the prior "
                        + "Render_Sprites pass cleared render_flags.on_screen; "
                        + "the final queue-9 child retires on queue dispatch 18 "
                        + "(docs/skdisasm/sonic3k.asm:62799-62824,36336-36402)");
        verify(services.gameState, never()).setEndOfLevelActive(false);

        results.update(18, player);

        verify(services.gameState).setEndOfLevelActive(false);
        assertTrue(services.titleCard.calls.isEmpty(),
                "the parent publishes after observing child count zero but "
                        + "does not initialize the mutated title owner yet");

        results.update(19, player);

        assertEquals(List.of("0:1"), services.titleCard.calls);
    }

    @Test
    void cnzActOneExitStartsActTwoTitleCardAndMusic() throws Exception {
        ActTransitionRecordingServices services = new ActTransitionRecordingServices(0x03, Sonic3kMusic.CNZ2.id);
        ObjectArtProvider objectArtProvider = mock(ObjectArtProvider.class);
        services.objectArtProvider = objectArtProvider;
        S3kResultsScreenObjectInstance results = transitionShell(
                services, PlayerCharacter.SONIC_AND_TAILS, 0);
        results.setServices(services);

        Method onExitReady = S3kResultsScreenObjectInstance.class.getDeclaredMethod("onExitReady");
        onExitReady.setAccessible(true);
        onExitReady.invoke(results);
        onExitReady.invoke(results);

        assertEquals(List.of(Sonic3kMusic.CNZ2.id), services.playedMusic,
                "Obj_LevelResults exit for CNZ Act 1 must start the next act's level music "
                        + "after the act-clear results leave (docs/skdisasm/sonic3k.asm:62708-62720)");
        assertEquals(List.of("3:1"), services.titleCard.calls,
                "Obj_LevelResults exit for CNZ Act 1 mutates into the in-level Act 2 title card "
                        + "(docs/skdisasm/sonic3k.asm:62708-62720)");
        assertEquals(1, services.apparentAct,
                "Act 1 results exit must update Apparent_act to Act 2 before title-card handoff "
                        + "(docs/skdisasm/sonic3k.asm:62708-62720)");
        verify(objectArtProvider).prepareRuntimeArtForInLevelTitleCard();
    }

    @Test
    void carriedCnzActOneResultsKeepsReloadedActStateForNativeTitleCardReset() throws Exception {
        ActTransitionRecordingServices services = new ActTransitionRecordingServices(0x03, Sonic3kMusic.CNZ2.id);
        S3kResultsScreenObjectInstance results = transitionShell(
                services, PlayerCharacter.SONIC_AND_TAILS, 0);
        results.setServices(services);
        results.onCarriedAcrossSeamlessTransition(
                -0x3000, 0x0200,
                new CarriedTitlePublicationTiming(
                        false, true, false, 0, 0, false, 0, 0, 0));

        Method onExitReady = S3kResultsScreenObjectInstance.class.getDeclaredMethod("onExitReady");
        onExitReady.setAccessible(true);
        onExitReady.invoke(results);
        onExitReady.invoke(results);

        assertEquals(List.of("3:1"), services.titleCard.calls,
                "The retained results SST mutates into the native in-level Act 2 title card");
        verify(services.levelManager, never()).resetLevelGamestate(org.mockito.ArgumentMatchers.any(LevelState.class));
    }

    @Test
    void aizActOneMinibossTitleHandoffDefersLevelGamestateResetToTitleCard() throws Exception {
        ActTransitionRecordingServices services = new ActTransitionRecordingServices(0x00, Sonic3kMusic.AIZ2.id);
        S3kResultsScreenObjectInstance results = transitionShell(
                services, PlayerCharacter.SONIC_AND_TAILS, 0);
        results.setServices(services);

        Method onExitReady = S3kResultsScreenObjectInstance.class.getDeclaredMethod("onExitReady");
        onExitReady.setAccessible(true);
        onExitReady.invoke(results);
        onExitReady.invoke(results);

        assertEquals(1, services.apparentAct,
                "AIZ Act 1 miniboss results still updates Apparent_act for the in-level Act 2 title card "
                        + "(docs/skdisasm/sonic3k.asm:62708-62720)");
        assertEquals(List.of("0:1"), services.titleCard.calls,
                "AIZ Act 1 miniboss results mutates into the in-level Act 2 title card "
                        + "without taking the seamless reload path");
        verify(services.levelManager, never()).resetLevelGamestate(org.mockito.ArgumentMatchers.any(LevelState.class));
    }

    @Test
    void hczAndMgzSeamlessActOneExitSetsTransitionReadyFlag() throws Exception {
        for (int zone : List.of(0x01, 0x02)) {
            ActTransitionRecordingServices services = new ActTransitionRecordingServices(zone, Sonic3kMusic.HCZ2.id);
            S3kResultsScreenObjectInstance results = transitionShell(
                    services, PlayerCharacter.SONIC_AND_TAILS, 0);
            results.setServices(services);

            Method onExitReady = S3kResultsScreenObjectInstance.class.getDeclaredMethod("onExitReady");
            onExitReady.setAccessible(true);
            onExitReady.invoke(results);

            verify(services.gameState, never()).setEndOfLevelFlag(true);
        }
    }

    @Test
    void armedTransitionProviderPublishesReadyFlagWithoutZoneInference() throws Exception {
        ActTransitionRecordingServices services =
                new ActTransitionRecordingServices(0x03, Sonic3kMusic.CNZ2.id, true);
        S3kResultsScreenObjectInstance results = transitionShell(
                services, PlayerCharacter.SONIC_AND_TAILS, 0);
        results.setServices(services);

        Method onExitReady = S3kResultsScreenObjectInstance.class.getDeclaredMethod("onExitReady");
        onExitReady.setAccessible(true);
        onExitReady.invoke(results);

        verify(services.gameState).setEndOfLevelFlag(true);
    }

    @Test
    void iczActTwoExitKeepsBossLeftCameraLockWhenRestoringLevelBounds() throws Exception {
        IczExitRecordingServices services = new IczExitRecordingServices();
        S3kResultsScreenObjectInstance results = transitionShell(
                services, PlayerCharacter.SONIC_ALONE, 1);
        results.setServices(services);

        Method onExitReady = S3kResultsScreenObjectInstance.class.getDeclaredMethod("onExitReady");
        onExitReady.setAccessible(true);
        onExitReady.invoke(results);

        assertEquals(0x4560, services.camera.getMinX() & 0xFFFF,
                "ICZ2's capsule handoff leaves Camera_min_X_pos at the post-boss lock instead of restoring level start");
        assertEquals(0x7000, services.camera.getMaxX() & 0xFFFF,
                "The right edge should still be released to the full level/end bounds for the results exit");
        assertEquals(0x0000, services.camera.getMinY() & 0xFFFF);
        assertEquals(0x0800, services.camera.getMaxY() & 0xFFFF);
    }

    @Test
    void lbzActTwoExitHandsOffToDeathEggFallWithoutRestoringCameraBounds() throws Exception {
        LbzExitRecordingServices services = new LbzExitRecordingServices();
        S3kResultsScreenObjectInstance results = transitionShell(
                services, PlayerCharacter.SONIC_AND_TAILS, 1);
        results.setServices(services);

        Method onExitReady = S3kResultsScreenObjectInstance.class.getDeclaredMethod("onExitReady");
        onExitReady.setAccessible(true);
        onExitReady.invoke(results);

        verify(services.gameState).setEndOfLevelFlag(true);
        assertEquals(0x4390, services.camera.getMinX() & 0xFFFF,
                "LBZ2 final boss results must leave the boss camera lock for Obj_LBZFinalBoss1/Death Egg handoff");
        assertEquals(0x4390, services.camera.getMaxX() & 0xFFFF,
                "Restoring full level bounds here lets the generic camera path swallow the post-results fall sequence");
        assertEquals(0x0668, services.camera.getMinY() & 0xFFFF);
        assertEquals(0x0668, services.camera.getMaxY() & 0xFFFF);
    }

    private static S3kResultsScreenObjectInstance transitionShell(
            TestObjectServices services, PlayerCharacter character, int act) {
        return ObjectConstructionContext.withRewindActiveRestore(
                () -> ObjectConstructionContext.construct(
                        services,
                        () -> new S3kResultsScreenObjectInstance(character, act)));
    }

    private static S3kResultsScreenObjectInstance resultsWithTimingAdjustment(
            String adjustmentName, boolean usesShortResultsChildRetireTail) throws Exception {
        Class<?> adjustmentClass = Class.forName(
                S3kSignpostInstance.class.getName() + "$ResultsChildTimingAdjustment");
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object adjustment = Enum.valueOf((Class) adjustmentClass, adjustmentName);
        Constructor<S3kResultsScreenObjectInstance> constructor =
                S3kResultsScreenObjectInstance.class.getDeclaredConstructor(
                        PlayerCharacter.class, int.class, int.class, int.class, int.class,
                        adjustmentClass, boolean.class);
        constructor.setAccessible(true);
        TestObjectServices services = new TestObjectServices();
        S3kResultsScreenObjectInstance results = ObjectConstructionContext.withRewindActiveRestore(
                () -> ObjectConstructionContext.construct(services,
                        () -> constructResults(constructor, adjustment, usesShortResultsChildRetireTail)));
        results.setServices(services);
        return results;
    }

    private static S3kResultsScreenObjectInstance constructResults(
            Constructor<S3kResultsScreenObjectInstance> constructor,
            Object adjustment, boolean usesShortResultsChildRetireTail) {
        try {
            return constructor.newInstance(PlayerCharacter.SONIC_AND_TAILS, 0,
                    0, 0, 3, adjustment, usesShortResultsChildRetireTail);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not construct results child", e);
        }
    }

    private static void invokeCreateGate(S3kResultsScreenObjectInstance results) throws Exception {
        Method updateCreateGate = S3kResultsScreenObjectInstance.class.getDeclaredMethod("updateCreateGate");
        updateCreateGate.setAccessible(true);
        updateCreateGate.invoke(results);
    }

    private static void invokeCreateElements(S3kResultsScreenObjectInstance results)
            throws Exception {
        Method createElements =
                S3kResultsScreenObjectInstance.class.getDeclaredMethod("createElements");
        createElements.setAccessible(true);
        createElements.invoke(results);
    }

    private static void placeElementsAtTargets(
            S3kResultsScreenObjectInstance results) throws Exception {
        Field elementsField =
                S3kResultsScreenObjectInstance.class.getDeclaredField("elements");
        elementsField.setAccessible(true);
        Object[] elements = (Object[]) elementsField.get(results);
        for (Object element : elements) {
            Field targetX = element.getClass().getDeclaredField("targetX");
            Field currentX = element.getClass().getDeclaredField("currentX");
            targetX.setAccessible(true);
            currentX.setAccessible(true);
            currentX.setInt(element, targetX.getInt(element));
        }
    }

    private static int privateInt(Object instance, String fieldName) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(instance);
    }

    private static int privateStaticInt(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static int superclassInt(Object instance, String fieldName) throws Exception {
        Field field = instance.getClass().getSuperclass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(instance);
    }

    private static boolean privateBoolean(Object instance, String fieldName) throws Exception {
        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(instance);
    }

    private static void prepareFinalExitDispatch(
            S3kResultsScreenObjectInstance results) throws Exception {
        setPrivate(results, "resultsChildrenCreated", true);
        setPrivate(results, "childrenRemaining", 0);
        setPrivate(results, "exitRetireDispatchesInitialized", true);
        setPrivate(results, "carriedResultsRenderRetireDispatches", 0);
        Field state = com.openggf.level.objects.AbstractResultsScreen.class
                .getDeclaredField("state");
        state.setAccessible(true);
        state.setInt(results, 4);
    }

    private static void prepareWaitDispatch(
            S3kResultsScreenObjectInstance results) throws Exception {
        setPrivate(results, "resultsChildrenCreated", true);
        Field state = com.openggf.level.objects.AbstractResultsScreen.class
                .getDeclaredField("state");
        state.setAccessible(true);
        state.setInt(results, 3);
    }

    private static void setPrivate(Object target, String fieldName, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setPrivateEnum(
            Object target, String fieldName, String constant) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, Enum.valueOf((Class<? extends Enum>) field.getType(), constant));
    }

    private static final class TransitionRecordingServices extends TestObjectServices {
        private final int zone;
        private final RecordingBridge bridge = new RecordingBridge();
        private int signalCount;

        private TransitionRecordingServices(int zone) {
            this.zone = zone;
        }

        @Override
        public int romZoneId() {
            return zone;
        }

        @Override
        public LevelEventProvider levelEventProvider() {
            return bridge;
        }

        private final class RecordingBridge implements LevelEventProvider, S3kTransitionEventBridge {
            @Override
            public void initLevel(int zone, int act) {
            }

            @Override
            public void update() {
            }

            @Override
            public void signalActTransition() {
                signalCount++;
            }

            @Override
            public void requestHczPostTransitionCutscene() {
            }

            @Override
            public void requestMgzPostTransitionRelease() {
            }

            @Override
            public void requestCnzPostTransitionRelease() {
            }
        }
    }

    private static final class ActTransitionRecordingServices extends TestObjectServices {
        private final int zone;
        private final int act2MusicId;
        private final GameStateManager gameState = mock(GameStateManager.class);
        private final Camera camera = new Camera();
        private final RecordingTitleCardProvider titleCard = new RecordingTitleCardProvider();
        private final LevelManager levelManager = mock(LevelManager.class);
        private final List<Integer> playedMusic = new ArrayList<>();
        private final boolean retainedTransitionFlagOwner;
        private ObjectArtProvider objectArtProvider;
        private int apparentAct = -1;

        private ActTransitionRecordingServices(int zone, int act2MusicId) {
            this(zone, act2MusicId, false);
        }

        private ActTransitionRecordingServices(int zone, int act2MusicId,
                                               boolean retainedTransitionFlagOwner) {
            this.zone = zone;
            this.act2MusicId = act2MusicId;
            this.retainedTransitionFlagOwner = retainedTransitionFlagOwner;
        }

        @Override
        public int romZoneId() {
            return zone;
        }

        @Override
        public GameStateManager gameState() {
            return gameState;
        }

        @Override
        public LevelManager levelManager() {
            return levelManager;
        }

        @Override
        public LevelEventProvider levelEventProvider() {
            return new ResultsTransitionBridge(retainedTransitionFlagOwner);
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public GameModule gameModule() {
            GameModule module = mock(GameModule.class);
            ZoneRegistry zoneRegistry = mock(ZoneRegistry.class);
            when(zoneRegistry.getMusicId(zone, 1)).thenReturn(act2MusicId);
            when(module.getZoneRegistry()).thenReturn(zoneRegistry);
            when(module.getObjectArtProvider()).thenReturn(objectArtProvider);
            return module;
        }

        @Override
        public void playMusic(int musicId) {
            playedMusic.add(musicId);
        }

        @Override
        public void setApparentAct(int act) {
            apparentAct = act;
        }

        @Override
        public TitleCardProvider titleCardProvider() {
            return titleCard;
        }
    }

    private static final class ResultsTransitionBridge
            implements LevelEventProvider, S3kTransitionEventBridge {
        private final boolean retainedTransitionFlagOwner;

        private ResultsTransitionBridge(boolean retainedTransitionFlagOwner) {
            this.retainedTransitionFlagOwner = retainedTransitionFlagOwner;
        }

        @Override public void initLevel(int zone, int act) {}
        @Override public void update() {}
        @Override public void signalActTransition() {}
        @Override public void requestHczPostTransitionCutscene() {}
        @Override public boolean restorePendingPostResultsPlayerControl() {
            return retainedTransitionFlagOwner;
        }
        @Override public void requestMgzPostTransitionRelease() {}
        @Override public void requestCnzPostTransitionRelease() {}
    }

    private static final class IczExitRecordingServices extends TestObjectServices {
        private final Camera camera = new Camera();
        private final GameStateManager gameState = mock(GameStateManager.class);
        private final Level level = mock(Level.class);

        private IczExitRecordingServices() {
            camera.setMinX((short) 0x4560);
            camera.setMaxX((short) 0x44C0);
            camera.setMinY((short) 0x05F8);
            camera.setMaxY((short) 0x05F8);
            when(level.getMinX()).thenReturn(0);
            when(level.getMaxX()).thenReturn(0x7000);
            when(level.getMinY()).thenReturn(0);
            when(level.getMaxY()).thenReturn(0x0800);
        }

        @Override
        public int romZoneId() {
            return 0x05;
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public GameStateManager gameState() {
            return gameState;
        }

        @Override
        public Level currentLevel() {
            return level;
        }
    }

    private static final class LbzExitRecordingServices extends TestObjectServices {
        private final Camera camera = new Camera();
        private final GameStateManager gameState = mock(GameStateManager.class);
        private final Level level = mock(Level.class);

        private LbzExitRecordingServices() {
            camera.setMinX((short) 0x4390);
            camera.setMaxX((short) 0x4390);
            camera.setMinY((short) 0x0668);
            camera.setMaxY((short) 0x0668);
            when(level.getMinX()).thenReturn(0);
            when(level.getMaxX()).thenReturn(0x7000);
            when(level.getMinY()).thenReturn(0);
            when(level.getMaxY()).thenReturn(0x0800);
        }

        @Override
        public int romZoneId() {
            return 0x06;
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public GameStateManager gameState() {
            return gameState;
        }

        @Override
        public Level currentLevel() {
            return level;
        }
    }

    private static final class RecordingTitleCardProvider implements TitleCardProvider {
        private final List<String> calls = new ArrayList<>();

        @Override
        public void initialize(int zoneIndex, int actIndex) {
            calls.add(zoneIndex + ":" + actIndex);
        }

        @Override
        public void initializeInLevel(int zoneIndex, int actIndex) {
            calls.add(zoneIndex + ":" + actIndex);
        }

        @Override public void update() {}
        @Override public boolean shouldReleaseControl() { return false; }
        @Override public boolean isOverlayActive() { return false; }
        @Override public boolean isComplete() { return false; }
        @Override public void draw() {}
        @Override public void reset() {}
        @Override public int getCurrentZone() { return -1; }
        @Override public int getCurrentAct() { return -1; }
    }
}
