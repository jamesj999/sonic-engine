package com.openggf.game.rules;

public record RingRules(
        int ringFloorCheckMask,
        int ringFloorCheckCounterPhase,
        boolean ringFloorProbeRequiresRenderFlag,
        boolean lostRingBoundaryChecksOnlyOnProbeCadence,
        int lostRingRenderYMargin,
        int ringCollisionWidth,
        int ringCollisionHeight,
        boolean stageRingsUseObjectTouchCollection,
        boolean stageRingSweepUsesRawCameraWindow) {
}
