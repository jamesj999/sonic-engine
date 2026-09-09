package com.openggf.game.sonic2.audio;

import com.openggf.audio.rewind.AudioCommand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2SoundRequestService {

    @Test
    void servicesOneRomOrderedBoundaryAndPublishesNothingBeforeSeal() {
        Sonic2SoundRequestService service = new Sonic2SoundRequestService();
        List<Sonic2SoundRequestService.Event> observed = new ArrayList<>();
        service.addObserver(observed::add);

        service.submitMusic(0x82, music(0x82));
        service.submitMusic(0x94, music(0x94));
        service.submitSound(0xA0, sfx(0xA0));

        List<AudioCommand> commands = new ArrayList<>();
        var transaction = service.beginForwardBoundary();
        transaction.service(commands::add);

        assertTrue(observed.isEmpty(), "uncommitted events must remain invisible");
        assertEquals(List.of(music(0x82)), commands);
        transaction.rollback();
        assertTrue(observed.isEmpty(),
                "rollback must not publish staged request events");
    }

    @Test
    void rollbackRestoresMailboxAndPublishesNothing() {
        Sonic2SoundRequestService service = new Sonic2SoundRequestService();
        List<Sonic2SoundRequestService.Event> observed = new ArrayList<>();
        service.addObserver(observed::add);
        service.submitSound(0xB5, sfx(0xB5));
        Sonic2SoundRequestService.Snapshot before = service.snapshot();

        var failed = service.beginForwardBoundary();
        List<AudioCommand> discarded = new ArrayList<>();
        failed.service(discarded::add);
        failed.rollback();

        assertEquals(before, service.snapshot());
        assertTrue(observed.isEmpty());

    }

    @Test
    void preparedDiagnosticsRemainInvisibleRollbackKeepsOrdinalAndPublishIsOneShot() {
        Sonic2SoundRequestService service = new Sonic2SoundRequestService();
        List<Sonic2SoundRequestService.Event> observed = new ArrayList<>();
        service.addObserver(observed::add);
        service.submitSound(0x01, sfx(0x01));

        var rejected = service.beginForwardBoundary();
        rejected.service(ignored -> { });
        rejected.prepareCommit();
        assertTrue(observed.isEmpty());
        rejected.rollback();
        assertTrue(observed.isEmpty());

        var retry = service.beginForwardBoundary();
        retry.service(ignored -> { });
        retry.prepareCommit();
        var receipt = retry.commit();
        assertTrue(observed.isEmpty());
        retry.publishDiagnostics(receipt);
        List<Sonic2SoundRequestService.Event> once = List.copyOf(observed);
        assertEquals(1L, once.getFirst().ordinal(),
                "a rolled-back preparation must not consume an ordinal");
        retry.publishDiagnostics(receipt);
        assertEquals(once, observed,
                "sealed diagnostics must publish at most once");
    }

    @Test
    void nativeTrackStopClearsPriorityButRollbackRestoresIt() {
        Sonic2SoundRequestService service = new Sonic2SoundRequestService();
        var pipeline = new Sonic2SoundRequestPipeline<Sonic2SoundRequestService.PendingRequest>();
        pipeline.submitSound(0xBE, new Sonic2SoundRequestService.PendingRequest(0xBE, sfx(0xBE)));
        pipeline.bridge();
        pipeline.cycleQueue();
        pipeline.dispatchQueuedRequest();
        service.restore(new Sonic2SoundRequestService.Snapshot(pipeline.snapshot(), List.of()));
        var before = service.snapshot();
        var boundary = service.beginForwardBoundary();
        boundary.service(ignored -> { });
        assertEquals(0x70, service.snapshot().pipeline().sfxPriorityValue());
        boundary.onSfxTrackStop();
        assertEquals(0, service.snapshot().pipeline().sfxPriorityValue());
        boundary.rollback();
        assertEquals(before, service.snapshot());
    }

    private static AudioCommand.PlayMusic music(int id) {
        return new AudioCommand.PlayMusic(id, AudioCommand.MusicRoute.BASE_SMPS, false, null);
    }

    private static AudioCommand.PlaySfx sfx(int id) {
        return new AudioCommand.PlaySfx(id, null, AudioCommand.SfxRoute.BASE_SMPS_ID, 1.0f, null);
    }
}
