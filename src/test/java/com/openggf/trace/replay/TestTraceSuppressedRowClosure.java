package com.openggf.trace.replay;

import com.openggf.LevelFrameContext;
import com.openggf.game.GameModule;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTraceSuppressedRowClosure {

    @Test
    void representedClosureCountRejectsMultipleHardwareClosures() {
        assertThrows(IllegalArgumentException.class, () ->
                TraceSuppressedRowClosure.executeRepresented(
                        2, null, null, null, null, null));
    }

    @Test
    void heldOverlayWorkIsDeferredOnlyForTheSuppressedClosureOwner() {
        TitleCardProvider titleCard = mock(TitleCardProvider.class);
        when(titleCard.isOverlayActive()).thenReturn(true);
        when(titleCard.advancesOnHeldLevelCounter()).thenReturn(true);
        Runnable pendingTitle = mock(Runnable.class);

        TraceSuppressedRowClosure.executeUnownedTitleCardWork(
                true, titleCard, pendingTitle, ignored -> { });

        verify(titleCard, never()).update();
        verify(pendingTitle, never()).run();
    }

    @Test
    void lagRowClaimsOneVblankWithoutPlcWorkOrPreparation() {
        List<String> events = new ArrayList<>();
        TitleCardProvider titleCard = mock(TitleCardProvider.class);
        LevelEventProvider levelEvents = recordingLevelEvents(events);
        LevelManager level = recordingLevel(events, true);
        LevelFrameContext context = context(titleCard, levelEvents, events);
        PlcFrameLifecycleCoordinator lifecycle =
                new PlcFrameLifecycleCoordinator(recordingPlc(events));

        lifecycle.runLogicalIteration(() -> { }, frame -> {
            TraceSuppressedRowClosure.execute(
                    context,
                    frame,
                    level,
                    () -> events.add("pending-title"),
                    locked -> events.add("control:" + locked));
            return null;
        });

        assertEquals(List.of(
                "phase:LAG",
                "boundary:VINT_SERVICE",
                "pending-title",
                "event-vblank",
                "object-vblank"), events);
    }

    @Test
    void scheduledSuppressedCompletionRunsAfterVintBeforeVblankState() {
        List<String> events = new ArrayList<>();
        TitleCardProvider titleCard = mock(TitleCardProvider.class);
        LevelEventProvider levelEvents = recordingLevelEvents(events);
        LevelManager level = recordingLevel(events, true);
        TraceHardwareTimingBoundaryObserver observer =
                mock(TraceHardwareTimingBoundaryObserver.class);
        doAnswer(invocation -> {
            events.add("boundary:" + invocation.getArgument(0));
            return null;
        }).when(observer).onBoundary(
                org.mockito.ArgumentMatchers.any());
        doAnswer(ignored -> {
            events.add("suppressed-completion");
            return true;
        }).when(observer).applySuppressedRowCompletion();
        RuntimeArtCoordinator coordinator = mock(RuntimeArtCoordinator.class);
        doAnswer(invocation -> {
            events.add("coordinator-after:" + invocation.getArgument(0));
            return null;
        }).when(coordinator).afterTimingService(
                org.mockito.ArgumentMatchers.any());
        LevelFrameContext context = context(
                titleCard, levelEvents, observer, coordinator, events);
        PlcFrameLifecycleCoordinator lifecycle =
                new PlcFrameLifecycleCoordinator(recordingPlc(events));

        lifecycle.runLogicalIteration(() -> { }, frame -> {
            TraceSuppressedRowClosure.execute(
                    context,
                    frame,
                    level,
                    () -> events.add("pending-title"),
                    locked -> events.add("control:" + locked));
            return null;
        });

        assertEquals(List.of(
                "phase:LAG",
                "coordinator-after:VINT_SERVICE",
                "boundary:VINT_SERVICE",
                "suppressed-completion",
                "coordinator-after:PRE_MAIN_LOOP",
                "pending-title",
                "event-vblank",
                "object-vblank"), events);
        verify(observer).applySuppressedRowCompletion();
        verify(coordinator).afterTimingService(
                com.openggf.game.timing.HardwareServiceBoundary.PRE_MAIN_LOOP);
    }

    @Test
    void heldTitleCardRunsOneHardwareTimedScanThenCompletesSkippedRowState() {
        List<String> events = new ArrayList<>();
        TitleCardProvider titleCard = mock(TitleCardProvider.class);
        when(titleCard.advancesOnHeldLevelCounter()).thenReturn(true);
        when(titleCard.ownsRetainedResultsHeldLevelCounter()).thenReturn(true);
        when(titleCard.ownsInLevelPlayerControlLock()).thenReturn(true);
        when(titleCard.shouldLockPlayerControlForInLevelOverlay()).thenReturn(true);
        doAnswer(ignored -> {
            events.add("title-scan");
            return null;
        }).when(titleCard).update();
        LevelEventProvider levelEvents = recordingLevelEvents(events);
        LevelManager level = recordingLevel(events, true);
        LevelFrameContext context = context(titleCard, levelEvents, events);
        PlcFrameLifecycleCoordinator lifecycle =
                new PlcFrameLifecycleCoordinator(recordingPlc(events));

        lifecycle.runLogicalIteration(() -> { }, frame -> {
            TraceSuppressedRowClosure.execute(
                    context,
                    frame,
                    level,
                    () -> events.add("pending-title"),
                    locked -> events.add("control:" + locked));
            return null;
        });

        assertEquals(List.of(
                "plc-service:LEVEL_TITLE_CARD",
                "boundary:VINT_SERVICE",
                "title-scan",
                "fixed-objects",
                "boundary:POST_OBJECTS",
                "boundary:PRE_MAIN_LOOP",
                "plc-prepare:LEVEL_TITLE_CARD",
                "control:true",
                "pending-title",
                "event-vblank",
                "object-vblank"), events);
    }

    private static LevelFrameContext context(
            TitleCardProvider titleCard,
            LevelEventProvider levelEvents,
            List<String> events) {
        return context(
                titleCard,
                levelEvents,
                boundary -> events.add("boundary:" + boundary.name()),
                RuntimeArtCoordinator.NONE,
                events);
    }

    private static LevelFrameContext context(
            TitleCardProvider titleCard,
            LevelEventProvider levelEvents,
            com.openggf.game.timing.HardwareTimingBoundaryObserver observer,
            RuntimeArtCoordinator coordinator,
            List<String> events) {
        GameModule module = mock(GameModule.class);
        when(module.getTitleCardProvider()).thenReturn(titleCard);
        return new LevelFrameContext(
                module,
                null,
                levelEvents,
                null,
                null,
                null,
                null,
                null,
                new HardwareTimingService(),
                observer,
                coordinator);
    }

    private static LevelManager recordingLevel(
            List<String> events,
            boolean pendingTitleCard) {
        LevelManager level = mock(LevelManager.class);
        ObjectManager objects = mock(ObjectManager.class);
        when(level.getObjectManager()).thenReturn(objects);
        when(level.hasPendingInLevelTitleCardHeldCounterDispatch())
                .thenReturn(pendingTitleCard);
        doAnswer(ignored -> {
            events.add("object-vblank");
            return null;
        }).when(objects).advanceVblaCounter();
        return level;
    }

    private static LevelEventProvider recordingLevelEvents(List<String> events) {
        LevelEventProvider levelEvents = mock(LevelEventProvider.class);
        doAnswer(ignored -> {
            events.add("fixed-objects");
            return null;
        }).when(levelEvents).updateFixedInLevelObjects();
        doAnswer(ignored -> {
            events.add("event-vblank");
            return null;
        }).when(levelEvents).advanceVblankOnlyState();
        return levelEvents;
    }

    private static PlcLifecycleService recordingPlc(List<String> events) {
        return new PlcLifecycleService() {
            @Override
            public void serviceVBlank(PlcLifecyclePhase phase) {
                events.add(phase == PlcLifecyclePhase.LAG
                        ? "phase:LAG"
                        : "plc-service:" + phase);
            }

            @Override
            public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
                return phase == PlcLifecyclePhase.LEVEL_TITLE_CARD;
            }

            @Override
            public void prepareAfterLoop(PlcLifecyclePhase phase) {
                events.add("plc-prepare:" + phase);
            }
        };
    }
}
