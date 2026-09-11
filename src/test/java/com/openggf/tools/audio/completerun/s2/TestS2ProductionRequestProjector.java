package com.openggf.tools.audio.completerun.s2;

import com.openggf.audio.AudioManager;
import com.openggf.audio.NullAudioBackend;
import com.openggf.data.Rom;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.game.sonic2.audio.Sonic2SoundRequestPipeline;
import com.openggf.game.sonic2.audio.Sonic2SoundRequestService;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_2)
class TestS2ProductionRequestProjector {
    private Rom rom;

    @BeforeEach
    void setUp() {
        rom = new Rom();
        org.junit.jupiter.api.Assertions.assertTrue(rom.open(
                RomTestUtils.ensureSonic2RomAvailable().getAbsolutePath()));
    }

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        rom.close();
    }

    @Test
    void realProductionIngressProjectsTransferredRingAndResolvedDecision() {
        S2ProductionRequestProjector projector = new S2ProductionRequestProjector();
        Sonic2AudioProfile profile = new Sonic2AudioProfile(projector);
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        audio.setRom(rom);
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());

        audio.playSfx(0xB5);
        audio.presentFrame(PresentationMode.SILENT);
        assertEquals(List.of(), projector.requests(),
                "silent presentation must publish no request transitions");
        audio.presentFrame(PresentationMode.FORWARD);

        assertEquals(List.of(new CompleteRunAudioTrace.Request(
                2, CompleteRunAudioTrace.OwnerClass.SFX,
                "sfx.native.b5", 0xB5, "sound_queue", 0)),
                projector.requests());
        assertEquals(List.of(new CompleteRunAudioTrace.Decision(
                2, 0xCE, "sfx.native.ce", true, "accepted",
                0, 0x70, List.of(CompleteRunAudioTrace.HardwareRole.values()),
                null)), projector.decisions());
    }

    @Test
    void rejectedMismatchedRestorePublishesNothingAndRetainsMailbox() {
        S2ProductionRequestProjector projector = new S2ProductionRequestProjector();
        Sonic2AudioProfile profile = new Sonic2AudioProfile(projector);
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        audio.setRom(rom);
        audio.setAudioProfile(profile);
        audio.setSoundMap(profile.getSoundMap());
        audio.playSfx(0xB5);
        AudioLogicalSnapshot owned = audio.captureLogicalSnapshot();
        AudioLogicalSnapshot mismatched = new AudioLogicalSnapshot(
                owned.ringLeft(), owned.commandTimelineFrame(),
                owned.commandTimelineNextOrder(), owned.commandEntryCount(),
                owned.presentation(), owned.donorGameIds(), owned.donorBindings());

        audio.restoreLogicalSnapshot(mismatched);
        assertEquals(List.of(), projector.requests(),
                "failed restore must not publish request events");

        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(1, projector.requests().size(),
                "failed restore must retain the prior live mailbox");
    }

    @Test
    void priorityRejectionProjectsObservedPriorityWithoutRoleOwnership() {
        S2ProductionRequestProjector projector = new S2ProductionRequestProjector();
        projector.accept(new Sonic2SoundRequestService.Transfer(1,
                Sonic2SoundRequestPipeline.SourceSlot.SFX0, 0, 0xBF, false));
        projector.accept(new Sonic2SoundRequestService.Decision(2,
                Sonic2SoundRequestPipeline.QueueSlot.QUEUE0, 0xBF,
                Sonic2SoundRequestService.DecisionReason.ACCEPTED_PRIORITY,
                0, 0x7F));
        projector.accept(new Sonic2SoundRequestService.Dispatch(3, 0xBF, 0xBF,
                Sonic2SoundRequestPipeline.DispatchKind.NOT_YET_DISPATCHED));
        projector.accept(new Sonic2SoundRequestService.Transfer(4,
                Sonic2SoundRequestPipeline.SourceSlot.SFX0, 0, 0xA1, false));
        projector.accept(new Sonic2SoundRequestService.Decision(5,
                Sonic2SoundRequestPipeline.QueueSlot.QUEUE0, 0xA1,
                Sonic2SoundRequestService.DecisionReason.REJECTED_PRIORITY,
                0x7F, 0x7F));

        CompleteRunAudioTrace.Decision rejected = projector.decisions().getLast();
        assertEquals(false, rejected.accepted());
        assertEquals("rejected_priority", rejected.reason());
        assertEquals(0x7F, rejected.priorityBefore());
        assertEquals(0x7F, rejected.priorityAfter());
        assertEquals(null, rejected.roleDecisions(),
                "production request events do not claim hardware ownership");
    }

}
