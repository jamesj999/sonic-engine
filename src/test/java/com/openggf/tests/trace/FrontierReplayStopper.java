package com.openggf.tests.trace;

import com.openggf.trace.FrameComparison;
import com.openggf.trace.TraceVerificationScope;

final class FrontierReplayStopper {
    private final boolean enabled;
    private final int contextRadius;
    private final TraceVerificationScope verificationScope;
    private int firstErrorFrame = -1;
    private boolean stopped;

    private FrontierReplayStopper(boolean enabled, int contextRadius,
            TraceVerificationScope verificationScope) {
        this.enabled = enabled;
        this.contextRadius = Math.max(0, contextRadius);
        this.verificationScope = verificationScope;
    }

    static FrontierReplayStopper fromSystemProperties() {
        int contextRadius = TraceReplayConsole.contextRadius();
        TraceVerificationScope scope = TraceVerificationScope.fromSystemProperty();
        return Boolean.getBoolean("trace.frontierOnly")
                ? enabled(contextRadius, scope)
                : disabled(contextRadius, scope);
    }

    static FrontierReplayStopper enabled(int contextRadius) {
        return enabled(contextRadius, TraceVerificationScope.ALL);
    }

    static FrontierReplayStopper enabled(int contextRadius,
            TraceVerificationScope scope) {
        return new FrontierReplayStopper(true, contextRadius, scope);
    }

    static FrontierReplayStopper disabled(int contextRadius) {
        return disabled(contextRadius, TraceVerificationScope.ALL);
    }

    static FrontierReplayStopper disabled(int contextRadius,
            TraceVerificationScope scope) {
        return new FrontierReplayStopper(false, contextRadius, scope);
    }

    void observe(FrameComparison comparison) {
        if (!enabled || comparison == null || firstErrorFrame >= 0
                || !comparison.hasError(verificationScope)) {
            return;
        }
        firstErrorFrame = comparison.frame();
    }

    boolean shouldStopAfterFrame(int frame) {
        stopped = stopped || enabled && firstErrorFrame >= 0
                && frame >= firstErrorFrame + contextRadius;
        return stopped;
    }

    boolean stoppedEarly() {
        return stopped;
    }

}
