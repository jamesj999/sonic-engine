package com.openggf.game.animation;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.AnimatedTileChannelSnapshot;
import com.openggf.game.rewind.snapshot.AnimatedTileChannelSnapshot.Layout;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime-owned coordinator for animated tile channels.
 *
 * <p>The graph holds the active channel set for the current zone/runtime and
 * remembers the last resolved phase for each channel id. That lets channels use
 * phase-based caching without keeping their own mutable frame state.
 */
public final class AnimatedTileChannelGraph implements RewindSnapshottable<AnimatedTileChannelSnapshot> {

    private List<AnimatedTileChannel> channels = List.of();
    private Layout snapshotLayout = Layout.fromKeys(List.of());
    private int[] installedPhases = new int[0];
    private boolean[] installedPhasePresent = new boolean[0];
    private int recordedInstalledPhaseCount;

    /**
     * Replaces the current channel set and clears any cached per-channel phase.
     */
    public void install(List<AnimatedTileChannel> channels) {
        List<AnimatedTileChannel> installed = List.copyOf(Objects.requireNonNull(channels, "channels"));
        Map<String, Integer> installedIndexes = new HashMap<>();
        for (int index = 0; index < installed.size(); index++) {
            AnimatedTileChannel channel = installed.get(index);
            if (installedIndexes.put(channel.channelId(), index) != null) {
                throw new IllegalArgumentException("Duplicate animated tile channelId: " + channel.channelId());
            }
        }
        Layout installedLayout = Layout.fromKeys(installed.stream()
                .map(AnimatedTileChannel::channelId).toList());
        this.channels = installed;
        snapshotLayout = installedLayout;
        installedPhases = new int[installed.size()];
        installedPhasePresent = new boolean[installed.size()];
        recordedInstalledPhaseCount = 0;
    }

    /** Returns the currently installed channel definitions. */
    public List<AnimatedTileChannel> channels() {
        return channels;
    }

    /** Removes all channels and any cached phase history. */
    public void clear() {
        channels = List.of();
        snapshotLayout = Layout.fromKeys(List.of());
        installedPhases = new int[0];
        installedPhasePresent = new boolean[0];
        recordedInstalledPhaseCount = 0;
    }

    /**
     * Resolves and applies each active channel for the current frame.
     *
     * <p>Each channel receives its own {@link ChannelContext}, derived from the
     * shared frame context plus the concrete channel being evaluated.
     */
    public void update(ChannelContext baseContext) {
        Objects.requireNonNull(baseContext, "baseContext");
        List<AnimatedTileChannel> activeChannels = channels;
        int[] activePhases = installedPhases;
        boolean[] activePhasePresent = installedPhasePresent;
        for (int channelIndex = 0; channelIndex < activeChannels.size(); channelIndex++) {
            AnimatedTileChannel channel = activeChannels.get(channelIndex);
            if (!channel.guard().allows()) {
                continue;
            }
            ChannelContext channelContext = new ChannelContext(
                    this,
                    channel,
                    baseContext.level(),
                    baseContext.runtimeState(),
                    baseContext.zoneIndex(),
                    baseContext.actIndex(),
                    baseContext.frameCounter());
            int phase = channel.phaseSource().resolve(channelContext);
            if (installedPhases == activePhases && installedPhasePresent == activePhasePresent) {
                if (channel.cachePolicy() == AnimatedTileCachePolicy.ON_PHASE_CHANGE
                        && activePhasePresent[channelIndex]
                        && activePhases[channelIndex] == phase) {
                    continue;
                }
                if (!activePhasePresent[channelIndex]) {
                    activePhasePresent[channelIndex] = true;
                    recordedInstalledPhaseCount++;
                }
                activePhases[channelIndex] = phase;
            } else {
                if (channel.cachePolicy() == AnimatedTileCachePolicy.ON_PHASE_CHANGE
                        && hasRecordedPhase(channel.channelId())
                        && getLastPhase(channel.channelId()) == phase) {
                    continue;
                }
                recordPhase(channel.channelId(), phase);
            }
            channel.applyStrategy().apply(channelContext);
        }
    }

    private boolean hasRecordedPhase(String channelId) {
        int channelIndex = snapshotLayout.indexOf(channelId);
        return channelIndex >= 0 && installedPhasePresent[channelIndex];
    }

    /**
     * Records the last resolved phase for a channel. Package-private for testing.
     */
    void recordPhase(String channelId, int phase) {
        int channelIndex = snapshotLayout.indexOf(channelId);
        if (channelIndex < 0) {
            snapshotLayout = snapshotLayout.append(channelId);
            channelIndex = snapshotLayout.size() - 1;
            installedPhases = Arrays.copyOf(installedPhases, snapshotLayout.size());
            installedPhasePresent = Arrays.copyOf(installedPhasePresent, snapshotLayout.size());
        }
        if (!installedPhasePresent[channelIndex]) {
            installedPhasePresent[channelIndex] = true;
            recordedInstalledPhaseCount++;
        }
        installedPhases[channelIndex] = phase;
    }

    /**
     * Returns the last resolved phase for a channel, or -1 if not recorded.
     * Package-private for testing.
     */
    int getLastPhase(String channelId) {
        int channelIndex = snapshotLayout.indexOf(channelId);
        return channelIndex >= 0 && installedPhasePresent[channelIndex]
                ? installedPhases[channelIndex]
                : -1;
    }

    /** Returns the number of installed and diagnostic channels with recorded phases. */
    public int recordedPhaseCount() {
        return recordedInstalledPhaseCount;
    }

    // ── RewindSnapshottable ───────────────────────────────────────────────

    @Override
    public String key() {
        return "animated-tile-channels";
    }

    @Override
    public AnimatedTileChannelSnapshot capture() {
        return AnimatedTileChannelSnapshot.compact(
                snapshotLayout, installedPhases, installedPhasePresent, recordedInstalledPhaseCount);
    }

    @Override
    public void restore(AnimatedTileChannelSnapshot s) {
        if (s == null) {
            resetPhaseStateToInstalledLayout();
            throw new NullPointerException("snapshot");
        }
        int compactPresentCount = s.copyCompactState(
                snapshotLayout, installedPhases, installedPhasePresent);
        if (compactPresentCount >= 0) {
            recordedInstalledPhaseCount = compactPresentCount;
            return;
        }
        resetPhaseStateToInstalledLayout();
        for (Map.Entry<String, Integer> phase : s.lastPhaseByChannel().entrySet()) {
            recordPhase(phase.getKey(), phase.getValue());
        }
    }

    private void resetPhaseStateToInstalledLayout() {
        snapshotLayout = Layout.fromKeys(channels.stream()
                .map(AnimatedTileChannel::channelId).toList());
        installedPhases = new int[channels.size()];
        installedPhasePresent = new boolean[channels.size()];
        recordedInstalledPhaseCount = 0;
    }
}
