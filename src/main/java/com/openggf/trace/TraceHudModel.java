package com.openggf.trace;

import com.openggf.trace.live.MismatchEntry;

import java.util.List;

/** Read-only values rendered by the common visual trace HUD. */
public interface TraceHudModel {
    int errorCount();

    int warningCount();

    int laggedFrames();

    int recentActionMask();

    int recentInputMask();

    boolean recentStartPressed();

    List<MismatchEntry> recentMismatches();

    boolean hasRecordingDesync();

    boolean isComplete();
}
