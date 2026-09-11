package com.openggf.tools.audio.completerun;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Baseline;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Frame;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Lifecycle;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Record;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Terminal;
import java.io.IOException;
import java.util.Objects;

/** Append-only output boundary for a complete-run capture. */
public interface CompleteRunAudioRecordSink extends AutoCloseable {
    void append(Record record) throws IOException;

    default void baseline(Baseline baseline) throws IOException {
        append(Objects.requireNonNull(baseline, "baseline"));
    }

    default void frame(Frame frame) throws IOException {
        append(Objects.requireNonNull(frame, "frame"));
    }

    default void lifecycle(Lifecycle lifecycle) throws IOException {
        append(Objects.requireNonNull(lifecycle, "lifecycle"));
    }

    default void terminal(Terminal terminal) throws IOException {
        append(Objects.requireNonNull(terminal, "terminal"));
    }

    @Override
    void close() throws IOException;
}
