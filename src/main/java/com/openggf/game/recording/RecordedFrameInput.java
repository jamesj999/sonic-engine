package com.openggf.game.recording;

public record RecordedFrameInput(
        int frame,
        int p1InputMask,
        int p1ActionMask,
        boolean p1Start,
        int p2InputMask,
        int p2ActionMask,
        boolean p2Start
) {}
