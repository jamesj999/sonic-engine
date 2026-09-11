package com.openggf.game.sonic3k.specialstage;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.snapshot.GameStateSnapshot;
import com.openggf.level.Palette;

import java.util.Arrays;

record Sonic3kSpecialStageSnapshot(
        int currentStage,
        boolean initialized,
        boolean finished,
        boolean emeraldCollected,
        boolean superEmeraldMode,
        int ringsCollected,
        int spheresLeft,
        int ringsLeft,
        int frameCounter,
        int heldButtons,
        int pressedButtons,
        int p2HeldButtons,
        int clearRoutine,
        int clearTimer,
        int emeraldTimer,
        int emeraldInteractIndex,
        long emeraldArtWorkOrdinal,
        boolean exitSpinStarted,
        int palFadeDelay,
        boolean musicSpedUp,
        int ringAnimTimer,
        int ringAnimFrame,
        int bannerPhase,
        int bannerTimer,
        int bannerOffset,
        int tailsAnimTimer,
        int tailsMappingFrame,
        int tailsTailsAnimTimer,
        int tailsTailsMappingFrame,
        int tailsJumping,
        long tailsJumpHeight,
        long tailsJumpVelocity,
        boolean tailsEnabled,
        PlayerCharacter playerCharacter,
        boolean spriteDebugMode,
        boolean useSkLayouts,
        boolean firstUpdateCall,
        int preBootFadeHoldFrames,
        int postBootFadeHoldFrames,
        GameStateSnapshot gameState,
        GridSnapshot grid,
        PlayerSnapshot player,
        TailsAiSnapshot tailsAi,
        CollisionQueueSnapshot collisionQueue,
        RingConverterSnapshot ringConverter,
        PerspectiveSnapshot perspective,
        BackgroundSnapshot background,
        HudSnapshot hud,
        BannerSnapshot banner,
        PaletteSnapshot palette) {

    static Sonic3kSpecialStageSnapshot uninitializedForTest() {
        return new Sonic3kSpecialStageSnapshot(
                0, false, false, false, false,
                0, 0, 0, 0,
                0, 0, 0,
                0, 0, 0, 0, 0, false, 0, false,
                0, 0, 0, 0, 0,
                0, 0, 0, 1, 0, 0L, 0L,
                true,
                PlayerCharacter.SONIC_AND_TAILS,
                false, false, false, 0, 0, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    static int[] cloneIntArray(int[] source) {
        return source != null ? source.clone() : null;
    }

    static void copyInto(int[] source, int[] target) {
        Arrays.fill(target, 0);
        if (source != null) {
            System.arraycopy(source, 0, target, 0, Math.min(source.length, target.length));
        }
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

    static void copyPalettesInto(Palette[] source, Palette[] target) {
        if (source == null || target == null) {
            return;
        }
        for (int i = 0; i < target.length && i < source.length; i++) {
            target[i] = source[i] != null ? source[i].deepCopy() : null;
        }
    }

    record GridSnapshot(int[] buffer) {
        GridSnapshot {
            buffer = Sonic3kSpecialStageSnapshot.cloneIntArray(buffer);
        }
    }

    record PlayerSnapshot(
            int xPos,
            int yPos,
            int angle,
            int velocity,
            int rate,
            int rateTimer,
            int turning,
            boolean turnLock,
            boolean advancing,
            boolean started,
            boolean bumperLock,
            int bumperInteractIndex,
            int jumping,
            long jumpHeight,
            long jumpVelocity,
            int animFrameTimer,
            int mappingFrame,
            int prevMappingFrame,
            boolean failed,
            boolean clearRoutineActive,
            int fadeTimer,
            boolean blueSphereMode,
            boolean rateJustIncreased) { }

    record TailsAiSnapshot(int[] posTableInput, int[] posTableJump,
                           int posTableIndex, int cpuIdleTimer, int lastP2Input) {
        TailsAiSnapshot {
            posTableInput = Sonic3kSpecialStageSnapshot.cloneIntArray(posTableInput);
            posTableJump = Sonic3kSpecialStageSnapshot.cloneIntArray(posTableJump);
        }
    }

    record CollisionQueueSnapshot(int[] types, int[] timers, int[] frames, int[] gridIndices) {
        CollisionQueueSnapshot {
            types = Sonic3kSpecialStageSnapshot.cloneIntArray(types);
            timers = Sonic3kSpecialStageSnapshot.cloneIntArray(timers);
            frames = Sonic3kSpecialStageSnapshot.cloneIntArray(frames);
            gridIndices = Sonic3kSpecialStageSnapshot.cloneIntArray(gridIndices);
        }
    }
    record RingConverterSnapshot(int seedBlueConverted) { }
    record PerspectiveSnapshot(int animFrame, int paletteFrame) { }
    record BackgroundSnapshot(int vScroll, int hScroll, int prevXPos, int prevYPos) { }
    record HudSnapshot(boolean sphereHudDirty, boolean ringHudDirty,
                       int displayedSphereCount, int displayedRingCount) { }
    record BannerSnapshot(Sonic3kSpecialStageBanner.Phase phase, int slideOffset,
                          int displayTimer, boolean triggeredAdvance, boolean showPerfect) { }
    record PaletteSnapshot(Palette[] palettes, byte[] stagePaletteData, boolean fadeActive) {
        PaletteSnapshot {
            palettes = Sonic3kSpecialStageSnapshot.clonePalettes(palettes);
            stagePaletteData = stagePaletteData != null ? stagePaletteData.clone() : null;
        }
    }
}
