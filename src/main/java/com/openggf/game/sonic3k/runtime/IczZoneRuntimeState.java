package com.openggf.game.sonic3k.runtime;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kICZEvents;

import java.util.Objects;

/**
 * Runtime-shared ICZ state.
 *
 * <p>ICZ events own the indoor palette gate and background routine, while
 * AnimateTiles_ICZ consumes scroll-derived BG camera words later in the frame.
 * Keeping those reads behind this adapter prevents palette and animation code
 * from reaching back into the level-event manager.
 */
public final class IczZoneRuntimeState implements S3kZoneRuntimeState {
    private final int actIndex;
    private final PlayerCharacter playerCharacter;
    private final Sonic3kICZEvents events;

    public IczZoneRuntimeState(int actIndex, PlayerCharacter playerCharacter, Sonic3kICZEvents events) {
        this.actIndex = actIndex;
        this.playerCharacter = Objects.requireNonNull(playerCharacter, "playerCharacter");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override public int zoneIndex() { return Sonic3kZoneIds.ZONE_ICZ; }
    @Override public int actIndex() { return actIndex; }
    @Override public PlayerCharacter playerCharacter() { return playerCharacter; }
    @Override public int getDynamicResizeRoutine() { return 0; }
    @Override public boolean isActTransitionFlagActive() { return events.isEventsFg5(); }

    public boolean isBackedBy(Sonic3kICZEvents candidate) {
        return events == candidate;
    }

    public boolean isIndoorPaletteCyclingActive() {
        return events.isIndoorPaletteCyclingActive();
    }

    public int icz1BackgroundRoutine() {
        return events.getIcz1BackgroundRoutine();
    }

    public int icz1BigSnowOffset() {
        return events.getIcz1BigSnowOffset();
    }

    /** Current ICZ timed screen-shake vertical offset (ROM {@code Screen_shake_flag}). */
    public int screenShakeOffsetY() {
        return events.getScreenShakeOffsetY();
    }

    public boolean isAct2TransitionRequested() {
        return events.isAct2TransitionRequested();
    }

    public int iczBgCameraX(int cameraX, int cameraY) {
        if (actIndex == 0) {
            int x = cameraX & 0xFFFF;
            return x < 0x3940 ? 0x1880 : asrWordValue(cameraX, 1) - 0x1D80;
        }

        if (!isIczAct2Indoor(cameraX, cameraY)) {
            return 0;
        }
        int d0 = ((short) cameraX) << 16;
        d0 >>= 1;
        int d1 = d0 >> 3;
        for (int i = 0; i < 4; i++) {
            d0 -= d1;
        }
        return (short) (d0 >> 16);
    }

    public int iczEventsBg10(int cameraX, int cameraY) {
        if (actIndex == 0) {
            int x = cameraX & 0xFFFF;
            if (x < 0x3940) {
                return 0;
            }
            return asrWordValue(iczBgCameraX(cameraX, cameraY), 1);
        }

        if (!isIczAct2Indoor(cameraX, cameraY)) {
            return 0;
        }
        int d0 = ((short) cameraX) << 16;
        d0 >>= 1;
        int d1 = d0 >> 3;
        for (int i = 0; i < 5; i++) {
            d0 -= d1;
        }
        return (short) (d0 >> 16);
    }

    public int iczAct1BgCameraY(int cameraX, int cameraY) {
        int x = cameraX & 0xFFFF;
        return x < 0x3940 ? asrWordValue(cameraY, 7) : asrWordValue(cameraY, 1);
    }

    private static boolean isIczAct2Indoor(int cameraX, int cameraY) {
        int x = cameraX & 0xFFFF;
        int y = cameraY & 0xFFFF;
        if (x >= 0x1900 && x < 0x1B80) {
            return true;
        }
        return x >= 0x1000 && x < 0x3600 && y >= 0x720;
    }

    private static int asrWordValue(int value, int shift) {
        return (short) value >> shift;
    }
}
