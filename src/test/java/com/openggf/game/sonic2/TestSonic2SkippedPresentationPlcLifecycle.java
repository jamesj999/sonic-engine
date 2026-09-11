package com.openggf.game.sonic2;

import com.openggf.data.Rom;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.level.resources.NemesisPlcPatternCounts;
import com.openggf.level.resources.PlcParser;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2SkippedPresentationPlcLifecycle {
    private Rom rom;

    @BeforeEach
    void setUp() {
        rom = TestEnvironment.currentRom();
    }

    @Test
    void releaseDrainsInitialQueueThenServicesHeaderSecondaryAcrossTheTitleCardLeaveLoop()
            throws Exception {
        Sonic2PlcService service = new Sonic2PlcService(rom);
        service.transact(
                Sonic2PlcService.clearOperation(),
                Sonic2PlcService.appendOperation(Sonic2Constants.PLC_EHZ1),
                Sonic2PlcService.appendOperation(Sonic2Constants.PLC_STD2));

        Sonic2LevelInitProfile.completeInitialPresentationPlcs(rom, service, 0);

        // EHZ2 is small enough that the 25-frame title-card leave loop
        // (docs/s2disasm/s2.asm:5060-5066) retires it entirely, so
        // Level_MainLoop (s2.asm:5082-5087) is entered with an empty Plc_Buffer.
        assertEquals(List.of(), service.capture().queuedEntries(),
                "the title-card leave loop fully services the header secondary for EHZ");
    }

    @Test
    void oversizedHeaderSecondarySurvivesTheTitleCardLeaveLoopPartiallyServiced()
            throws Exception {
        Sonic2PlcService service = new Sonic2PlcService(rom);
        service.transact(
                Sonic2PlcService.clearOperation(),
                Sonic2PlcService.appendOperation(Sonic2Constants.PLC_ARZ1),
                Sonic2PlcService.appendOperation(Sonic2Constants.PLC_STD2));

        Sonic2LevelInitProfile.completeInitialPresentationPlcs(
                rom, service, Sonic2Constants.ZONE_ARZ);

        // The leave loop is a fixed 25 frames of RunPLC_RAM, not a drain to
        // empty: ARZ's larger PLC2 is still partially queued at Level_MainLoop.
        List<NemesisPlcQueueSnapshot.Entry> full = expectedDescriptors(Sonic2Constants.PLC_ARZ2);
        List<NemesisPlcQueueSnapshot.Entry> remaining = service.capture().queuedEntries();
        assertEquals(2, remaining.size(),
                "25 title-card leave frames retire all but the last two ARZ PLC2 descriptors");
        assertEquals(
                full.subList(full.size() - 2, full.size()).stream()
                        .map(e -> List.of(e.sourceAddress(), e.destinationTile())).toList(),
                remaining.stream()
                        .map(e -> List.of(e.sourceAddress(), e.destinationTile())).toList(),
                "ARZ keeps the unserviced tail of its header secondary queued, in order");
    }

    private List<NemesisPlcQueueSnapshot.Entry> expectedDescriptors(int id) throws Exception {
        var definition = PlcParser.parse(rom, Sonic2Constants.ART_LOAD_CUES_ADDR, id);
        var counts = NemesisPlcPatternCounts.derive(rom, definition);
        List<NemesisPlcQueueSnapshot.Entry> result = new ArrayList<>();
        for (int index = 0; index < definition.entries().size(); index++) {
            var entry = definition.entries().get(index);
            result.add(new NemesisPlcQueueSnapshot.Entry(
                    entry.romAddr(), entry.tileIndex(), counts.get(index), counts.get(index)));
        }
        return result;
    }
}
