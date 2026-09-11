package com.openggf.tools.audio.parity.s2;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.tests.trace.runs.S2RequestProjectionBk2TestBridge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestS2Bk2DriverOracleComparator {

    @Test
    void foldsEveryContiguousPhysicalRowThroughExactCompletionRow() {
        SmpsDriverSnapshot firstSnapshot = emptySnapshot(1);
        SmpsDriverSnapshot secondSnapshot = emptySnapshot(2);
        S2OracleRawStream.ChipWrite a = psg(0x91);
        S2OracleRawStream.ChipWrite b = psg(0x92);
        S2OracleRawStream.ChipWrite c = psg(0x93);
        S2OracleRawStream.ChipWrite d = psg(0x94);
        List<S2RequestProjectionBk2TestBridge.ProductionAudioRow> rows = List.of(
                row(10_195, emptySnapshot(0), a),
                row(10_196, firstSnapshot, b),
                row(10_197, emptySnapshot(0), c),
                row(10_198, secondSnapshot, d));
        List<S2AudioOracleComparator.ReferenceTick> reference = List.of(
                reference(0, 10_196), reference(1, 10_198));

        List<S2Bk2DriverOracleComparator.FoldedTick> folded =
                S2Bk2DriverOracleComparator.foldRows(reference, rows);

        assertEquals(2, folded.size());
        assertEquals(0, folded.get(0).ordinal());
        assertEquals(List.of(a, b), folded.get(0).writes());
        assertSame(firstSnapshot, folded.get(0).snapshot());
        assertEquals(1, folded.get(1).ordinal());
        assertEquals(List.of(c, d), folded.get(1).writes());
        assertSame(secondSnapshot, folded.get(1).snapshot());
    }

    @Test
    void refusesMissingDuplicateOrOutOfOrderPhysicalRows() {
        List<S2AudioOracleComparator.ReferenceTick> reference = List.of(
                reference(0, 10_196));
        SmpsDriverSnapshot snapshot = emptySnapshot(0);

        assertThrows(IllegalArgumentException.class, () ->
                S2Bk2DriverOracleComparator.foldRows(reference, List.of(
                        row(10_195, snapshot), row(10_197, snapshot))));
        assertThrows(IllegalArgumentException.class, () ->
                S2Bk2DriverOracleComparator.foldRows(reference, List.of(
                        row(10_195, snapshot), row(10_195, snapshot),
                        row(10_196, snapshot))));
        assertThrows(IllegalArgumentException.class, () ->
                S2Bk2DriverOracleComparator.foldRows(reference, List.of(
                        row(10_196, snapshot), row(10_195, snapshot))));
    }

    private static S2AudioOracleComparator.ReferenceTick reference(
            int ordinal, int row) {
        return new S2AudioOracleComparator.ReferenceTick(
                ordinal, row, new byte[S2OracleSchema.STATE_BYTES], List.of());
    }

    private static S2RequestProjectionBk2TestBridge.ProductionAudioRow row(
            int row, SmpsDriverSnapshot snapshot,
            S2OracleRawStream.ChipWrite... writes) {
        return new S2RequestProjectionBk2TestBridge.ProductionAudioRow(
                row, snapshot, List.of(writes), true);
    }

    private static S2OracleRawStream.ChipWrite psg(int value) {
        return new S2OracleRawStream.ChipWrite(false, 0, 0, value,
                S2OracleRawStream.ChipWrite.SERVICE_UPDATE_MUSIC);
    }

    private static SmpsDriverSnapshot emptySnapshot(int counter) {
        return new SmpsDriverSnapshot(
                SmpsSequencer.Region.NTSC,
                SmpsDriver.ReadMode.HYBRID,
                0, false, counter, 5, List.of(),
                new int[6], new int[4], List.of());
    }
}
