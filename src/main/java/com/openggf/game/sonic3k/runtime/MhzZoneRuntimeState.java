package com.openggf.game.sonic3k.runtime;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kMHZEvents;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Runtime-shared MHZ state.
 *
 * <p>The ROM stores MHZ deform outputs in the events workspace
 * ({@code Camera_X_pos_BG_copy}, {@code Events_bg+$10}, {@code Events_bg+$12})
 * and {@code AnimateTiles_MHZ} reads them later in the frame. This adapter keeps
 * that contract explicit without coupling animation code to the scroll handler.
 */
public final class MhzZoneRuntimeState implements S3kZoneRuntimeState {
    // ROM level setup runs Process_Sprites then Animate_Tiles before LevelLoop
    // (sonic3k.asm:7853-7855). MHZ caps read Anim_Counters+$F during
    // Process_Sprites (82199), and AnimateTiles_MHZ advances it by 2 later
    // in the same frame (54901-54908, 7894-7906).
    private static final int INITIAL_MUSHROOM_CAP_POSITION_COUNTER = 2;

    private final int actIndex;
    private final PlayerCharacter playerCharacter;
    private final Sonic3kMHZEvents events;
    private int publishedBgCameraX;
    private int middleBgCameraX;
    private int nearBgCameraX;
    private int pollenParticleCount;
    private int pollenLeafPatternCounter;
    private int mushroomCapPositionCounter = INITIAL_MUSHROOM_CAP_POSITION_COUNTER;
    private int endBossArenaTallSupportX;
    private final int[] endBossArenaSpikeX = new int[6];

    public MhzZoneRuntimeState(int actIndex, PlayerCharacter playerCharacter) {
        this(actIndex, playerCharacter, null);
    }

    public MhzZoneRuntimeState(int actIndex, PlayerCharacter playerCharacter, Sonic3kMHZEvents events) {
        this.actIndex = actIndex;
        this.playerCharacter = Objects.requireNonNull(playerCharacter, "playerCharacter");
        this.events = events;
    }

    @Override public int zoneIndex() { return Sonic3kZoneIds.ZONE_MHZ; }
    @Override public int actIndex() { return actIndex; }
    @Override public PlayerCharacter playerCharacter() { return playerCharacter; }
    @Override public int getDynamicResizeRoutine() { return events == null ? 0 : events.getDynamicResizeRoutine(); }
    @Override public boolean isActTransitionFlagActive() {
        return events != null && events.isActTransitionFlagActive();
    }

    public Sonic3kMHZEvents.SeasonPaletteMode seasonPaletteMode() {
        return events == null ? Sonic3kMHZEvents.SeasonPaletteMode.GREEN : events.getSeasonPaletteMode();
    }

    public boolean isSeasonFlagSet() {
        return events != null && events.isSeasonFlagSet();
    }

    public void clearSeasonFlag() {
        if (events != null) {
            events.clearSeasonFlag();
        }
    }

    public boolean isAutumnTriggerFlagSet() {
        return events != null && events.isAutumnTriggerFlagSet();
    }

    public boolean isLeafBlowerCutsceneFlagSet() {
        return events != null && events.isLeafBlowerCutsceneFlagSet();
    }

    public void setLeafBlowerCutsceneFlag(boolean active) {
        if (events != null) {
            events.setLeafBlowerCutsceneFlag(active);
        }
    }

    public boolean isShipSequenceActive() {
        return events != null && events.isShipHIntActive();
    }

    public int shipHIntCounter() {
        return events == null ? 0 : events.getShipHIntCounter();
    }

    public void applyShipControllerFrame(int motionAccumulator, int swingOffset) {
        if (events != null) {
            events.applyShipControllerFrame(motionAccumulator, swingOffset);
        }
    }

    public int shipSecondaryBgCameraXFixed() {
        return events == null ? 0 : events.getShipSecondaryBgCameraXFixed();
    }

    public int shipEffectiveBgY() {
        return events == null ? 0 : events.getShipEffectiveBgY();
    }

    public boolean isShipScrollLockSet() {
        return events != null && events.isShipScrollLockSet();
    }

    public boolean isShipControllerSignalFlagSet() {
        return events != null && events.isShipControllerSignalFlagSet();
    }

    public void signalShipTransition() {
        if (events != null) {
            events.setShipTransitionFlag(true);
        }
    }

    public void signalEndBossWalkoffPrep() {
        if (events != null) {
            events.signalEndBossWalkoffPrep();
        }
    }

    public int endBossWalkoffPrepEventFlag() {
        return events == null ? 0 : events.getEndBossWalkoffPrepEventFlag();
    }

    public int levelRepeatOffset() {
        return events == null ? 0 : events.getLevelRepeatOffset();
    }

    public int shipHScrollCameraCopy() {
        return events == null ? 0 : events.getShipHScrollCameraCopy();
    }

    public int shipPrimaryHScroll() {
        return events == null ? 0 : events.getShipPrimaryHScroll();
    }

    public boolean isEndBossArenaBackgroundActive() {
        return events != null && events.isEndBossArenaBackgroundActive();
    }

