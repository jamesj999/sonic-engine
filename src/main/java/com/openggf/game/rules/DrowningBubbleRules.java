package com.openggf.game.rules;

public record DrowningBubbleRules(
        int initialDrowningCountdownFrameTimer,
        int mouthBubbleTimerBias,
        boolean breathingBubbleDefersFirstObjectPass,
        int mouthBubbleRiseVelocity) {
}
