package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.events.Sonic3kMHZEvents;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * MHZ2 end-boss arena helper objects allocated by {@code MHZ2_BackgroundEvent}.
 *
 * <p>ROM references: {@code loc_556F8} pillar, {@code loc_55732} tall support,
 * and {@code loc_5577C} spike helpers.
 */
public final class MhzEndBossArenaHelperInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    public enum Role {
        PILLAR,
        TALL_SUPPORT,
        SPIKE
    }

    private static final int SPIKE_COLLISION_FLAGS = 0x8B;

    @RewindTransient(reason = "Zone event workspace owner; helper state is derived each frame.")
    private final Sonic3kMHZEvents events;
    private Role role;
    private int spikeIndex;
    private int spikeTier;
    private boolean alternateSide;
    private int mappingFrame;
    private int priorityBucket;
    private int collisionFlags;
    private boolean spikeDrawActive;

    private MhzEndBossArenaHelperInstance() {
        this(null, Role.PILLAR, 0x4238, 0x02F0, -1, 0, false);
    }

    private MhzEndBossArenaHelperInstance(
            Sonic3kMHZEvents events,
            Role role,
            int x,
            int y,
            int spikeIndex,
            int spikeTier,
            boolean alternateSide) {
        super(new ObjectSpawn(x, y, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0),
                "MHZEndBossArenaHelper");
        this.events = events;
        this.role = role;
        this.spikeIndex = spikeIndex;
        this.spikeTier = spikeTier;
        this.alternateSide = alternateSide;
        initializeRomObjectData();
    }

    public static MhzEndBossArenaHelperInstance pillar(Sonic3kMHZEvents events) {
        return new MhzEndBossArenaHelperInstance(events, Role.PILLAR, 0x4238, 0x02F0, -1, 0, false);
    }

    public static MhzEndBossArenaHelperInstance tallSupport(Sonic3kMHZEvents events) {
        return new MhzEndBossArenaHelperInstance(events, Role.TALL_SUPPORT, 0, 0x0300, -1, 0, false);
    }

    public static MhzEndBossArenaHelperInstance spike(
            Sonic3kMHZEvents events,
            int spikeIndex,
            int spikeTier,
            boolean alternateSide) {
        return new MhzEndBossArenaHelperInstance(events, Role.SPIKE, 0, 0, spikeIndex, spikeTier, alternateSide);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx.objectServices() == null
                || !(ctx.objectServices().levelEventProvider() instanceof Sonic3kLevelEventManager manager)) {
            return null;
        }
        Sonic3kMHZEvents liveEvents = manager.getMhzEvents();
        if (liveEvents == null) {
            return null;
        }
        return pillar(liveEvents);
    }

    private void initializeRomObjectData() {
        switch (role) {
            case PILLAR -> {
                mappingFrame = 0;
                priorityBucket = 1; // priority $80
            }
            case TALL_SUPPORT -> {
                mappingFrame = 1;
                priorityBucket = 7; // priority $380
            }
            case SPIKE -> {
                mappingFrame = 4 - spikeTier;
                priorityBucket = 1 + spikeTier; // priority $80,$100,$180
            }
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (role == Role.SPIKE) {
            updateSpikeState();
        } else if (!events.isEndBossArenaForegroundRefreshActive()) {
            setDestroyed(true);
        } else if (role == Role.TALL_SUPPORT) {
            S3kRuntimeStates.currentMhz(services().zoneRuntimeRegistry())
                    .ifPresent(state -> updateDynamicSpawn(state.endBossArenaTallSupportX(), getY()));
        }
    }

    private void updateSpikeState() {
        spikeDrawActive = false;
        if (!events.isEndBossArenaForegroundRefreshActive()
                || events.isEndBossArenaSpikeDeletionFlagSet()) {
            setDestroyed(true);
            return;
        }
        boolean[] active = events.getEndBossArenaSpikeActiveForTest();
        int[] yTable = events.getEndBossArenaSpikeYForTest();
        boolean isActive = spikeIndex >= 0 && spikeIndex < active.length && active[spikeIndex];
        collisionFlags = isActive && spikeTier == 1 ? SPIKE_COLLISION_FLAGS : 0;
        if (isActive && spikeIndex < yTable.length) {
            spikeDrawActive = true;
            int x = S3kRuntimeStates.currentMhz(services().zoneRuntimeRegistry())
                    .map(state -> state.endBossArenaSpikeX(spikeIndex))
                    .orElse(getX());
            updateDynamicSpawn(x, yTable[spikeIndex]);
        }
    }

    public Role getRole() {
        return role;
    }

    public int getMappingFrame() {
        return mappingFrame;
    }

    public int getSpikeTier() {
        return spikeTier;
    }

    public boolean isAlternateSide() {
        return alternateSide;
    }

    @Override
    public int getPriorityBucket() {
        return priorityBucket;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return switch (role) {
            case PILLAR -> 0x18;
            case TALL_SUPPORT -> 0x0C;
            case SPIKE -> 0x10;
        };
    }

    @Override
    public int getOnScreenHalfHeight() {
        return switch (role) {
            case PILLAR -> 0x18;
            case TALL_SUPPORT -> 0x80;
            case SPIKE -> 0x10;
        };
    }

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (role == Role.SPIKE && !spikeDrawActive) {
            return;
        }
        String key = role == Role.SPIKE
                ? Sonic3kObjectArtKeys.MHZ_END_BOSS_SPIKES
                : Sonic3kObjectArtKeys.MHZ_END_BOSS_PILLAR;
        PatternSpriteRenderer renderer = getRenderer(key);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
        }
    }
}
