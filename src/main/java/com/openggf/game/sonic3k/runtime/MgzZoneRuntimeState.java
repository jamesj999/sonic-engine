package com.openggf.game.sonic3k.runtime;

import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kMGZEvents;
import com.openggf.game.sonic3k.scroll.SwScrlMgz;
import com.openggf.level.ParallaxManager;
import com.openggf.level.scroll.ZoneScrollHandler;

import java.util.Objects;

public final class MgzZoneRuntimeState implements S3kZoneRuntimeState {
    private final int actIndex;
    private final PlayerCharacter playerCharacter;
    private final Sonic3kMGZEvents events;
    private int pendingScreenShakeOffset;
    /** ROM {@code Screen_shake_flag} = -1 (continuous). */
    private boolean continuousScreenShakeRequested;
    private int currentScreenShakeOffset;
    private boolean bossBgScrollOffsetPublished;

    public MgzZoneRuntimeState(int actIndex, PlayerCharacter playerCharacter, Sonic3kMGZEvents events) {
        this.actIndex = actIndex;
        this.playerCharacter = Objects.requireNonNull(playerCharacter, "playerCharacter");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override public int zoneIndex() { return Sonic3kZoneIds.ZONE_MGZ; }
    @Override public int actIndex() { return actIndex; }
    @Override public PlayerCharacter playerCharacter() { return playerCharacter; }
    @Override public int getDynamicResizeRoutine() { return events.getDynamicResizeRoutine(); }
    @Override public boolean isActTransitionFlagActive() { return events.isEventsFg5Raw(); }
    public boolean isBackedBy(Sonic3kMGZEvents candidate) { return events == candidate; }

    public int bgRiseRoutine() {
        return events.getBgRiseRoutine();
    }

    public int bgRiseOffset() {
        return events.getBgRiseOffset();
    }

    /**
     * Writes through to the events state (the canonical source for
     * {@code Events_bg+$00} / {@code Events_bg+$02}). Mirrors
     * {@link #publishBossBgScrollOffset(int)} for the BG-rise state machine
     * so callers outside the events class can drive it deterministically.
     */
    public void publishBgRiseState(int routine, int offset) {
        events.setBgRiseRoutine(routine);
        events.setBgRiseOffset(offset);
    }

    /**
     * Pushes the current BG-rise state to the registered MGZ scroll handler so
     * its cached {@code bgRiseRoutine}/{@code bgRiseOffset} and BG camera
     * derivations reflect the events transition immediately. Bridges the
     * events package (forbidden from depending on {@link SwScrlMgz}) and the
     * scroll handler so collision probes between event tick and the next
     * parallax draw see post-transition state.
     */
    public void syncBgRiseToScrollHandler() {
        SwScrlMgz handler = resolveMgzScrollHandler();
        if (handler != null) {
            handler.setBgRiseState(bgRiseRoutine(), bgRiseOffset());
        }
    }

    private static SwScrlMgz resolveMgzScrollHandler() {
        ParallaxManager parallax = GameServices.parallaxOrNull();
        if (parallax == null) {
            return null;
        }
        ZoneScrollHandler handler = parallax.getHandler(Sonic3kZoneIds.ZONE_MGZ);
        return (handler instanceof SwScrlMgz mgz) ? mgz : null;
    }

    public int bossBgScrollOffset() {
        return events.getBossBgScrollOffset();
    }

    public boolean hasBossBgScrollOffset() {
        return bossBgScrollOffsetPublished;
    }

    public void publishBossBgScrollOffset(int offset) {
        events.setBossBgScrollOffset(offset & 0xFFFF);
        bossBgScrollOffsetPublished = true;
    }

    public void clearBossBgScrollOffset() {
        bossBgScrollOffsetPublished = false;
    }

    /**
     * ROM {@code st (Screen_shake_flag).w} — objects only raise the continuous
     * shake flag (Tunnelbot: docs/skdisasm/sonic3k.asm:184784, :184886,
     * :184907). The offset itself belongs to {@code ShakeScreen_Setup}
     * (sonic3k.asm:104188-104210), which the zone's background event runs once
     * per frame; see {@link com.openggf.game.sonic3k.scroll.SwScrlMgz}.
     */
    public void requestContinuousScreenShake() {
        continuousScreenShakeRequested = true;
    }

    /** Consumed once per frame by the zone scroll handler's ShakeScreen_Setup model. */
    public boolean consumeContinuousScreenShakeRequest() {
        boolean requested = continuousScreenShakeRequested;
        continuousScreenShakeRequested = false;
        return requested;
    }

    public void requestScreenShakeOffset(int offset) {
        if (offset > pendingScreenShakeOffset) {
            pendingScreenShakeOffset = offset;
        }
    }

    public int consumeScreenShakeOffset() {
        currentScreenShakeOffset = pendingScreenShakeOffset;
        pendingScreenShakeOffset = 0;
        return currentScreenShakeOffset;
    }

    public int currentScreenShakeOffset() {
        return currentScreenShakeOffset;
    }

    public void clearScreenShakeOffset() {
        pendingScreenShakeOffset = 0;
        currentScreenShakeOffset = 0;
        continuousScreenShakeRequested = false;
    }
}
