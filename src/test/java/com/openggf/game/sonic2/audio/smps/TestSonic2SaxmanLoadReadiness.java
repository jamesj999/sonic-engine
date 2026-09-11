package com.openggf.game.sonic2.audio.smps;

import com.openggf.audio.smps.LoadedSmpsMusic;
import com.openggf.audio.smps.SmpsLoadReadiness;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.data.Rom;
import com.openggf.tests.RomTestUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class TestSonic2SaxmanLoadReadiness {
    @Test
    void emeraldHillUsesItsRomBytesAndShippedZ80RoutineCost() {
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        Assumptions.assumeTrue(romFile != null, "Sonic 2 ROM unavailable");
        Rom rom = new Rom();
        assertTrue(rom.open(romFile.getAbsolutePath()));

        LoadedSmpsMusic loaded = new Sonic2SmpsLoader(rom)
                .loadMusicWithReadiness(0x81);

        assertNotNull(loaded);
        assertEquals(0x772, loaded.data().getData().length);
        assertEquals(0x528, loaded.readiness().compressedByteCount());
        assertEquals(1016, loaded.readiness().workUnitCount());
        assertEquals(363_255L, loaded.readiness().minimumTStates(
                new SmpsLoadReadiness.Context(
                        SmpsSequencer.Region.NTSC, false)));
        assertFalse(loaded.readiness().immediate());
    }

    @Test
    void readinessNaturallyCrossesAfterSixCompleteNtscBudgets() {
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        Assumptions.assumeTrue(romFile != null, "Sonic 2 ROM unavailable");
        Rom rom = new Rom();
        assertTrue(rom.open(romFile.getAbsolutePath()));
        SmpsLoadReadiness.Work work = new Sonic2SmpsLoader(rom)
                .loadMusicWithReadiness(0x81).readiness().begin(
                        new SmpsLoadReadiness.Context(
                                SmpsSequencer.Region.NTSC, false));

        for (int presentation = 0; presentation < 6; presentation++) {
            assertFalse(work.advanceOnePresentation());
        }
        assertTrue(work.advanceOnePresentation());
        assertTrue(work.ready());
        assertTrue(work.advanceOnePresentation());
    }

    @Test
    void uncompressedMusicAndGenericLoaderRemainImmediate() {
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        Assumptions.assumeTrue(romFile != null, "Sonic 2 ROM unavailable");
        Rom rom = new Rom();
        assertTrue(rom.open(romFile.getAbsolutePath()));

        LoadedSmpsMusic loaded = new Sonic2SmpsLoader(rom)
                .loadMusicWithReadiness(0x98);

        assertTrue(loaded.readiness().immediate());
        assertTrue(loaded.readiness().begin(new SmpsLoadReadiness.Context(
                SmpsSequencer.Region.NTSC, false)).ready());
    }

    @Test
    void differentCompressedWorkProducesDifferentSourceCosts() {
        Sonic2SaxmanLoadReadiness literal =
                new Sonic2SaxmanLoadReadiness(
                        new byte[] {2, 0, 1, 0x55}, 6, 3, false, false);
        Sonic2SaxmanLoadReadiness match =
                new Sonic2SaxmanLoadReadiness(
                        new byte[] {3, 0, 0, 0, 0}, 6, 3, false, false);
        SmpsLoadReadiness.Context context = new SmpsLoadReadiness.Context(
                SmpsSequencer.Region.NTSC, false);

        assertNotEquals(literal.minimumTStates(context),
                match.minimumTStates(context));
        assertNotEquals(literal.provenance(), match.provenance());
    }

    @Test
    void palUsesItsRegionalZ80BudgetWithoutAFrameCount() {
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        Assumptions.assumeTrue(romFile != null, "Sonic 2 ROM unavailable");
        Rom rom = new Rom();
        assertTrue(rom.open(romFile.getAbsolutePath()));
        SmpsLoadReadiness.Work work = new Sonic2SmpsLoader(rom)
                .loadMusicWithReadiness(0x81).readiness().begin(
                        new SmpsLoadReadiness.Context(
                                SmpsSequencer.Region.PAL, false));

        for (int presentation = 0; presentation < 5; presentation++) {
            assertFalse(work.advanceOnePresentation());
        }
        assertTrue(work.advanceOnePresentation());
    }

    @Test
    void speedShoesBranchIsSampledIntoTheLoadCost() {
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        Assumptions.assumeTrue(romFile != null, "Sonic 2 ROM unavailable");
        Rom rom = new Rom();
        assertTrue(rom.open(romFile.getAbsolutePath()));
        SmpsLoadReadiness readiness = new Sonic2SmpsLoader(rom)
                .loadMusicWithReadiness(0x81).readiness();

        long normal = readiness.minimumTStates(new SmpsLoadReadiness.Context(
                SmpsSequencer.Region.NTSC, false));
        long spedUp = readiness.minimumTStates(new SmpsLoadReadiness.Context(
                SmpsSequencer.Region.NTSC, true));

        assertEquals(normal + 8, spedUp);
    }

    @Test
    void sourceBankChangesCostAndCannotShareProvenance() {
        byte[] bytes = {2, 0, 1, 0x55};
        Sonic2SaxmanLoadReadiness bank1 =
                new Sonic2SaxmanLoadReadiness(bytes, 6, 3, false, false);
        Sonic2SaxmanLoadReadiness bank2 =
                new Sonic2SaxmanLoadReadiness(bytes, 6, 3, true, false);
        SmpsLoadReadiness.Context context = new SmpsLoadReadiness.Context(
                SmpsSequencer.Region.NTSC, false);

        assertEquals(bank1.minimumTStates(context) + 5,
                bank2.minimumTStates(context));
        assertNotEquals(bank1.provenance(), bank2.provenance());
    }

    @Test
    void palBranchIsCostedOnlyWhenSongPolicyEnablesIt() {
        byte[] bytes = {2, 0, 1, 0x55};
        Sonic2SaxmanLoadReadiness enabled =
                new Sonic2SaxmanLoadReadiness(bytes, 6, 3, true, false);
        Sonic2SaxmanLoadReadiness disabled =
                new Sonic2SaxmanLoadReadiness(bytes, 6, 3, true, true);
        SmpsLoadReadiness.Context ntsc = new SmpsLoadReadiness.Context(
                SmpsSequencer.Region.NTSC, false);
        SmpsLoadReadiness.Context pal = new SmpsLoadReadiness.Context(
                SmpsSequencer.Region.PAL, false);

        assertEquals(enabled.minimumTStates(ntsc) + 28,
                enabled.minimumTStates(pal));
        assertEquals(disabled.minimumTStates(ntsc),
                disabled.minimumTStates(pal));
        assertNotEquals(enabled.provenance(), disabled.provenance());

        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        Assumptions.assumeTrue(romFile != null, "Sonic 2 ROM unavailable");
        Rom rom = new Rom();
        assertTrue(rom.open(romFile.getAbsolutePath()));
        assertEquals(363_283L, new Sonic2SmpsLoader(rom)
                .loadMusicWithReadiness(0x81).readiness()
                .minimumTStates(pal));
    }
}
