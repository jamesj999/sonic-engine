package com.openggf.tools.audio.completerun;

import com.openggf.debug.playback.Bk2Movie;
import com.openggf.game.session.EngineContext;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CompleteRunFixture;
import java.util.Objects;

/** Closed adapter from authenticated production rows to an atomic capture writer. */
final class CompleteRunAudioOpenGgfCapture {
    private CompleteRunAudioOpenGgfCapture() {
    }

    @FunctionalInterface
    interface RowSource {
        void drive(ProductionBk2AudioRunner.RowConsumer consumer) throws Exception;
    }

    /** Runs the fixed Task 5 production row owner; callers provide only output projection. */
    static void run(EngineContext context, Bk2Movie movie,
            CompleteRunFixture fixture, CompleteRunAudioCaptureStore.Writer writer,
            CompleteRunAudioFrameProjection projection) throws Exception {
        try {
            Objects.requireNonNull(movie, "BK2 movie");
            Objects.requireNonNull(fixture, "fixture");
            if (movie.getFrameCount() != fixture.bk2RowCount()
                    || fixture.exclusiveEnd() != fixture.bk2RowCount()) {
                throw new IllegalArgumentException(
                        "BK2 rows do not match the complete-run fixture terminal");
            }
        } catch (Exception | Error failure) {
            abortWriter(writer, failure);
            throw failure;
        }
        reduce(fixture, writer, projection,
                consumer -> ProductionBk2AudioRunner.run(context, movie, consumer));
    }

    static void reduce(CompleteRunFixture fixture,
            CompleteRunAudioCaptureStore.Writer writer,
            CompleteRunAudioFrameProjection projection,
            RowSource rows) throws Exception {
        try {
            Objects.requireNonNull(fixture, "fixture");
            Objects.requireNonNull(writer, "capture writer");
            Objects.requireNonNull(projection, "frame projection");
            Objects.requireNonNull(rows, "authenticated row source");
        } catch (Exception | Error failure) {
            abortWriter(writer, failure);
            throw failure;
        }
        final com.openggf.audio.rewind.AudioLogicalSnapshot[] terminal = { null };
        try (CompleteRunAudioCaptureReducer reducer =
                     new CompleteRunAudioCaptureReducer(fixture, writer, projection)) {
            rows.drive(row -> {
                reducer.beforeFrame(row.preRow());
                reducer.acceptFrame(row.observation());
                terminal[0] = row.observation().logicalSnapshot();
            });
            reducer.finish(Objects.requireNonNull(terminal[0],
                    "authenticated row source produced no terminal boundary"));
        }
    }

    private static void abortWriter(CompleteRunAudioCaptureStore.Writer writer,
            Throwable primary) {
        try {
            Objects.requireNonNull(writer, "capture writer").abort();
        } catch (Exception | Error cleanup) {
            primary.addSuppressed(cleanup);
        }
    }
}
