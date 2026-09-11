package com.openggf.tests.trace.runs;

import com.openggf.audio.AudioRequestObserver;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.tools.audio.parity.s2.S2OracleRawStream;
import com.openggf.tools.audio.completerun.s2.S2ProductionRequestProjector;

import java.nio.file.Path;
import java.util.List;

/** Test-bytecode-only bridge to the package-private run-chain measurement harness. */
public final class S2RequestProjectionBk2TestBridge {
    private S2RequestProjectionBk2TestBridge() {
    }

    public record Capture(
            S2ProductionRequestProjector projector, List<Integer> requestRows,
            List<Integer> musicSubmissionRows,
            List<ProductionAudioRow> audioRows,
            List<PublicAudioRequest> publicAudioRequests,
            List<DriverUpdateTick> updateTicks) {
        public Capture {
            requestRows = List.copyOf(requestRows);
            musicSubmissionRows = List.copyOf(musicSubmissionRows);
            audioRows = List.copyOf(audioRows);
            publicAudioRequests = List.copyOf(publicAudioRequests);
            updateTicks = List.copyOf(updateTicks);
        }
    }

    /**
     * One completed engine driver update: the last driver snapshot the update
     * committed and every chip write since the previous update boundary. The
     * row is provenance only, exactly as the reference's frame field is.
     */
    public record DriverUpdateTick(
            int row, SmpsDriverSnapshot snapshot,
            List<S2OracleRawStream.ChipWrite> writes) {
        public DriverUpdateTick {
            writes = List.copyOf(writes);
        }
    }

    public record PublicAudioRequest(
            int row, AudioRequestObserver.RequestClass requestClass,
            int nativeId) {
    }

    /** Immutable observation emitted after one committed production row. */
    public record ProductionAudioRow(
            int row, SmpsDriverSnapshot snapshot,
            List<S2OracleRawStream.ChipWrite> writes,
            boolean completedDriverService) {
        public ProductionAudioRow {
            writes = List.copyOf(writes);
        }
    }

    public static Capture capture(Path rom, Path bk2)
            throws Exception {
        return new S2RequestProjectionBk2Capture().capture(rom, bk2);
    }

    /**
     * The same capture over another bounded interval of the committed run
     * chain, for a published window other than the original one.
     */
    public static Capture capture(Path rom, Path bk2, int firstRow,
            int exclusiveEnd) throws Exception {
        return new S2RequestProjectionBk2Capture()
                .capture(rom, bk2, firstRow, exclusiveEnd);
    }
}
