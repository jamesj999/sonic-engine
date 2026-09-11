package com.openggf.game.sonic2.resources;

import com.openggf.data.Rom;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.level.resources.NemesisPlcPatternCounts;
import com.openggf.level.resources.NemesisPlcServiceQueue;
import com.openggf.level.resources.PlcParser;
import com.openggf.level.resources.PlcParser.PlcDefinition;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2PlcService {
    private Rom rom;

    @BeforeEach
    void setUp() {
        rom = TestEnvironment.currentRom();
    }

    @Test
    void parsesSonic2TableAndMapsAppendReplaceAndClear() throws IOException {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        Sonic2PlcService service = new Sonic2PlcService(rom, queue);
        PlcDefinition first = firstNonEmptyPlc();
        PlcDefinition replacement = nextNonEmptyPlc(first.plcId());

        service.append(first.plcId());
        assertEquals(entriesFor(first), queue.capture().queuedEntries());

        service.replaceQueued(replacement.plcId());
        assertEquals(entriesFor(replacement), queue.capture().queuedEntries());

        service.clearQueued();
        assertFalse(service.isBusy());
    }

    @Test
    void requiresPreparationAndHonorsThreeAndSixPatternBudgets() throws IOException {
        PlcDefinition definition = firstPlcWhoseFirstEntryHasAtLeast(7);
        int totalPatterns = NemesisPlcPatternCounts.derive(rom, definition).getFirst();

        NemesisPlcServiceQueue levelQueue = new NemesisPlcServiceQueue();
        Sonic2PlcService levelService = new Sonic2PlcService(rom, levelQueue);
        levelService.append(definition.plcId());
        levelService.serviceLevelVBlank();
        assertEquals(totalPatterns, levelQueue.capture().queuedEntries().getFirst().remainingPatterns(),
                "VBlank service must not implicitly prepare a FIFO head");
        levelService.prepare();
        levelService.serviceLevelVBlank();
        assertEquals(totalPatterns - 3, levelQueue.capture().activeEntry().remainingPatterns());

        NemesisPlcServiceQueue normalQueue = new NemesisPlcServiceQueue();
        Sonic2PlcService normalService = new Sonic2PlcService(rom, normalQueue);
        normalService.append(definition.plcId());
        normalService.prepare();
        normalService.serviceNormalVBlank();
        assertEquals(totalPatterns - 6, normalQueue.capture().activeEntry().remainingPatterns());
    }

    @Test
    void exposesWholeBufferBusyAndRejectsQueuedMutationWhileDecoderIsActive()
            throws IOException {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        Sonic2PlcService service = new Sonic2PlcService(rom, queue);
        PlcDefinition first = firstNonEmptyPlc();
        PlcDefinition replacement = nextNonEmptyPlc(first.plcId());

        service.append(first.plcId());
        assertTrue(service.isBusy());
        service.prepare();
        assertThrows(IllegalStateException.class, service::clearQueued);
        assertThrows(IllegalStateException.class, () -> service.replaceQueued(replacement.plcId()));
    }

    @Test
    void refusesTheUnsafeSixteenthRetailQueueSlot() throws IOException {
        Sonic2PlcService service = new Sonic2PlcService(rom, new NemesisPlcServiceQueue());
        int oneEntryPlcId = firstSingleEntryPlc().plcId();

        for (int slot = 0; slot < 15; slot++) {
            service.append(oneEntryPlcId);
        }
        assertTrue(service.isBusy());

        assertThrows(IllegalStateException.class, () -> service.append(oneEntryPlcId),
                "Task 1 pins slot 16 as a retail-retained sentinel, not usable capacity");
    }

    @Test
    void rejectedTwoCueBatchLeavesTheEntireLogicalQueueUnchanged() throws IOException {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        Sonic2PlcService service = new Sonic2PlcService(rom, queue);
        int oneEntryPlcId = firstSingleEntryPlc().plcId();
        for (int slot = 0; slot < 14; slot++) {
            service.append(oneEntryPlcId);
        }
        var before = queue.capture();

        assertThrows(IllegalStateException.class,
                () -> service.prepareAppendBatch(oneEntryPlcId, oneEntryPlcId));

        assertEquals(before, queue.capture(),
                "a rejected second cue must not publish the first cue from the batch");
    }

    @Test
    void rejectsOutOfRangeIdsBeforeMutatingQueuedState() throws IOException {
        NemesisPlcServiceQueue queue = new NemesisPlcServiceQueue();
        Sonic2PlcService service = new Sonic2PlcService(rom, queue);
        PlcDefinition definition = firstNonEmptyPlc();
        service.append(definition.plcId());
        var before = queue.capture();

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> service.append(-1));
        assertTrue(negative.getMessage().contains("Sonic 2"));
        assertTrue(negative.getMessage().contains("-1"));
        assertEquals(before, queue.capture());

        IllegalArgumentException upperBound = assertThrows(IllegalArgumentException.class,
                () -> service.replaceQueued(Sonic2Constants.ART_LOAD_CUES_ENTRY_COUNT));
        assertTrue(upperBound.getMessage().contains("Sonic 2"));
        assertTrue(upperBound.getMessage().contains(
                String.valueOf(Sonic2Constants.ART_LOAD_CUES_ENTRY_COUNT)));
        assertEquals(before, queue.capture());
    }

    @Test
    void moduleRegistersOneRomBoundServiceForTheActiveGame() {
        Sonic2GameModule module = new Sonic2GameModule();
        module.createGame(rom);

        Sonic2PlcService service = assertInstanceOf(Sonic2PlcService.class,
                module.getGameService(Sonic2PlcService.class));
        assertSame(service, module.getGameService(Sonic2PlcService.class));
        assertSame(service, module.getGameService(PlcLifecycleService.class));
    }

    @Test
    void diagnosticsReserveEmptyServiceObservations() throws IOException {
        PlcDefinition definition = firstPlcWhoseFirstEntryHasAtLeast(7);
        Sonic2PlcService service = new Sonic2PlcService(rom);
        service.append(definition.plcId());
        service.prepare();
        service.serviceVBlank(PlcLifecyclePhase.LEVEL_TITLE_CARD);

        QueueDiagnosticSnapshot snapshot =
                service.captureQueueDiagnostics().getFirst();
        assertEquals(QueueDiagnosticSnapshot.Kind.S2_NEMESIS_PLC,
                snapshot.kind());
        assertTrue(snapshot.serviceObservations().isEmpty());
    }

    private PlcDefinition firstNonEmptyPlc() throws IOException {
        return plcDefinitions().stream()
                .filter(definition -> !definition.entries().isEmpty())
                .findFirst()
                .orElseThrow();
    }

    private PlcDefinition nextNonEmptyPlc(int excludedId) throws IOException {
        return plcDefinitions().stream()
                .filter(definition -> definition.plcId() != excludedId)
                .filter(definition -> !definition.entries().isEmpty())
                .findFirst()
                .orElseThrow();
    }

    private PlcDefinition firstSingleEntryPlc() throws IOException {
        return plcDefinitions().stream()
                .filter(definition -> definition.entries().size() == 1)
                .findFirst()
                .orElseThrow();
    }

    private PlcDefinition firstPlcWhoseFirstEntryHasAtLeast(int minimumPatterns)
            throws IOException {
        for (PlcDefinition definition : plcDefinitions()) {
            if (!definition.entries().isEmpty()
                    && NemesisPlcPatternCounts.derive(rom, definition).getFirst() >= minimumPatterns) {
                return definition;
            }
        }
        throw new AssertionError("Sonic 2 ROM must contain a PLC entry large enough for budget testing");
    }

    private List<PlcDefinition> plcDefinitions() throws IOException {
        return java.util.stream.IntStream.range(0, Sonic2Constants.ART_LOAD_CUES_ENTRY_COUNT)
                .mapToObj(this::parse)
                .toList();
    }

    private PlcDefinition parse(int plcId) {
        try {
            return PlcParser.parse(rom, Sonic2Constants.ART_LOAD_CUES_ADDR, plcId);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private List<com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot.Entry> entriesFor(
            PlcDefinition definition) throws IOException {
        List<Integer> counts = NemesisPlcPatternCounts.derive(rom, definition);
        return java.util.stream.IntStream.range(0, definition.entries().size())
                .mapToObj(index -> new com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot.Entry(
                        definition.entries().get(index).romAddr(),
                        definition.entries().get(index).tileIndex(),
                        counts.get(index), counts.get(index)))
                .toList();
    }
}
