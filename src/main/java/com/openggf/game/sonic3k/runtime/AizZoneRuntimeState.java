package com.openggf.game.sonic3k.runtime;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.FireCurtainRenderState;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;

import java.util.Objects;

public final class AizZoneRuntimeState implements S3kZoneRuntimeState {
    private final int actIndex;
    private final PlayerCharacter playerCharacter;
    private final Sonic3kAIZEvents events;

    public AizZoneRuntimeState(int actIndex, PlayerCharacter playerCharacter, Sonic3kAIZEvents events) {
        this.actIndex = actIndex;
        this.playerCharacter = Objects.requireNonNull(playerCharacter, "playerCharacter");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override public int zoneIndex() { return Sonic3kZoneIds.ZONE_AIZ; }
    @Override public int actIndex() { return actIndex; }
    @Override public PlayerCharacter playerCharacter() { return playerCharacter; }
    @Override public int getDynamicResizeRoutine() { return events.getDynamicResizeRoutine(); }
    @Override public boolean isActTransitionFlagActive() { return events.isEventsFg5(); }
    @Override
    public boolean rightWallDeepProbePreservesPenetration() {
        // Player_WalkVertR applies its deep-probe recovery only when the
        // combined zone/act word is zero: AIZ1 (sonic3k.asm:18884-18941).
        return actIndex == 0;
    }
    public boolean isBackedBy(Sonic3kAIZEvents candidate) { return events == candidate; }

    public boolean isBossFlagActive() { return events.isBossFlag(); }
    public boolean isPostFireHazeActive() { return events.isPostFireHazeActive(); }
    public boolean isBattleshipForestFrontPhaseActive() { return events.isBattleshipForestFrontPhaseActive(); }
    public boolean isFireTransitionScrollActive() { return events.isFireTransitionScrollActive(); }
    public int getFireTransitionBgX() { return events.getFireTransitionBgX(); }
    public int getFireTransitionBgY() { return events.getFireTransitionBgY(); }
    public FireCurtainRenderState getFireCurtainRenderState(int screenHeight) {
        return events.getFireCurtainRenderState(screenHeight);
    }
    public boolean isBattleshipAutoScrollActive() { return events.isBattleshipAutoScrollActive(); }
    public boolean isBattleshipForestLoopActive() { return events.isBattleshipForestLoopActive(); }
    public int getLevelRepeatOffset() { return events.getLevelRepeatOffset(); }
    public int getBattleshipBgYOffset() { return events.getBattleshipBgYOffset(); }
    public int getBattleshipSmoothScrollX() { return events.getBattleshipSmoothScrollX(); }
    public int getScreenShakeOffsetY() { return events.getScreenShakeOffsetY(); }
}
