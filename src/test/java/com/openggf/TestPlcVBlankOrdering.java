package com.openggf;

import com.openggf.camera.Camera;
import com.openggf.game.GameModule;
import com.openggf.game.NoOpBonusStageProvider;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.level.LevelManager;
import com.openggf.level.resources.NemesisPlcServiceQueue;
import com.openggf.level.resources.PlcParser.PlcDefinition;
import com.openggf.level.resources.PlcParser.PlcEntry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectWindowingStrategy;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestPlcVBlankOrdering {

    @Test
    void canonicalFrameStepDispatchesGameOwnedVBlankExactlyOnce() {
        AtomicInteger dispatched = new AtomicInteger();
        com.openggf.game.LevelInitProfile profile =
                (com.openggf.game.LevelInitProfile) Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{com.openggf.game.LevelInitProfile.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("serviceLevelLoadVBlank")) {
                                dispatched.incrementAndGet();
                            }
                            return switch (method.getReturnType().getName()) {
                                case "boolean" -> false;
                                case "int" -> 0;
                                default -> null;
                            };
                        });
        GameModule module = mock(GameModule.class);
        when(module.getLevelInitProfile()).thenReturn(profile);
        var coordinator = new PlcFrameLifecycleCoordinator((PlcLifecycleService) null);
        var frame = coordinator.latchBeforeFadeUpdate();

        LevelFrameStep.executeHardwareTimedObjectScan(
                context(module, new ArrayList<>()), frame,
                PlcLifecyclePhase.LEVEL_TITLE_CARD, () -> { });
        frame.finish();

        assertEquals(1, dispatched.get(),
                "the canonical frame step must own game-specific VBlank work");
    }

    @Test
    void ordinaryLevelServicesPlcBeforeEventsAndObjects() {
        List<String> calls = new ArrayList<>();
        GameModule module = mock(GameModule.class);
        PlcLifecycleService service = recordingService(calls);
        when(module.getGameService(PlcLifecycleService.class)).thenReturn(service);
        LevelManager level = mock(LevelManager.class);
        org.mockito.Mockito.doAnswer(ignored -> {
            calls.add("objects");
            return null;
        }).when(level).updateObjectPositionsWithoutTouches(false);

        LevelFrameTestStep.execute(context(module, calls), level, mock(Camera.class), () -> calls.add("physics"));

        assertEquals(List.of("vblank-service", "vint", "objects", "physics", "prepare"), calls);
    }

    @Test
    void vblankOnlyRowDoesNotServiceLevelPlc() {
        List<String> calls = new ArrayList<>();
        GameModule module = mock(GameModule.class);
        when(module.getGameService(PlcLifecycleService.class)).thenReturn(recordingService(calls));

        LevelFrameTestStep.serviceVBlankOnly(context(module, calls));

        assertEquals(List.of("vint"), calls);
    }

    @Test
    void completedEntryPreparesButDoesNotConsumeSuccessorUntilNextToken() {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        queue.append(new PlcDefinition(0, List.of(
                new PlcEntry(0x100, 1), new PlcEntry(0x200, 2))),
                List.of(3, 5));
        queue.prepareHead();

        PlcLifecycleService lifecycle = queueLifecycle(queue);
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(lifecycle);
        LevelFrameContext frameContext = context(mock(GameModule.class),
                new ArrayList<>());
        var first = coordinator.latchBeforeFadeUpdate();
        LevelFrameStep.executeHardwareTimedObjectScan(
                frameContext, first, PlcLifecyclePhase.SPECIAL_STAGE, () -> {
                    var duringScan = queue.capture();
                    assertEquals(null, duringScan.activeEntry());
                    assertEquals(1, duringScan.queuedEntries().size());
                    assertEquals(5,
                            duringScan.queuedEntries().getFirst().remainingPatterns());
                });
        first.finish();

        var afterPreparation = queue.capture();
        assertEquals(5, afterPreparation.activeEntry().remainingPatterns());
        assertEquals(0, afterPreparation.queuedEntries().size());

        var second = coordinator.latchBeforeFadeUpdate();
        LevelFrameStep.executeHardwareTimedObjectScan(
                frameContext, second, PlcLifecyclePhase.SPECIAL_STAGE, () -> { });
        second.finish();
        assertEquals(2, queue.capture().activeEntry().remainingPatterns());
    }

    @Test
    void orderedScanObservesAppendClearAndReplaceSynchronouslyInEitherSlotOrder() {
        MutableFacade facade = new MutableFacade();
        List<List<String>> observations = new ArrayList<>();

        dispatchObjectSlots(
                () -> facade.append("append"),
                () -> observations.add(facade.snapshot()));
        assertEquals(List.of(List.of("append")), observations);

        observations.clear();
        facade.replace("seed");
        dispatchObjectSlots(
                () -> observations.add(facade.snapshot()),
                facade::clear);
        assertEquals(List.of(List.of("seed")), observations);
        assertEquals(List.of(), facade.snapshot());

        observations.clear();
        facade.append("old");
        dispatchObjectSlots(
                () -> facade.replace("replacement"),
                () -> observations.add(facade.snapshot()));
        assertEquals(List.of(List.of("replacement")), observations);

        observations.clear();
        dispatchObjectSlots(
                () -> observations.add(facade.snapshot()),
                () -> facade.append("later"));
        assertEquals(List.of(List.of("replacement")), observations);
        assertEquals(List.of("replacement", "later"), facade.snapshot());
    }

    private static LevelFrameContext context(GameModule module, List<String> calls) {
        return new LevelFrameContext(module, null, null, NoOpBonusStageProvider.INSTANCE,
                null, null, null, null, new HardwareTimingService(),
                boundary -> {
                    if (boundary == com.openggf.game.timing.HardwareServiceBoundary.VINT_SERVICE) {
                        calls.add("vint");
                    }
                }, null);
    }

    private static PlcLifecycleService recordingService(List<String> calls) {
        return new PlcLifecycleService() {
            @Override
            public void serviceVBlank(PlcLifecyclePhase phase) {
                if (phase == PlcLifecyclePhase.ORDINARY_LEVEL) {
                    calls.add("vblank-service");
                }
            }

            @Override
            public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
                return phase == PlcLifecyclePhase.ORDINARY_LEVEL;
            }

            @Override
            public void prepareAfterLoop(PlcLifecyclePhase phase) {
                calls.add("prepare");
            }
        };
    }

    private static PlcLifecycleService queueLifecycle(
            NemesisPlcServiceQueue queue) {
        return new PlcLifecycleService() {
            @Override
            public void serviceVBlank(PlcLifecyclePhase phase) {
                queue.servicePatterns(3);
            }

            @Override
            public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
                return true;
            }

            @Override
            public void prepareAfterLoop(PlcLifecyclePhase phase) {
                queue.prepareHead();
            }
        };
    }

    private static void dispatchObjectSlots(Runnable first, Runnable second) {
        ObjectRegistry registry = mock(ObjectRegistry.class);
        when(registry.objectSlotLayout()).thenReturn(ObjectSlotLayout.SONIC_1);
        when(registry.objectWindowingStrategy())
                .thenReturn(ObjectWindowingStrategy.LEGACY);
        Camera camera = mock(Camera.class);
        when(camera.getWidth()).thenReturn((short) 320);
        TestObjectServices services = new TestObjectServices().withCamera(camera);
        ObjectManager manager = new ObjectManager(
                List.of(), registry, -1, null, null, null, camera, services);
        manager.createDynamicObject(() -> new SlotCallbackObject(first));
        manager.createDynamicObject(() -> new SlotCallbackObject(second));
        manager.update(0, null, List.of(), 0);
    }

    private static final class MutableFacade {
        private final List<String> queued = new ArrayList<>();

        void append(String value) {
            queued.add(value);
        }

        void clear() {
            queued.clear();
        }

        void replace(String value) {
            queued.clear();
            queued.add(value);
        }

        List<String> snapshot() {
            return List.copyOf(queued);
        }

    }

    private static final class SlotCallbackObject extends AbstractObjectInstance {
        private final Runnable callback;

        private SlotCallbackObject(Runnable callback) {
            super(new ObjectSpawn(0, 0, 1, 0, 0, false, 0),
                    "PLC slot callback");
            this.callback = callback;
        }

        @Override
        public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
            callback.run();
        }

        @Override
        public void appendRenderCommands(
                List<com.openggf.graphics.GLCommand> commands) {
        }
    }
}
