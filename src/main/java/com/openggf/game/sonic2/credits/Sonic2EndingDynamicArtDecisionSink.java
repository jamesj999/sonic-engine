package com.openggf.game.sonic2.credits;

import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.level.render.SpriteDplcFrame;

import java.util.Objects;

/**
 * Production decision boundary for Sonic 2 ending player art.
 *
 * <p>The cutscene state machine emits semantic frame selections here. The
 * session-owned dynamic-art lifecycle decides whether the selection produces
 * an accepted DMA batch; presentation only consumes the prepared art bank.
 */
@FunctionalInterface
interface Sonic2EndingDynamicArtDecisionSink {

    Sonic2EndingDynamicArtDecisionSink NONE = decision -> { };

    void observe(Decision decision);

    record Decision(
            DynamicArtLifecycleService.DecisionKind kind,
            String owner,
            int mappingFrame,
            SpriteDplcFrame dplcFrame) {
        public Decision {
            kind = Objects.requireNonNull(kind, "kind");
            owner = Objects.requireNonNull(owner, "owner");
            if (mappingFrame < 0) {
                throw new IllegalArgumentException("mappingFrame must be nonnegative");
            }
        }
    }
}
