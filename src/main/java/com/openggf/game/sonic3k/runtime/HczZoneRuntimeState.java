package com.openggf.game.sonic3k.runtime;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kHCZEvents;

import java.util.Objects;

public final class HczZoneRuntimeState implements S3kZoneRuntimeState {
    private final int actIndex;
    private final PlayerCharacter playerCharacter;
    private final Sonic3kHCZEvents events;

    public HczZoneRuntimeState(int actIndex, PlayerCharacter playerCharacter, Sonic3kHCZEvents events) {
        this.actIndex = actIndex;
        this.playerCharacter = Objects.requireNonNull(playerCharacter, "playerCharacter");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override public int zoneIndex() { return Sonic3kZoneIds.ZONE_HCZ; }
    @Override public int actIndex() { return actIndex; }
    @Override public PlayerCharacter playerCharacter() { return playerCharacter; }
    @Override public int getDynamicResizeRoutine() { return events.getDynamicResizeRoutine(); }
    @Override public boolean isActTransitionFlagActive() { return events.isEventsFg5(); }
    public boolean isBackedBy(Sonic3kHCZEvents candidate) { return events == candidate; }

    /**
     * Whether the HCZ2 wall-chase BG high-priority overlay is currently active.
     * Drives the staged {@code HczWallChaseBgOverlayEffect} render pass.
     */
    public boolean wallChaseBgOverlayActive() {
        return events.isWallChaseBgOverlayActive();
    }

    /**
     * Whether HCZ2 drives Plane B as a 512px VDP window over the BG layout.
     *
     * <p>ROM: every act-2 background state keeps the plane a windowed view —
     * {@code HCZ2BGE_WallMove} refreshes it with {@code DrawBGAsYouMove} at the
     * wall BG camera ({@code HCZ2_WallMove} writes
     * {@code Camera_X_pos_BG_copy = camX - $200 + wall offset}), and the
     * post-chase states rebuild it from source X={@code $000}
     * ({@code HCZ2BGE_NormalRefresh}/{@code HCZ2BGE_Normal}). The engine window
     * follows {@code getBgCameraX()} while the chase publishes it and anchors
     * at X=0 otherwise.
     */
    public boolean backgroundPlaneWindowActive() {
        return actIndex == 1;
    }

}