    public boolean isBossAreaBackgroundDeformActive() {
        return events != null && events.isBossAreaBackgroundDeformActive();
    }

    public boolean isEndBossArenaForegroundRefreshActive() {
        return events != null && events.isEndBossArenaForegroundRefreshActive();
    }

    public int screenShakeOffset() {
        return events == null ? 0 : events.getScreenShakeOffset();
    }

    public int shipPropellerOneX() {
        return events == null ? 0 : events.getShipPropellerOneX();
    }

    public int shipPropellerTwoX() {
        return events == null ? 0 : events.getShipPropellerTwoX();
    }

    public int shipPropellerY() {
        return events == null ? 0 : events.getShipPropellerY();
    }

    public void publishEndBossArenaHelperXPositions(int tallSupportX, int[] spikeX) {
        endBossArenaTallSupportX = tallSupportX & 0xFFFF;
        Arrays.fill(endBossArenaSpikeX, 0);
        if (spikeX != null) {
            int count = Math.min(spikeX.length, endBossArenaSpikeX.length);
            for (int i = 0; i < count; i++) {
                endBossArenaSpikeX[i] = spikeX[i] & 0xFFFF;
            }
        }
    }

    public int endBossArenaTallSupportX() {
        return endBossArenaTallSupportX;
    }

    public int endBossArenaSpikeX(int spikeIndex) {
        return spikeIndex >= 0 && spikeIndex < endBossArenaSpikeX.length
                ? endBossArenaSpikeX[spikeIndex]
                : 0;
    }

    public boolean isBackedBy(Sonic3kMHZEvents candidate) {
        return events == candidate;
    }

    public void publishDeformOutputs(int bgCameraX, int middleBgCameraX, int nearBgCameraX) {
        this.publishedBgCameraX = bgCameraX & 0xFFFF;
        this.middleBgCameraX = middleBgCameraX & 0xFFFF;
        this.nearBgCameraX = nearBgCameraX & 0xFFFF;
    }

    public int publishedBgCameraX() {
        return publishedBgCameraX;
    }

    public int middleBgCameraX() {
        return middleBgCameraX;
    }

    public int nearBgCameraX() {
        return nearBgCameraX;
    }

    public int backgroundLayer1Phase() {
        return (nearBgCameraX - publishedBgCameraX) & 0x1F;
    }

    public int backgroundLayer2Phase() {
        return (middleBgCameraX - publishedBgCameraX) & 0x3F;
    }

    public boolean tryReservePollenParticle() {
        if (pollenParticleCount >= 0x10) {
            return false;
        }
        pollenParticleCount++;
        return true;
    }

    public void reservePollenParticleAfterSpawnerGate() {
        pollenParticleCount++;
    }

    public void releasePollenParticle() {
        if (pollenParticleCount > 0) {
            pollenParticleCount--;
        }
    }

    public int pollenParticleCount() {
        return pollenParticleCount;
    }

    public int pollenLeafPatternCounter() {
        return pollenLeafPatternCounter;
    }

    public boolean nextPollenParticleUsesBigLeaf() {
        int counter = pollenLeafPatternCounter;
        pollenLeafPatternCounter = (pollenLeafPatternCounter + 1) & 0xFF;
        int index = counter & 7;
        return index != 2 && index != 6 && index != 7;
    }

    public int mushroomCapPositionCounter() {
        return mushroomCapPositionCounter;
    }

    public void publishMushroomCapPositionCounter(int counter) {
        mushroomCapPositionCounter = counter & 0xFF;
    }

    public void advanceMushroomCapPositionCounter() {
        int next = (mushroomCapPositionCounter + 2) & 0xFF;
        mushroomCapPositionCounter = next < 0x58 ? next : 0;
    }

    @Override
    public byte[] captureBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 13);
        buffer.putInt(publishedBgCameraX);
        buffer.putInt(middleBgCameraX);
        buffer.putInt(nearBgCameraX);
        buffer.putInt(pollenParticleCount);
        buffer.putInt(pollenLeafPatternCounter);
        buffer.putInt(mushroomCapPositionCounter);
        buffer.putInt(endBossArenaTallSupportX);
        for (int spikeX : endBossArenaSpikeX) {
            buffer.putInt(spikeX);
        }
        return buffer.array();
    }

    @Override
    public void restoreBytes(byte[] bytes) {
        if (bytes == null || bytes.length < Integer.BYTES * 3) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        publishedBgCameraX = buffer.getInt();
        middleBgCameraX = buffer.getInt();
        nearBgCameraX = buffer.getInt();
        if (bytes.length >= Integer.BYTES * 5) {
            pollenParticleCount = buffer.getInt();
            pollenLeafPatternCounter = buffer.getInt();
        }
        if (bytes.length >= Integer.BYTES * 6) {
            mushroomCapPositionCounter = buffer.getInt();
        }
        if (bytes.length >= Integer.BYTES * 7) {
            endBossArenaTallSupportX = buffer.getInt();
        }
        if (bytes.length >= Integer.BYTES * 13) {
            for (int i = 0; i < endBossArenaSpikeX.length; i++) {
                endBossArenaSpikeX[i] = buffer.getInt();
            }
        }
    }
}
