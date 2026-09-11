package com.openggf.game.sonic1.specialstage;

import com.openggf.level.Palette;

final class Sonic1SpecialStageSnapshot {
    final boolean initialized;
    final boolean finished;
    final boolean emeraldCollected;
    final boolean debugMode;
    final int startupHoldTicksRemaining;
    final boolean objInitPending;
    final int currentStage;
    final int ringsCollected;
    final int ssAngle;
    final int ssRotate;
    final int debugSavedAngle;
    final int debugSavedRotate;
    final long sonicPosX;
    final long sonicPosY;
    final int sonicVelX;
    final int sonicVelY;
    final int sonicInertia;
    final boolean sonicAirborne;
    final boolean sonicFacingLeft;
    final int cameraX;
    final int cameraY;
    final int ghostState;
    final int upDownCooldown;
    final int reverseCooldown;
    final int ringAnimFrame;
    final int ringAnimTimer;
    final int wallVramAnimFrame;
    final int wallVramAnimTimer;
    final int sonicAnimId;
    final int sonicAnimFrameIndex;
    final int sonicAnimFrameTimer;
    final int palSsTime;
    final int palSsNum;
    final int palSsIndex;
    final int ani2Frame;
    final int ani2Timer;
    final int ani3Frame;
    final int ani3Timer;
    final int sonicSpriteFrame;
    final boolean exitTriggered;
    final int exitPhase;
    final int exitTimer;
    final boolean exitFadeStarted;
    final int exitFadeTimer;
    final int heldButtons;
    final int pressedButtons;
    final boolean debugSpeedUp;
    final boolean debugSlowDown;
    final int bgAnimState;
    final boolean bgUsingPlane6;
    final int fgAnimPlaneIndex;
    final int fgYScroll;
    final int bgYScroll;
    final int bgExtraScrollX;
    final byte[] layout;
    final int[][] ssAnimBuffer;
    final int[] ssAnimGlassFinalBlock;
    final int[] bgSineBuffer;
    final int[] bgBandBuffer;
    final Palette[] ssPalettes;

    Sonic1SpecialStageSnapshot(
            boolean initialized,
            boolean finished,
            boolean emeraldCollected,
            boolean debugMode,
            int startupHoldTicksRemaining,
            boolean objInitPending,
            int currentStage,
            int ringsCollected,
            int ssAngle,
            int ssRotate,
            int debugSavedAngle,
            int debugSavedRotate,
            long sonicPosX,
            long sonicPosY,
            int sonicVelX,
            int sonicVelY,
            int sonicInertia,
            boolean sonicAirborne,
            boolean sonicFacingLeft,
            int cameraX,
            int cameraY,
            int ghostState,
            int upDownCooldown,
            int reverseCooldown,
            int ringAnimFrame,
            int ringAnimTimer,
            int wallVramAnimFrame,
            int wallVramAnimTimer,
            int sonicAnimId,
            int sonicAnimFrameIndex,
            int sonicAnimFrameTimer,
            int palSsTime,
            int palSsNum,
            int palSsIndex,
            int ani2Frame,
            int ani2Timer,
            int ani3Frame,
            int ani3Timer,
            int sonicSpriteFrame,
            boolean exitTriggered,
            int exitPhase,
            int exitTimer,
            boolean exitFadeStarted,
            int exitFadeTimer,
            int heldButtons,
            int pressedButtons,
            boolean debugSpeedUp,
            boolean debugSlowDown,
            int bgAnimState,
            boolean bgUsingPlane6,
            int fgAnimPlaneIndex,
            int fgYScroll,
            int bgYScroll,
            int bgExtraScrollX,
            byte[] layout,
            int[][] ssAnimBuffer,
            int[] ssAnimGlassFinalBlock,
            int[] bgSineBuffer,
            int[] bgBandBuffer,
            Palette[] ssPalettes) {
        this.initialized = initialized;
        this.finished = finished;
        this.emeraldCollected = emeraldCollected;
        this.debugMode = debugMode;
        this.startupHoldTicksRemaining = startupHoldTicksRemaining;
        this.objInitPending = objInitPending;
        this.currentStage = currentStage;
        this.ringsCollected = ringsCollected;
        this.ssAngle = ssAngle;
        this.ssRotate = ssRotate;
        this.debugSavedAngle = debugSavedAngle;
        this.debugSavedRotate = debugSavedRotate;
        this.sonicPosX = sonicPosX;
        this.sonicPosY = sonicPosY;
        this.sonicVelX = sonicVelX;
        this.sonicVelY = sonicVelY;
        this.sonicInertia = sonicInertia;
        this.sonicAirborne = sonicAirborne;
        this.sonicFacingLeft = sonicFacingLeft;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.ghostState = ghostState;
        this.upDownCooldown = upDownCooldown;
        this.reverseCooldown = reverseCooldown;
        this.ringAnimFrame = ringAnimFrame;
        this.ringAnimTimer = ringAnimTimer;
        this.wallVramAnimFrame = wallVramAnimFrame;
        this.wallVramAnimTimer = wallVramAnimTimer;
        this.sonicAnimId = sonicAnimId;
        this.sonicAnimFrameIndex = sonicAnimFrameIndex;
        this.sonicAnimFrameTimer = sonicAnimFrameTimer;
        this.palSsTime = palSsTime;
        this.palSsNum = palSsNum;
        this.palSsIndex = palSsIndex;
        this.ani2Frame = ani2Frame;
        this.ani2Timer = ani2Timer;
        this.ani3Frame = ani3Frame;
        this.ani3Timer = ani3Timer;
        this.sonicSpriteFrame = sonicSpriteFrame;
        this.exitTriggered = exitTriggered;
        this.exitPhase = exitPhase;
        this.exitTimer = exitTimer;
        this.exitFadeStarted = exitFadeStarted;
        this.exitFadeTimer = exitFadeTimer;
        this.heldButtons = heldButtons;
        this.pressedButtons = pressedButtons;
        this.debugSpeedUp = debugSpeedUp;
        this.debugSlowDown = debugSlowDown;
        this.bgAnimState = bgAnimState;
        this.bgUsingPlane6 = bgUsingPlane6;
        this.fgAnimPlaneIndex = fgAnimPlaneIndex;
        this.fgYScroll = fgYScroll;
        this.bgYScroll = bgYScroll;
        this.bgExtraScrollX = bgExtraScrollX;
        this.layout = cloneByteArray(layout);
        this.ssAnimBuffer = cloneIntMatrix(ssAnimBuffer);
        this.ssAnimGlassFinalBlock = cloneIntArray(ssAnimGlassFinalBlock);
        this.bgSineBuffer = cloneIntArray(bgSineBuffer);
        this.bgBandBuffer = cloneIntArray(bgBandBuffer);
        this.ssPalettes = clonePalettes(ssPalettes);
    }

    static byte[] cloneByteArray(byte[] source) {
        return source != null ? source.clone() : null;
    }

    static int[] cloneIntArray(int[] source) {
        return source != null ? source.clone() : null;
    }

    static int[][] cloneIntMatrix(int[][] source) {
        if (source == null) {
            return null;
        }
        int[][] copy = new int[source.length][];
        for (int i = 0; i < source.length; i++) {
            copy[i] = cloneIntArray(source[i]);
        }
        return copy;
    }

    static Palette[] clonePalettes(Palette[] source) {
        if (source == null) {
            return null;
        }
        Palette[] copy = new Palette[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] != null ? source[i].deepCopy() : null;
        }
        return copy;
    }
}
