package com.openggf.game.sonic3k.titlecard;

import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.game.sonic3k.Sonic3k;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.rewind.snapshot.PlcProgressSnapshot;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueue;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueueSnapshot;
import com.openggf.level.LevelData;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.tests.HardwareBoundaryPump;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kTitleCardKosQueue {
    private static final int[] NORMAL_SOURCES = {
            Sonic3kConstants.ART_KOSM_TITLE_CARD_RED_ACT_ADDR,
            Sonic3kConstants.ART_KOSM_TITLE_CARD_S3K_ZONE_ADDR,
            Sonic3kConstants.ART_KOSM_TITLE_CARD_NUM1_ADDR,
            Sonic3kConstants.TITLE_CARD_ZONE_ART_ADDRS[0]
    };
    private static final int[] NORMAL_DESTINATIONS = {0x500, 0x510, 0x53D, 0x54D};
    private static final int[] BONUS_SOURCES = {
            Sonic3kConstants.ART_KOSM_TITLE_CARD_RED_ACT_ADDR,
            Sonic3kConstants.ART_KOSM_TITLE_CARD_S3K_ZONE_ADDR,
            Sonic3kConstants.ART_KOSM_BONUS_TITLE_CARD_ADDR
    };
    private static final int[] BONUS_DESTINATIONS = {0x500, 0x510, 0x54D};

    private HardwareTimingService timing;
    private Rom rom;
    private Sonic3kTitleCardManager manager;

    @BeforeEach
    void setUp() throws Exception {
        timing = GameServices.hardwareTiming();
        timing.resetForMissingSnapshot();
        rom = TestEnvironment.currentRom();
        manager = new Sonic3kTitleCardManager();
        manager.reset();
        prepareTitleLease((Sonic3kObjectArtProvider) GameServices.module()
                .getObjectArtProvider());
    }

    @Test
    void normalCardQueuesFourArchivesInOrderAndRewindsInFlightBoundary() throws Exception {
        List<HardwareWorkHandle> expected =
                expectedHandles(NORMAL_SOURCES, NORMAL_DESTINATIONS);

        manager.initialize(0, 0);

        assertEquals(expected, moduleHandles(),
                "normal title art must retain native four-archive FIFO order");
        assertTrue(isArtLoading(manager));
        assertEquals(0, readyCount(expected));

        service(HardwareServiceBoundary.POST_OBJECTS);
        service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        assertEquals(0, readyCount(expected),
                "PRE_MAIN_LOOP may advance descriptors but must not publish readiness");
        HardwareTimingSnapshot rewindPoint = timing.capture();
        S3kKosDecompressionQueueSnapshot directRewindPoint =
                directQueue().capture();

        service(HardwareServiceBoundary.POST_OBJECTS);
        service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        service(HardwareServiceBoundary.POST_OBJECTS);
        timing.restore(rewindPoint);
        directQueue().restore(directRewindPoint);

        assertEquals(HardwareServiceBoundary.PRE_MAIN_LOOP,
                timing.capture().lastServicedBoundary());
        assertEquals(expected, moduleHandles());
        assertEquals(0, readyCount(expected),
                "rewind must restore the exact in-flight, not-ready decoder state");

        drainThroughPostObjects(expected);
    }

    @Test
    void bonusCardQueuesThreeArchivesInOrderAndPollsUntilFinalPostObjects() throws Exception {
        List<HardwareWorkHandle> expected =
                expectedHandles(BONUS_SOURCES, BONUS_DESTINATIONS);

        manager.initializeBonus();

        assertEquals(expected, moduleHandles(),
                "bonus title art must retain native three-archive FIFO order");
        assertTrue(isArtLoading(manager));
        drainThroughPostObjects(expected);
    }

    @Test
    void readyUnclaimedTitleJobsDoNotSubmitEnemyArt() throws Exception {
        CountingObjectArtProvider provider = installCountingProvider();

        manager.initialize(0, 0);
        List<HardwareWorkHandle> titleHandles = moduleHandles();
        for (int frame = 0;
                frame < 100_000 && readyCount(titleHandles) < titleHandles.size();
                frame++) {
            service(HardwareServiceBoundary.PRE_MAIN_LOOP);
            service(HardwareServiceBoundary.POST_OBJECTS);
        }
        assertEquals(titleHandles.size(), readyCount(titleHandles));

        provider.processRuntimeArtQueue();
        assertEquals(titleHandles, moduleHandles(),
                "readiness alone is not native title-card retirement");

        manager.update();
        provider.processRuntimeArtQueue();

        assertEquals(0, provider.titleCardRetirementCount,
                "claiming title payloads is not title-owner retirement");
        assertTrue(moduleHandles().isEmpty(),
                "enemy art remains held until the title owner reaches COMPLETE");
    }

    @Test
    void repeatedSameZoneCachedCardPublishesExactlyOneFreshTerrainBatch()
            throws Exception {
        Object previousGame = getLevelManagerGame();
        setLevelManagerGame(new Sonic3k(rom));
        try {
            installCachedTitleArt();
            manager.requestFreshLevelRuntimeArtHandoff(
                    LevelData.S3K_ANGEL_ISLAND_1.getLevelIndex());
            manager.initialize(0, 0);

            manager.update();
            S3kRuntimeArtCoordinator.current().afterTimingService(
                    HardwareServiceBoundary.PRE_MAIN_LOOP);

            assertEquals(2, moduleHandles().size(),
                    "cached title readiness publishes the two-parent terrain batch");
            assertEquals(-1,
                    manager.capture().freshLevelRuntimeArtHandoffLevelIndex());

            manager.update();
            S3kRuntimeArtCoordinator.current().afterTimingService(
                    HardwareServiceBoundary.PRE_MAIN_LOOP);
            assertEquals(2, moduleHandles().size(),
                    "the armed handoff is consumed exactly once");
        } finally {
            setLevelManagerGame(previousGame);
        }
    }

    @Test
    void freshLevelOwnerPublishesParentBeforeDeferringFirstDirectChild()
            throws Exception {
        Object previousGame = getLevelManagerGame();
        setLevelManagerGame(new Sonic3k(rom));
        try {
            setPendingFreshLevelTransitionBoundary(true);
            installCachedTitleArt();
            setField(manager, "lastLoadedZone", 6);
            manager.requestFreshLevelRuntimeArtHandoff(
                    LevelData.S3K_LAUNCH_BASE_1.getLevelIndex());
            manager.initializeFreshLevelTransition(6, 0);
            setField(manager, "freshLevelTitleOwnerReplacedAtAssembly", true);
            setField(manager, "state", Sonic3kTitleCardState.DISPLAY);
            setField(manager, "stateTimer", 21);

            manager.update();

            assertEquals(Sonic3kTitleCardState.EXIT, manager.capture().state());
            assertTrue(manager.shouldCompleteFreshLevelTransitionBoundary(),
                    "the cleared child slots let the retained owner retire with its wait");
            assertEquals(2, moduleHandles().size());
            assertFalse(directQueue().decompressionsPending());

            service(HardwareServiceBoundary.POST_OBJECTS);
            service(HardwareServiceBoundary.PRE_MAIN_LOOP);
            S3kRuntimeArtCoordinator.current().finishHeldLoopTailClosure();
            assertFalse(directQueue().decompressionsPending(),
                    "the owner row publishes only the KosM parent");

            service(HardwareServiceBoundary.POST_OBJECTS);
            assertTrue(directQueue().decompressionsPending(),
                    "the following loop publishes the first direct child");
        } finally {
            setPendingFreshLevelTransitionBoundary(false);
            setLevelManagerGame(previousGame);
        }
    }

    @Test
    void cachedTitleArtDoesNotRetireRuntimeArtAtInitialization() throws Exception {
        CountingObjectArtProvider provider = installCountingProvider();
        setField(manager, "artLoaded", true);
        setField(manager, "lastLoadedZone", 0);
        setField(manager, "lastLoadedAct", 0);
        setField(manager, "combinedPatterns", new com.openggf.level.Pattern[0x100]);

        manager.initialize(0, 0);
        manager.update();

        assertEquals(0, provider.titleCardRetirementCount,
                "cached title art has no payload-readiness retirement edge");
        assertFalse(provider.capture().runtimeArtAdmissionConsumed());
    }

    @Test
    void ordinaryInLevelCompletionArmsAndSubmitsOnFollowingRuntimePass()
            throws Exception {
        Sonic3kObjectArtProvider provider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        installCachedTitleArt();
        manager.initializeInLevel(0, 0);
        prepareExitForCompletion(manager);

        manager.update();

        PlcProgressSnapshot completed = provider.capture();
        assertTrue(manager.isComplete());
        assertTrue(completed.runtimeArtAdmissionConsumed());
        assertFalse(completed.kosSubmissionArmed());
        assertTrue((completed.runtimeState() & (1 << 4)) != 0);
        assertEquals(List.of(), completed.pendingKosOrdinals());

        provider.processRuntimeArtQueue();
        PlcProgressSnapshot edge = provider.capture();
        assertTrue(edge.kosSubmissionArmed());
        assertTrue((edge.runtimeState() & (1 << 4)) == 0);
        assertEquals(3, edge.pendingKosOrdinals().size(),
                "the following runtime pass submits the native enemy batch");
    }

    @Test
    void heldResultsTitleCompletionConsumesDirectlyWithoutDeferral()
            throws Exception {
        Sonic3kObjectArtProvider provider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        installCachedTitleArt();
        manager.initializeInLevel(0, 0);
        manager.requestLevelGamestateResetAfterCreateDispatches(1);
        prepareExitForCompletion(manager);

        manager.update();

        PlcProgressSnapshot completed = provider.capture();
        assertTrue(manager.isComplete());
        assertTrue(completed.runtimeArtAdmissionConsumed());
        assertTrue(completed.kosSubmissionArmed());
        assertTrue((completed.runtimeState() & (1 << 4)) == 0,
                "held/carried title owners consume directly on their SST dispatch");

        provider.processRuntimeArtQueue();
        assertEquals(3, provider.capture().pendingKosOrdinals().size());
    }

    @Test
    void titleOwnerRetiresRuntimeArtExactlyOnceOnCompleteTransition() throws Exception {
        CountingObjectArtProvider provider = installCountingProvider();
        setField(manager, "artLoaded", true);
        setField(manager, "lastLoadedZone", 0);
        setField(manager, "lastLoadedAct", 0);
        setField(manager, "combinedPatterns", new com.openggf.level.Pattern[0x100]);
        manager.initialize(0, 0);
        prepareExitForCompletion(manager);

        manager.update();

        assertTrue(manager.isComplete());
        assertEquals(1, provider.titleCardRetirementCount,
                "the EXIT to COMPLETE transition is the sole title-owner release");

        manager.update();
        manager.reset();

        assertEquals(1, provider.titleCardRetirementCount,
                "later COMPLETE updates and reset do not replay retirement");
    }

    @Test
    void rewindAfterTitleRetirementRebindsEnemyJobsWithoutResubmission()
            throws Exception {
        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) GameServices.module()
                        .getObjectArtProvider();

        manager.initialize(0, 0);
        List<HardwareWorkHandle> titleHandles =
                moduleHandles();
        for (int frame = 0;
                frame < 4096 && readyCount(titleHandles) < titleHandles.size();
                frame++) {
            service(HardwareServiceBoundary.PRE_MAIN_LOOP);
            service(HardwareServiceBoundary.POST_OBJECTS);
        }
        manager.update();
        prepareExitForCompletion(manager);
        manager.update();
        provider.processRuntimeArtQueue();

        List<HardwareWorkHandle> enemyHandles =
                moduleHandles();
        assertEquals(List.of(4L, 5L, 6L),
                enemyHandles.stream().map(HardwareWorkHandle::ordinal).toList());
        HardwareTimingSnapshot timingSnapshot = timing.capture();
        S3kKosDecompressionQueueSnapshot directSnapshot =
                directQueue().capture();
        PlcProgressSnapshot providerSnapshot = provider.capture();
        assertTrue(providerSnapshot.kosSubmissionArmed());
        assertEquals(List.of(4L, 5L, 6L),
                providerSnapshot.pendingKosOrdinals());

        service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        service(HardwareServiceBoundary.POST_OBJECTS);
        timing.restore(timingSnapshot);
        directQueue().restore(directSnapshot);
        provider.restore(providerSnapshot);
        provider.processRuntimeArtQueue();

        assertEquals(enemyHandles, moduleHandles());
        assertEquals(7L, timing.capture().nextOrdinals()
                .get(com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE));
    }

    private List<HardwareWorkHandle> expectedHandles(
            int[] sources, int[] destinations) throws Exception {
        HardwareTimingService expectedTiming = new HardwareTimingService();
        S3kKosModuleQueue expectedQueue = new S3kKosModuleQueue(
                expectedTiming, new S3kKosDecompressionQueue(expectedTiming));
        List<HardwareWorkHandle> handles = new ArrayList<>(sources.length);
        for (int i = 0; i < sources.length; i++) {
            handles.add(expectedQueue.queue(rom, sources[i], destinations[i]));
        }
        return List.copyOf(handles);
    }

    private void drainThroughPostObjects(List<HardwareWorkHandle> handles) throws Exception {
        // Kos_modules_left is decremented at exactly one site,
        // Process_Kos_Module_Queue (docs/skdisasm/sonic3k.asm:2750-2752), which LevelLoop
        // reaches at 7908 -- immediately after the object pass (ExecuteObjects,
        // 7900-7906), i.e. POST_OBJECTS. Process_Kos_Queue (7887) follows it in the same
        // loop tail and services only the decompression queue, so it can never retire an
        // archive. The object pass therefore observes readiness; it cannot create it.
        boolean sawPostObjectsPublication = false;
        for (int frame = 0; frame < 4096 && readyCount(handles) < handles.size(); frame++) {
            int readyBefore = readyCount(handles);

            manager.update();
            assertTrue(isArtLoading(manager),
                    "the title-card consumer must keep polling until every archive is ready");
            assertEquals(readyBefore, readyCount(handles),
                    "the object pass observes KosM readiness, it cannot create it");

            service(HardwareServiceBoundary.POST_OBJECTS);
            int readyAfterPost = readyCount(handles);
            assertTrue(readyAfterPost >= readyBefore,
                    "module readiness must never regress across a LevelLoop iteration");
            // One call to Process_Kos_Module_Queue per iteration, taking one of two
            // mutually exclusive branches: submit (2741) or DMA-and-decrement
            // (2750-2752). The shift-out tail (2778-2788) re-enters
            // Process_Kos_Module_Queue_Init (2694-2713) for the next archive, which
            // reloads Kos_modules_left rather than retiring a second archive.
            assertTrue(readyAfterPost - readyBefore <= 1,
                    "at most one archive may retire per LevelLoop iteration");
            if (readyAfterPost > readyBefore) {
                sawPostObjectsPublication = true;
            }

            service(HardwareServiceBoundary.PRE_MAIN_LOOP);
            assertEquals(readyAfterPost, readyCount(handles),
                    "Process_Kos_Queue (7887) advances only the decompression queue and "
                            + "must not publish newly completed KosM work");
        }

        assertEquals(handles.size(), readyCount(handles));
        assertTrue(sawPostObjectsPublication,
                "at least one archive must publish readiness at the module state step");
        assertTrue(isArtLoading(manager),
                "the state step publishes readiness without running the consumer");

        manager.update();

        assertFalse(isArtLoading(manager));
        assertTrue(timing.pendingHandles().isEmpty(),
                "the title-card consumer must claim all retained handles in FIFO order");
    }

    private int readyCount(List<HardwareWorkHandle> handles) {
        int count = 0;
        for (HardwareWorkHandle handle : handles) {
            if (timing.isReady(handle)) {
                count++;
            }
        }
        return count;
    }

    private void service(HardwareServiceBoundary boundary) {
        HardwareBoundaryPump.service(
                timing, S3kRuntimeArtCoordinator.current(), boundary);
    }

    private S3kKosDecompressionQueue directQueue() {
        return S3kRuntimeArtCoordinator.current().directQueue();
    }

    private List<HardwareWorkHandle> moduleHandles() {
        return timing.pendingHandles().stream()
                .filter(handle -> handle.kind()
                        == com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE)
                .toList();
    }

    private static boolean isArtLoading(Sonic3kTitleCardManager manager) throws Exception {
        Field field = Sonic3kTitleCardManager.class.getDeclaredField("artLoading");
        field.setAccessible(true);
        return field.getBoolean(manager);
    }

    private static CountingObjectArtProvider installCountingProvider() throws Exception {
        Sonic3kGameModule module = (Sonic3kGameModule) GameServices.module();
        Field field = Sonic3kGameModule.class.getDeclaredField("objectArtProvider");
        field.setAccessible(true);
        CountingObjectArtProvider provider = new CountingObjectArtProvider();
        field.set(module, provider);
        prepareTitleLease(provider);
        return provider;
    }

    private static void prepareTitleLease(Sonic3kObjectArtProvider provider)
            throws Exception {
        provider.prepareRuntimeArtForActTransition(
                0, RuntimeArtAdmissionPolicy.TITLE_OWNER);
    }

    private static void prepareExitForCompletion(Sonic3kTitleCardManager manager)
            throws Exception {
        setField(manager, "state", Sonic3kTitleCardState.EXIT);
        setField(manager, "exitChildrenGone", true);
        setField(manager, "actNumberVisible", true);
        boolean[] exited = (boolean[]) getField(manager, "elemExited");
        java.util.Arrays.fill(exited, true);
    }

    private void installCachedTitleArt() throws Exception {
        setField(manager, "artLoaded", true);
        setField(manager, "lastLoadedZone", 0);
        setField(manager, "lastLoadedAct", 0);
        setField(manager, "combinedPatterns", new com.openggf.level.Pattern[0x100]);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object getLevelManagerGame() throws Exception {
        Field field = GameServices.level().getClass().getDeclaredField("game");
        field.setAccessible(true);
        return field.get(GameServices.level());
    }

    private static void setLevelManagerGame(Object game) throws Exception {
        Field field = GameServices.level().getClass().getDeclaredField("game");
        field.setAccessible(true);
        field.set(GameServices.level(), game);
    }

    private static void setPendingFreshLevelTransitionBoundary(boolean pending)
            throws Exception {
        Field field = GameServices.level().getClass()
                .getDeclaredField("pendingFreshLevelTransitionBoundary");
        field.setAccessible(true);
        if (!pending) {
            field.set(GameServices.level(), null);
            return;
        }
        var constructor = field.getType().getDeclaredConstructor(
                short.class, short.class, int.class,
                short.class, short.class, List.class);
        constructor.setAccessible(true);
        field.set(GameServices.level(), constructor.newInstance(
                (short) 0, (short) 0, 0, (short) 0, (short) 0, List.of()));
    }

    private static final class CountingObjectArtProvider extends Sonic3kObjectArtProvider {
        private int titleCardRetirementCount;

        @Override
        public void consumeRuntimeArtAdmission(
                RuntimeArtAdmissionLease lease,
                RuntimeArtAdmissionOwnerKind ownerKind) {
            super.consumeRuntimeArtAdmission(lease, ownerKind);
            titleCardRetirementCount++;
        }
    }
}
