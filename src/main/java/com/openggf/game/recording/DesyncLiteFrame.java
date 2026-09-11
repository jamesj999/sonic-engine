package com.openggf.game.recording;

public record DesyncLiteFrame(
        int frame,
        int p1CentreX,
        int p1CentreY,
        int p1XSpeed,
        int p1YSpeed,
        int p1Inertia,
        int p1Status,
        int p1Animation,
        int cameraX,
        int cameraY,
        int timerFrames,
        int timerSeconds,
        int timerMinutes,
        int ringCount,
        int score
) {
}
