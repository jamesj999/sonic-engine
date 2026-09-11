package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Object 0x4F - SinkingMud (Sonic 3 & Knuckles).
 *
 * <p>Invisible top-solid mud/quicksand volume used in MGZ and competition zones.
 * Width is {@code subtype << 4} pixels; the surface starts at a half-height of
 * {@code $30} in single-player and {@code $18} in competition mode. While a player
 * stands on the mud, their personal surface depth decreases by 1 per frame. When
 * they step off, it recovers by 2 per frame. If the surface is already exhausted at
 * the start of a standing frame, the player is killed and the depth resets.
 *
 * <p>ROM: {@code Obj_SinkingMud} / {@code SolidObjectTop_1P} (sonic3k.asm:68500-68661)
 */
public class SinkingMudObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider,
        RewindRecreatable {

    private static final int PRIORITY = 4;
    private static final int MAX_RAW_SURFACE = 0x30;

    @Override
    public int romObjectCodePointerHighWord() {
        // Obj_SinkingMud dispatches through $00032AAE.
        return 0x0003;
    }

    private static final float DEBUG_R = 1.0f;
    private static final float DEBUG_G = 1.0f;
    private static final float DEBUG_B = 1.0f;

    private int halfWidth;
    private final Map<PlayableEntity, Integer> rawSurfaceByPlayer = new IdentityHashMap<>();
    private final Map<PlayableEntity, Boolean> standingNextUpdate = new IdentityHashMap<>();
    private final Set<PlayableEntity> killedThisFrame =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private List<PlayableEntity> trackedPlayers = List.of();

    public SinkingMudObjectInstance(ObjectSpawn spawn) {
        super(spawn, "SinkingMud");
        this.halfWidth = (spawn.subtype() & 0xFF) << 3;
    }

    @Override
    public SinkingMudObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SinkingMudObjectInstance(ctx.spawn());
    }

    @Override
    public SolidObjectParams getSolidParams() {
        int surface = sharedSurfaceHeight();
        return SolidObjectParams.of(halfWidth, surface, surface);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // ROM SolidObjectTop_1P computes surface-playerBottom into d0, then
        // accepts only the unsigned range $FFF0..$FFFF. d0 == 0 is below
        // $FFF0 and branches to the no-contact return (sonic3k.asm:41998-42007).
        return true;
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // SolidObjectTop_1P enters the shared stale-rider branch when this
        // object's standing bit is still set and the player is airborne.
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return player != null && !player.getDead() && !killedThisFrame.contains(player);
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (player == null || !contact.standing() || killedThisFrame.contains(player)) {
            return;
        }
        boolean wasStandingAtUpdate = standingNextUpdate.getOrDefault(player, false);
        standingNextUpdate.put(player, true);
        if (wasStandingAtUpdate) {
            // Continued riding uses MvSonicOnPtfm's current-radius absolute
            // surface snap. A fresh SolidObjectTop landing already applied
            // its distinct d0/+3 formula before Player_TouchFloor changed the
            // radius, so re-snapping here would move that landing downward.
            snapPlayerToOwnSurface(player);
        } else if (player instanceof AbstractPlayableSprite sprite
                && preContactYRadius(sprite) > sprite.getYRadius()) {
            // SolidObjectTop applies its fresh-landing y_pos formula using the
            // incoming radius before Player_TouchFloor restores the default.
            // Preserve that native centre across the engine's radius/bounds
            // representation change (notably the MGZ carrier-to-mud handoff).
            int incomingYRadius = preContactYRadius(sprite);
            int targetCentreY = getY()
                    - collisionSurfaceHeight(rawSurfaceByPlayer.getOrDefault(player, MAX_RAW_SURFACE))
                    - incomingYRadius - 1;
            if (sprite.isObjectControlled()) {
                // The carrier still owns y_radius until its later release.
                sprite.applyCustomRadii(sprite.getXRadius(), incomingYRadius);
            }
            NativePositionOps.writeYPosPreserveSubpixel(sprite, targetCentreY);
        }
    }

    @Override
    public void onSolidContactCleared(PlayableEntity player, int frameCounter) {
        if (player != null) {
            standingNextUpdate.put(player, false);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        killedThisFrame.clear();
        ObjectServices svc = tryServices();
        List<PlayableEntity> participants = svc != null
                ? svc.playerQuery().playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)
                : List.of();
        if (player != null && !participants.contains(player)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(player);
            withUpdatePlayer.addAll(participants);
            participants = withUpdatePlayer;
        }
        trackedPlayers = participants;
        for (PlayableEntity entity : trackedPlayers) {
            advancePlayerSurface(entity);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Obj_SinkingMud is invisible. Its collision volume belongs exclusively
        // to the OBJECT_DEBUG pass below, never the normal object render pass.
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null || halfWidth <= 0) {
            return;
        }

        int fullHeight = isCompetitionZone() ? 0x30 : 0x60;
        ctx.drawRect(getX(), getY(), halfWidth, fullHeight / 2,
                DEBUG_R, DEBUG_G, DEBUG_B);
    }

    int rawSurfaceForTest(PlayableEntity player) {
        return rawSurfaceByPlayer.getOrDefault(player, MAX_RAW_SURFACE);
    }

    private void advancePlayerSurface(PlayableEntity player) {
        boolean wasStanding = standingNextUpdate.getOrDefault(player, false);

        int rawSurface = rawSurfaceByPlayer.getOrDefault(player, MAX_RAW_SURFACE);
        if (wasStanding) {
            if (rawSurface == 0) {
                rawSurfaceByPlayer.put(player, MAX_RAW_SURFACE);
                standingNextUpdate.put(player, false);
                killedThisFrame.add(player);
                player.applyCrushDeath();
                ObjectServices svc = tryServices();
                if (svc != null) {
                    ObjectManager objectManager = svc.objectManager();
                    if (objectManager != null) {
                        objectManager.clearRidingObject(player);
                    }
                }
                return;
            }
            rawSurface--;
        } else {
            Integer copiedSurface = copiedSurfaceFromAdjacentMud(player);
            if (copiedSurface != null) {
                rawSurface = copiedSurface;
            } else if (rawSurface < MAX_RAW_SURFACE) {
                // ROM compares against $30 before addq #2, so recovery from
                // $2F overshoots to $31 and remains there on later frames.
                rawSurface += 2;
            }
        }
        rawSurfaceByPlayer.put(player, rawSurface);
    }

    private Integer copiedSurfaceFromAdjacentMud(PlayableEntity player) {
        if (player == null) {
            return null;
        }
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return null;
        }
        ObjectManager objectManager = svc.objectManager();

        if (player.isOnObject()) {
            ObjectInstance riding = objectManager.getRidingObject(player);
            if (riding instanceof SinkingMudObjectInstance mud && riding != this) {
                return mud.rawSurfaceByPlayer.getOrDefault(player, MAX_RAW_SURFACE);
            }
        }

        // The ROM mud routine runs before the player slot. On the frame Sonic
        // jumps off a mud patch, Status_OnObj is still set when the adjacent
        // mud runs, even though the engine's object update sees the cleared
        // post-movement status. The source mud's prior standing contact is
        // the engine-owned equivalent of that routine-entry standing bit.
        for (SinkingMudObjectInstance mud
                : objectManager.activeObjectsOfType(SinkingMudObjectInstance.class)) {
            if (mud != this && mud.standingNextUpdate.getOrDefault(player, false)) {
                return mud.rawSurfaceByPlayer.getOrDefault(player, MAX_RAW_SURFACE);
            }
        }
        return null;
    }

    private void snapPlayerToOwnSurface(PlayableEntity player) {
        int surface = collisionSurfaceHeight(rawSurfaceByPlayer.getOrDefault(player, MAX_RAW_SURFACE));
        int newCentreY = getY() - surface - player.getYRadius();
        int newY = newCentreY - (player.getHeight() / 2);
        player.setY((short) newY);
    }

    private int preContactYRadius(AbstractPlayableSprite player) {
        ObjectServices svc = tryServices();
        return svc != null && svc.objectManager() != null
                ? svc.objectManager().getPreContactYRadius()
                : player.getYRadius();
    }

    private int sharedSurfaceHeight() {
        if (trackedPlayers.isEmpty()) {
            return collisionSurfaceHeight(MAX_RAW_SURFACE);
        }

        int highestSurface = 0;
        for (PlayableEntity player : trackedPlayers) {
            int rawSurface = rawSurfaceByPlayer.getOrDefault(player, MAX_RAW_SURFACE);
            highestSurface = Math.max(highestSurface, collisionSurfaceHeight(rawSurface));
        }
        return highestSurface;
    }

    private int collisionSurfaceHeight(int rawSurface) {
        return isCompetitionZone() ? (rawSurface >> 1) : rawSurface;
    }

    private boolean isCompetitionZone() {
        ObjectServices svc = tryServices();
        if (svc == null) {
            return false;
        }
        int zoneId = svc.romZoneId();
        return zoneId >= Sonic3kZoneIds.ZONE_ALZ && zoneId <= Sonic3kZoneIds.ZONE_EMZ;
    }

}
