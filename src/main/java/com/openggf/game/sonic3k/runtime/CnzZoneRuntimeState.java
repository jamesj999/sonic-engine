package com.openggf.game.sonic3k.runtime;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;

import java.util.Objects;

public final class CnzZoneRuntimeState implements S3kZoneRuntimeState {
    private final int actIndex;
    private final PlayerCharacter playerCharacter;
    private final Sonic3kCNZEvents events;

    public CnzZoneRuntimeState(int actIndex, PlayerCharacter playerCharacter, Sonic3kCNZEvents events) {
        this.actIndex = actIndex;
        this.playerCharacter = Objects.requireNonNull(playerCharacter, "playerCharacter");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override public int zoneIndex() { return Sonic3kZoneIds.ZONE_CNZ; }
    @Override public int actIndex() { return actIndex; }
    @Override public PlayerCharacter playerCharacter() { return playerCharacter; }
    @Override public int getDynamicResizeRoutine() { return events.getDynamicResizeRoutine(); }
    @Override public boolean isActTransitionFlagActive() { return events.isEventsFg5(); }
    @Override public boolean advancesOscillationOnSeamlessTransition() { return true; }
    public boolean isBackedBy(Sonic3kCNZEvents candidate) { return events == candidate; }

    public Sonic3kCNZEvents.BossBackgroundMode bossBackgroundMode() {
        return events.getBossBackgroundMode();
    }

    public boolean bossBackgroundScrollActive() {
        int routine = events.getBackgroundRoutine();
        if (routine >= Sonic3kCNZEvents.BG_BOSS_START
                && routine <= Sonic3kCNZEvents.BG_DO_TRANSITION) {
            return true;
        }
        return switch (events.getBossBackgroundMode()) {
            case ACT1_MINIBOSS_PATH, ACT1_POST_BOSS -> true;
            case NORMAL, ACT2_KNUCKLES_TELEPORTER -> false;
        };
    }

    /**
     * ROM-facing FG routine mirror. CNZ stores this locally on the zone-events
     * instance rather than relying on {@code AbstractLevelEventManager}'s shared
     * protected counters.
     */
    public int foregroundRoutine() {
        return events.getForegroundRoutine();
    }

    /**
     * ROM-facing BG routine mirror used by the CNZ background-event path.
     */
    public int backgroundRoutine() {
        return events.getBackgroundRoutine();
    }

    /**
     * Published BG deform phase source matching the value later consumed by
     * {@code AnimateTiles_CNZ} through the ROM's event workspace.
     */
    public int deformPhaseBgX() {
        return events.getDeformPhaseBgX();
    }

    /**
     * Published background camera X copy corresponding to
     * {@code Camera_X_pos_BG_copy}.
     */
    public int publishedBgCameraX() {
        return events.getPublishedBgCameraX();
    }

    /**
     * Publishes the ROM-equivalent CNZ deform outputs without exposing the
     * backing event object to callers.
     *
     * <p>The scroll handler uses this to mirror the values that CNZ later
     * stores in {@code Events_bg+$10} and {@code Camera_X_pos_BG_copy}. The
     * adapter keeps the publication boundary narrow: callers can write the
     * deform outputs, but they still cannot reach the raw event instance.
     */
    public void publishDeformOutputs(int deformPhaseBgX, int bgCameraX) {
        events.setPublishedDeformInputs(deformPhaseBgX, bgCameraX);
    }

    public int bossScrollOffsetY() {
        return events.getBossScrollOffsetY();
    }

    public int bossScrollVelocityY() {
        return events.getBossScrollVelocityY();
    }

    public boolean isWallGrabSuppressed() {
        return events.isWallGrabSuppressed();
    }

    public int waterTargetY() {
        return events.getWaterTargetY();
    }

    /** Current CNZ timed screen-shake vertical offset (ROM Screen_shake_flag). */
    public int screenShakeOffsetY() {
        return events.getScreenShakeOffsetY();
    }
}
