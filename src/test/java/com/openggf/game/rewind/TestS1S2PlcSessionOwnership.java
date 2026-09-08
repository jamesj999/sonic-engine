package com.openggf.game.rewind;

import com.openggf.data.Rom;
import com.openggf.game.GameModule;
import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.GameplaySessionFactory;
import com.openggf.game.session.WorldSession;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.sonic2.timing.Sonic2LevelMusicScheduler;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures native PLC progress belongs to a particular S1/S2 game session. */
@ExtendWith(SingletonResetExtension.class)
class TestS1S2PlcSessionOwnership {

    @Test
    void sequentialSonic1AndSonic2SessionsDoNotSharePlcProgressOrAdapterIdentity() {
        Sonic1GameModule sonic1 = new Sonic1GameModule();
        sonic1.createGame(new Rom());
        Sonic1PlcService s1Service = sonic1.getGameService(Sonic1PlcService.class);
        s1Service.restore(nonEmptySnapshot());
        assertTrue(s1Service.isBusy());
        assertSingleAdapter(sonic1, s1Service);

        Sonic2GameModule sonic2 = new Sonic2GameModule();
        sonic2.createGame(new Rom());
        Sonic2PlcService s2Service = sonic2.getGameService(Sonic2PlcService.class);
        assertFalse(s2Service.isBusy(), "the next session must start with an empty PLC FIFO");
        assertEquals(2, sonic2.rewindAdapters().size());
        assertSame(s2Service, sonic2.rewindAdapters().get(0));
        var scheduler = sonic2.rewindAdapters().get(1);
        assertTrue(scheduler
                instanceof Sonic2LevelMusicScheduler);
        assertSame(scheduler, sonic2.rewindAdapters().get(1),
                "the session must retain one scheduler identity");

        s2Service.restore(nonEmptySnapshot());
        assertEquals(nonEmptySnapshot(), s2Service.capture());
        assertEquals(nonEmptySnapshot(), s1Service.capture(),
                "restoring the second session must not mutate the closed first session");
    }

    @Test
    void sonic1RegistersRomBoundFacadeAfterAttachAndRestoresThroughRealRegistry() {
        Sonic1GameModule module = new Sonic1GameModule();
        GameplayModeContext context = attachedBeforeGameCreation(module);
        assertFalse(context.getRewindRegistry().capture().containsKey(Sonic1PlcService.REWIND_KEY));

        module.createGame(new Rom());
        context.registerLevelAdapters(context.getLevelManager());
        Sonic1PlcService service = module.getGameService(Sonic1PlcService.class);
        assertRegistryReplaysAndResetsMissingAdapter(context, service, Sonic1PlcService.REWIND_KEY,
                service::prepare, service::serviceLevelVBlank, service::clearQueued);
    }

    @Test
    void sonic2RegistersRomBoundFacadeAfterAttachAndRestoresThroughRealRegistry() {
        Sonic2GameModule module = new Sonic2GameModule();
        GameplayModeContext context = attachedBeforeGameCreation(module);
        assertFalse(context.getRewindRegistry().capture().containsKey(Sonic2PlcService.REWIND_KEY));
        assertTrue(context.getRewindRegistry().capture()
                .containsKey(Sonic2LevelMusicScheduler.REWIND_KEY));

        module.createGame(new Rom());
        context.registerLevelAdapters(context.getLevelManager());
        Sonic2PlcService service = module.getGameService(Sonic2PlcService.class);
        assertRegistryReplaysAndResetsMissingAdapter(context, service, Sonic2PlcService.REWIND_KEY,
                service::prepare, service::serviceLevelVBlank, service::clearQueued);
    }

    private static NemesisPlcQueueSnapshot nonEmptySnapshot() {
        return new NemesisPlcQueueSnapshot(null, List.of(
                new NemesisPlcQueueSnapshot.Entry(0x100, 0x20, 3, 3)));
    }

    private static void assertSingleAdapter(GameModule module, RewindSnapshottable<?> service) {
        assertTrue(module.rewindAdapters().stream().anyMatch(adapter -> adapter == service),
                "the module must retain the ROM-bound PLC adapter alongside other S1 rewind state");
    }

    private static GameplayModeContext attachedBeforeGameCreation(GameModule module) {
        GameplayModeContext context = new GameplayModeContext(new WorldSession(module));
        GameplaySessionFactory.attachManagers(context, EngineServices.current());
        return context;
    }

    private static void assertRegistryReplaysAndResetsMissingAdapter(
            GameplayModeContext context,
            RewindSnapshottable<NemesisPlcQueueSnapshot> service,
            String rewindKey,
            Runnable prepare,
            Runnable serviceFrame,
            Runnable rejectedMutation) {
        service.restore(new NemesisPlcQueueSnapshot(
                new NemesisPlcQueueSnapshot.Entry(0x100, 0x20, 6, 6),
                List.of(new NemesisPlcQueueSnapshot.Entry(0x200, 0x40, 3, 3))));
        CompositeSnapshot snapshot = context.getRewindRegistry().capture();
        assertTrue(snapshot.containsKey(rewindKey));

        assertThrows(IllegalStateException.class, rejectedMutation::run,
                "active queue mutation must remain rejected before a registry restore");
        List<Boolean> original = advanceBusySequence(service, prepare, serviceFrame);

        context.getRewindRegistry().restore(snapshot);
        assertThrows(IllegalStateException.class, rejectedMutation::run,
                "active queue mutation must remain rejected after a registry restore");
        assertEquals(original, advanceBusySequence(service, prepare, serviceFrame),
                "registry restore must preserve the exact consumer release frame");

        context.getRewindRegistry().restore(legacySnapshotWithout(snapshot, rewindKey));
        assertFalse(service.capture().activeEntry() != null || !service.capture().queuedEntries().isEmpty(),
                "a pre-PLC snapshot must reset the newly registered queue instead of retaining live work");
    }

    private static List<Boolean> advanceBusySequence(
            RewindSnapshottable<NemesisPlcQueueSnapshot> service,
            Runnable prepare,
            Runnable serviceFrame) {
        List<Boolean> result = new ArrayList<>();
        result.add(isBusy(service.capture()));
        for (int frame = 0; frame < 8 && isBusy(service.capture()); frame++) {
            prepare.run();
            serviceFrame.run();
            result.add(isBusy(service.capture()));
        }
        return List.copyOf(result);
    }

    private static CompositeSnapshot legacySnapshotWithout(CompositeSnapshot snapshot, String key) {
        LinkedHashMap<String, Object> entries = new LinkedHashMap<>(snapshot.entries());
        entries.remove(key);
        return new CompositeSnapshot(entries);
    }

    private static boolean isBusy(NemesisPlcQueueSnapshot snapshot) {
        return snapshot.activeEntry() != null || !snapshot.queuedEntries().isEmpty();
    }
}
